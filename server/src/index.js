import { AppError, publicError } from "./errors.js";
import { hashToken } from "./crypto.js";
import {
  accountFromGoogleProfile,
  exchangeAuthorizationCode,
  verifyGoogleIdToken,
} from "./google-oauth.js";
export { SessionState } from "./session-state.js";
export { UserState } from "./user-state.js";

const authBuckets = new Map();

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

function allowedOrigins(env) {
  const values = String(env.ALLOWED_ORIGINS || "")
    .split(",")
    .map(value => value.trim().replace(/\/$/, ""))
    .filter(Boolean);
  return new Set(values);
}

function requireOrigin(request, env) {
  const origin = request.headers.get("Origin") || "";
  if (!origin || !allowedOrigins(env).has(origin)) {
    throw new AppError("Этот сайт не разрешён для API", {
      code: "ORIGIN_NOT_ALLOWED",
      status: 403,
    });
  }
  return origin;
}

function withCors(response, request, env) {
  const origin = request.headers.get("Origin") || "";
  if (!origin || !allowedOrigins(env).has(origin)) return response;
  const result = new Response(response.body, response);
  result.headers.set("Access-Control-Allow-Origin", origin);
  result.headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With");
  result.headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
  result.headers.set("Vary", "Origin");
  return result;
}

function bearerToken(request) {
  const header = request.headers.get("Authorization") || "";
  return header.match(/^Bearer\s+(.+)$/i)?.[1] || "";
}

async function requestJson(request, maximumBytes = 2 * 1024 * 1024) {
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (declared > maximumBytes) {
    throw new AppError("Запрос слишком большой", { code: "PAYLOAD_TOO_LARGE", status: 413 });
  }
  const text = await request.text();
  if (new TextEncoder().encode(text).length > maximumBytes) {
    throw new AppError("Запрос слишком большой", { code: "PAYLOAD_TOO_LARGE", status: 413 });
  }
  try {
    return text ? JSON.parse(text) : {};
  } catch (error) {
    throw new AppError("Некорректный JSON", { code: "INVALID_JSON", status: 400, cause: error });
  }
}

function checkAuthRate(request) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const now = Date.now();
  const current = authBuckets.get(ip);
  if (!current || current.resetAt <= now) {
    authBuckets.set(ip, { count: 1, resetAt: now + 10 * 60 * 1000 });
    return;
  }
  current.count += 1;
  if (current.count > 20) {
    throw new AppError("Слишком много запросов. Повторите немного позже.", {
      code: "RATE_LIMITED",
      status: 429,
    });
  }
  if (authBuckets.size > 500) {
    for (const [key, bucket] of authBuckets) {
      if (bucket.resetAt <= now) authBuckets.delete(key);
    }
  }
}

async function objectJson(response) {
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (_) {
    payload = null;
  }
  if (!response.ok) {
    throw new AppError(payload?.error?.message || "Внутренняя ошибка хранилища", {
      code: payload?.error?.code || "OBJECT_REQUEST_FAILED",
      status: response.status,
      details: payload?.error?.details || null,
    });
  }
  return payload;
}

function userStub(env, userId) {
  return env.USERS.get(env.USERS.idFromName(String(userId)));
}

async function readSession(request, env) {
  const token = bearerToken(request);
  if (!token) {
    throw new AppError("Подключите Google", { code: "AUTH_REQUIRED", status: 401 });
  }
  const sessionHash = await hashToken(token);
  const session = env.SESSIONS.get(env.SESSIONS.idFromName(sessionHash));
  const response = await session.fetch("https://session/read", { method: "POST" });
  if (!response.ok) {
    throw new AppError("Сессия истекла. Подключите Google повторно.", {
      code: "AUTH_REQUIRED",
      status: 401,
    });
  }
  return { token, sessionHash, sessionStub: session, ...(await response.json()) };
}

async function route(request, env) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/healthz") {
    return json({ ok: true, runtime: "cloudflare-workers" });
  }

  if (request.method === "OPTIONS") {
    requireOrigin(request, env);
    return new Response(null, { status: 204 });
  }

  const origin = requireOrigin(request, env);

  if (request.method === "POST" && url.pathname === "/api/auth/code") {
    checkAuthRate(request);
    if ((request.headers.get("X-Requested-With") || "").toLowerCase() !== "xmlhttprequest") {
      throw new AppError("Некорректный запрос авторизации", {
        code: "AUTH_REQUEST_INVALID",
        status: 403,
      });
    }
    const input = await requestJson(request, 32 * 1024);
    const tokens = await exchangeAuthorizationCode({
      code: input.code,
      redirectUri: origin,
      clientId: env.GOOGLE_CLIENT_ID,
      clientSecret: env.GOOGLE_CLIENT_SECRET,
    });
    if (!tokens.id_token) {
      throw new AppError("Google не вернул данные аккаунта", {
        code: "GOOGLE_ID_TOKEN_MISSING",
        status: 401,
      });
    }
    const profile = await verifyGoogleIdToken(tokens.id_token, env.GOOGLE_CLIENT_ID);
    const account = accountFromGoogleProfile(profile);
    const response = await userStub(env, profile.sub).fetch("https://user/authorize", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ account, refreshToken: tokens.refresh_token || "" }),
    });
    return json(await objectJson(response));
  }

  const session = await readSession(request, env);

  if (request.method === "GET" && url.pathname === "/api/auth/session") {
    return json({ account: session.account });
  }

  if (request.method === "POST" && url.pathname === "/api/auth/logout") {
    await session.sessionStub.fetch("https://session/delete", { method: "DELETE" });
    await userStub(env, session.userId).fetch("https://user/session/remove", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionHash: session.sessionHash }),
    });
    return new Response(null, { status: 204 });
  }

  if (request.method === "DELETE" && url.pathname === "/api/auth/access") {
    const response = await userStub(env, session.userId).fetch("https://user/access", {
      method: "DELETE",
    });
    await objectJson(response);
    return new Response(null, { status: 204 });
  }

  if (request.method === "DELETE" && url.pathname === "/api/cloud-data") {
    const response = await userStub(env, session.userId).fetch("https://user/cloud-data", {
      method: "DELETE",
    });
    return json(await objectJson(response));
  }

  if (request.method === "POST" && url.pathname === "/api/sync") {
    const input = await requestJson(request);
    const response = await userStub(env, session.userId).fetch("https://user/sync", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(input),
    });
    return json(await objectJson(response));
  }

  throw new AppError("Маршрут не найден", { code: "NOT_FOUND", status: 404 });
}

export default {
  async fetch(request, env) {
    try {
      return withCors(await route(request, env), request, env);
    } catch (error) {
      const failure = publicError(error);
      if (failure.status >= 500) {
        console.error(JSON.stringify({
          severity: "ERROR",
          code: failure.code,
          message: error?.message || failure.message,
        }));
      }
      return withCors(json({
        error: {
          code: failure.code,
          message: failure.message,
          details: failure.details,
        },
      }, failure.status), request, env);
    }
  },
};
