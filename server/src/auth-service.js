import { google } from "googleapis";
import { AppError } from "./errors.js";

export class AuthService {
  constructor({ clientId, clientSecret, secretBox, store }) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.secretBox = secretBox;
    this.store = store;
  }

  oauthClient(callbackUri = undefined) {
    return new google.auth.OAuth2(this.clientId, this.clientSecret, callbackUri);
  }

  async exchangeCode({ code, callbackUri }) {
    const client = this.oauthClient(callbackUri);
    let tokens;
    try {
      ({ tokens } = await client.getToken(code));
    } catch (error) {
      throw new AppError("Google не подтвердил код авторизации", {
        code: "GOOGLE_CODE_EXCHANGE_FAILED",
        status: 401,
        cause: error,
      });
    }

    if (!tokens.id_token) {
      throw new AppError("Google не вернул данные аккаунта", {
        code: "GOOGLE_ID_TOKEN_MISSING",
        status: 401,
      });
    }

    let ticket;
    try {
      ticket = await client.verifyIdToken({
        idToken: tokens.id_token,
        audience: this.clientId,
      });
    } catch (error) {
      throw new AppError("Google вернул недействительные данные аккаунта", {
        code: "GOOGLE_ID_TOKEN_INVALID",
        status: 401,
        cause: error,
      });
    }
    const profile = ticket.getPayload();
    if (!profile?.sub) {
      throw new AppError("Не удалось определить Google-аккаунт", {
        code: "GOOGLE_PROFILE_INVALID",
        status: 401,
      });
    }

    const existing = await this.store.getUser(profile.sub);
    const encryptedRefreshToken = tokens.refresh_token
      ? this.secretBox.encrypt(tokens.refresh_token)
      : existing?.encryptedRefreshToken;

    if (!encryptedRefreshToken) {
      throw new AppError(
        "Google не выдал постоянный доступ. Удалите прежнее разрешение приложения в настройках аккаунта Google и подключите его снова.",
        { code: "GOOGLE_REFRESH_TOKEN_MISSING", status: 409 },
      );
    }

    const account = {
      sub: profile.sub,
      email: profile.email || "",
      name: profile.name || profile.email || "Google",
      picture: profile.picture || "",
    };

    await this.store.saveUser(profile.sub, {
      encryptedRefreshToken,
      account,
      oauthInvalid: false,
    });

    const sessionToken = await this.store.createSession(profile.sub, account);
    return { account, sessionToken };
  }

  async authorizedClient(userId) {
    const user = await this.store.getUser(userId);
    if (!user?.encryptedRefreshToken || user.oauthInvalid) {
      throw new AppError("Подключите Google повторно", {
        code: "GOOGLE_REAUTH_REQUIRED",
        status: 401,
      });
    }

    const client = this.oauthClient();
    client.setCredentials({
      refresh_token: this.secretBox.decrypt(user.encryptedRefreshToken),
    });

    client.on("tokens", tokens => {
      if (!tokens.refresh_token) return;
      this.store
        .saveUser(userId, {
          encryptedRefreshToken: this.secretBox.encrypt(tokens.refresh_token),
          oauthInvalid: false,
        })
        .catch(error => console.error("Refresh token rotation could not be saved:", error?.message || "unknown error"));
    });

    return client;
  }

  async invalidateUser(userId) {
    await this.store.saveUser(userId, { oauthInvalid: true });
    await this.store.deleteUserSessions(userId);
  }

  async revoke(userId) {
    const user = await this.store.getUser(userId);
    if (user?.encryptedRefreshToken) {
      const client = this.oauthClient();
      try {
        await client.revokeToken(this.secretBox.decrypt(user.encryptedRefreshToken));
      } catch (error) {
        console.warn("Google token revocation failed:", error?.message || "unknown error");
      }
    }
    await this.store.deleteUserSessions(userId);
    await this.store.deleteUser(userId);
  }
}
