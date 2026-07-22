import { AppError, readJson } from "./errors.js";
import { base64UrlToBytes, jsonFromBase64Url, utf8 } from "./encoding.js";

const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
const JWKS_ENDPOINT = "https://www.googleapis.com/oauth2/v3/certs";
const VALID_ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);

function validateClientConfig(clientId, clientSecret) {
  if (!clientId?.trim() || !clientSecret?.trim()) {
    throw new AppError("Google OAuth не настроен на сервере", {
      code: "OAUTH_SERVER_NOT_CONFIGURED",
      status: 503,
    });
  }
}

async function tokenRequest(parameters, { clientId, clientSecret, fetchImpl = fetch }) {
  validateClientConfig(clientId, clientSecret);
  const body = new URLSearchParams({
    client_id: clientId,
    client_secret: clientSecret,
    ...parameters,
  });

  let response;
  try {
    response = await fetchImpl(TOKEN_ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
  } catch (error) {
    throw new AppError("Сервер Google OAuth временно недоступен", {
      code: "GOOGLE_OAUTH_UNAVAILABLE",
      status: 502,
      cause: error,
    });
  }

  const payload = await readJson(response, "Google OAuth вернул некорректный ответ");
  if (!response.ok) {
    const invalidGrant = payload?.error === "invalid_grant";
    throw new AppError(
      invalidGrant ? "Доступ Google истёк или был отозван" : "Google отклонил запрос авторизации",
      {
        code: invalidGrant ? "GOOGLE_REAUTH_REQUIRED" : "GOOGLE_OAUTH_FAILED",
        status: invalidGrant ? 401 : 502,
        details: payload?.error || null,
      },
    );
  }
  return payload || {};
}

export async function exchangeAuthorizationCode({
  code,
  redirectUri,
  clientId,
  clientSecret,
  fetchImpl = fetch,
}) {
  if (typeof code !== "string" || !code.trim() || code.length > 8192) {
    throw new AppError("Google не вернул корректный код авторизации", {
      code: "OAUTH_CODE_INVALID",
      status: 400,
    });
  }
  return tokenRequest(
    {
      code: code.trim(),
      grant_type: "authorization_code",
      redirect_uri: redirectUri,
    },
    { clientId, clientSecret, fetchImpl },
  );
}

export async function refreshGoogleAccessToken({
  refreshToken,
  clientId,
  clientSecret,
  fetchImpl = fetch,
}) {
  const payload = await tokenRequest(
    { refresh_token: refreshToken, grant_type: "refresh_token" },
    { clientId, clientSecret, fetchImpl },
  );
  if (!payload.access_token) {
    throw new AppError("Google не вернул ключ доступа к Drive", {
      code: "GOOGLE_ACCESS_TOKEN_MISSING",
      status: 502,
    });
  }
  return payload;
}

export async function revokeGoogleToken(token, fetchImpl = fetch) {
  if (!token) return false;
  try {
    const response = await fetchImpl(REVOKE_ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token }),
    });
    return response.ok;
  } catch (_) {
    return false;
  }
}

async function fetchGoogleJwks(fetchImpl) {
  let response;
  try {
    response = await fetchImpl(JWKS_ENDPOINT, {
      headers: { Accept: "application/json" },
    });
  } catch (error) {
    throw new AppError("Не удалось проверить данные Google-аккаунта", {
      code: "GOOGLE_JWKS_UNAVAILABLE",
      status: 502,
      cause: error,
    });
  }
  const payload = await readJson(response, "Google вернул некорректные ключи проверки");
  if (!response.ok || !Array.isArray(payload?.keys)) {
    throw new AppError("Не удалось проверить данные Google-аккаунта", {
      code: "GOOGLE_JWKS_INVALID",
      status: 502,
    });
  }
  return payload.keys;
}

function includesAudience(aud, clientId) {
  return Array.isArray(aud) ? aud.includes(clientId) : aud === clientId;
}

export async function verifyGoogleIdToken(idToken, clientId, {
  fetchImpl = fetch,
  now = Date.now(),
} = {}) {
  try {
    const parts = String(idToken || "").split(".");
    if (parts.length !== 3) throw new Error("invalid JWT");
    const [encodedHeader, encodedPayload, encodedSignature] = parts;
    const header = jsonFromBase64Url(encodedHeader);
    const payload = jsonFromBase64Url(encodedPayload);

    if (header.alg !== "RS256" || !header.kid) throw new Error("unsupported JWT header");
    const keys = await fetchGoogleJwks(fetchImpl);
    const jwk = keys.find(candidate => candidate.kid === header.kid && candidate.kty === "RSA");
    if (!jwk) throw new Error("signing key not found");

    const verificationKey = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    const validSignature = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      verificationKey,
      base64UrlToBytes(encodedSignature),
      utf8(`${encodedHeader}.${encodedPayload}`),
    );
    if (!validSignature) throw new Error("invalid signature");

    const nowSeconds = Math.floor(now / 1000);
    if (!VALID_ISSUERS.has(payload.iss)) throw new Error("invalid issuer");
    if (!includesAudience(payload.aud, clientId)) throw new Error("invalid audience");
    if (Array.isArray(payload.aud) && payload.aud.length > 1 && payload.azp !== clientId) {
      throw new Error("invalid authorized party");
    }
    if (!Number.isFinite(payload.exp) || payload.exp <= nowSeconds - 30) throw new Error("expired token");
    if (Number.isFinite(payload.iat) && payload.iat > nowSeconds + 300) throw new Error("future token");
    if (typeof payload.sub !== "string" || !payload.sub) throw new Error("missing subject");

    return payload;
  } catch (error) {
    if (error instanceof AppError) throw error;
    throw new AppError("Google вернул недействительные данные аккаунта", {
      code: "GOOGLE_ID_TOKEN_INVALID",
      status: 401,
      cause: error,
    });
  }
}

export function accountFromGoogleProfile(profile) {
  return {
    sub: profile.sub,
    email: typeof profile.email === "string" ? profile.email : "",
    name: typeof profile.name === "string" && profile.name
      ? profile.name
      : (profile.email || "Google"),
    picture: typeof profile.picture === "string" ? profile.picture : "",
  };
}
