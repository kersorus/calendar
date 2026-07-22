import { DurableObject } from "cloudflare:workers";
import { AppError, publicError } from "./errors.js";
import { decryptSecret, encryptSecret, hashToken, randomToken } from "./crypto.js";
import { refreshGoogleAccessToken, revokeGoogleToken } from "./google-oauth.js";
import {
  deleteDriveFile,
  downloadBackup,
  findBackup,
  listManagedBackups,
  renameBackup,
  uploadBackup,
} from "./google-drive.js";
import { mergeCloudPayloads } from "./cloud-record.js";

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

async function inputJson(request) {
  try {
    return await request.json();
  } catch (error) {
    throw new AppError("Некорректный запрос", {
      code: "INVALID_JSON",
      status: 400,
      cause: error,
    });
  }
}

function backupFileName(env) {
  return String(env.DRIVE_BACKUP_FILE_NAME || "las_salary_backup.json").trim()
    || "las_salary_backup.json";
}

export class UserState extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
    this.queue = Promise.resolve();
  }

  fetch(request) {
    const task = this.queue.then(() => this.route(request));
    this.queue = task.catch(() => undefined);
    return task.catch(error => {
      const failure = publicError(error);
      if (failure.status >= 500) {
        console.error(JSON.stringify({
          severity: "ERROR",
          code: failure.code,
          message: error?.message || failure.message,
        }));
      }
      return json({
        error: {
          code: failure.code,
          message: failure.message,
          details: failure.details,
        },
      }, failure.status);
    });
  }

  async route(request) {
    const path = new URL(request.url).pathname;
    if (request.method === "POST" && path === "/authorize") return this.authorize(request);
    if (request.method === "POST" && path === "/session/remove") return this.removeSession(request);
    if (request.method === "POST" && path === "/sync") return this.synchronize(request);
    if (request.method === "DELETE" && path === "/access") return this.revokeAccess();
    if (request.method === "DELETE" && path === "/cloud-data") return this.deleteCloudData();
    throw new AppError("Внутренний маршрут не найден", { code: "NOT_FOUND", status: 404 });
  }

  async getUser() {
    return (await this.ctx.storage.get("user")) || null;
  }

  async saveUser(data) {
    const previous = await this.getUser();
    const now = new Date().toISOString();
    const next = {
      ...previous,
      ...data,
      createdAt: previous?.createdAt || now,
      updatedAt: now,
    };
    await this.ctx.storage.put("user", next);
    return next;
  }

  async authorize(request) {
    const input = await inputJson(request);
    const account = input.account;
    if (!account?.sub || !account.email) {
      throw new AppError("Не удалось определить Google-аккаунт", {
        code: "GOOGLE_PROFILE_INVALID",
        status: 401,
      });
    }

    const previous = await this.getUser();
    let encryptedRefreshToken = previous?.encryptedRefreshToken || "";
    if (input.refreshToken) {
      encryptedRefreshToken = await encryptSecret(input.refreshToken, this.env.TOKEN_ENCRYPTION_KEY);
    }
    if (!encryptedRefreshToken) {
      throw new AppError(
        "Google не выдал постоянный доступ. Удалите прежнее разрешение приложения в настройках аккаунта Google и подключите его снова.",
        { code: "GOOGLE_REFRESH_TOKEN_MISSING", status: 409 },
      );
    }

    await this.saveUser({ encryptedRefreshToken, account, oauthInvalid: false });

    const sessionToken = randomToken(32);
    const sessionHash = await hashToken(sessionToken);
    const session = this.env.SESSIONS.get(this.env.SESSIONS.idFromName(sessionHash));
    const sessionKey = `session:${sessionHash}`;
    await this.ctx.storage.put(sessionKey, true);
    const result = await session.fetch("https://session/create", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: account.sub, account }),
    });
    if (!result.ok) {
      await this.ctx.storage.delete(sessionKey);
      throw new AppError("Не удалось создать серверную сессию", {
        code: "SESSION_CREATE_FAILED",
        status: 500,
      });
    }
    return json({ account, sessionToken });
  }

  async removeSession(request) {
    const input = await inputJson(request);
    if (input.sessionHash) await this.ctx.storage.delete(`session:${input.sessionHash}`);
    return new Response(null, { status: 204 });
  }

  async authorizedAccessToken() {
    const user = await this.getUser();
    if (!user?.encryptedRefreshToken || user.oauthInvalid) {
      throw new AppError("Подключите Google повторно", {
        code: "GOOGLE_REAUTH_REQUIRED",
        status: 401,
      });
    }

    const refreshToken = await decryptSecret(
      user.encryptedRefreshToken,
      this.env.TOKEN_ENCRYPTION_KEY,
    );
    try {
      const tokens = await refreshGoogleAccessToken({
        refreshToken,
        clientId: this.env.GOOGLE_CLIENT_ID,
        clientSecret: this.env.GOOGLE_CLIENT_SECRET,
      });
      const currentRefreshToken = tokens.refresh_token || refreshToken;
      if (tokens.refresh_token) {
        await this.saveUser({
          encryptedRefreshToken: await encryptSecret(
            currentRefreshToken,
            this.env.TOKEN_ENCRYPTION_KEY,
          ),
          oauthInvalid: false,
        });
      }
      return { accessToken: tokens.access_token, refreshToken: currentRefreshToken };
    } catch (error) {
      if (error?.code === "GOOGLE_REAUTH_REQUIRED") await this.invalidateOauth();
      throw error;
    }
  }

  async invalidateSessions() {
    const sessions = await this.ctx.storage.list({ prefix: "session:" });
    const keys = [...sessions.keys()];
    for (let offset = 0; offset < keys.length; offset += 20) {
      const batch = keys.slice(offset, offset + 20);
      await Promise.all(batch.map(async key => {
        const sessionHash = key.slice("session:".length);
        const session = this.env.SESSIONS.get(this.env.SESSIONS.idFromName(sessionHash));
        await session.fetch("https://session/delete", { method: "DELETE" }).catch(() => undefined);
      }));
    }
    if (keys.length) await this.ctx.storage.delete(keys);
  }

  async invalidateOauth() {
    await this.saveUser({ oauthInvalid: true });
    await this.invalidateSessions();
  }

  async synchronize(request) {
    const input = await inputJson(request);
    if (!input.payload) {
      throw new AppError("Нет данных для синхронизации", {
        code: "PAYLOAD_MISSING",
        status: 400,
      });
    }

    try {
      const { accessToken } = await this.authorizedAccessToken();
      const name = backupFileName(this.env);
      let file = await findBackup(accessToken, name);
      let remotePayload = null;
      let recoveredCorruptBackup = false;

      if (file) {
        try {
          remotePayload = await downloadBackup(accessToken, file);
        } catch (error) {
          if (error?.code !== "INVALID_BACKUP_JSON") throw error;
          const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
          await renameBackup(accessToken, file, `${name}.corrupt-${timestamp}`);
          file = null;
          recoveredCorruptBackup = true;
        }
      }

      let mergedPayload;
      try {
        mergedPayload = mergeCloudPayloads(input.payload, remotePayload);
      } catch (error) {
        if (!remotePayload || error?.code !== "INVALID_BACKUP_FORMAT") throw error;
        const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
        await renameBackup(accessToken, file, `${name}.corrupt-${timestamp}`);
        file = null;
        recoveredCorruptBackup = true;
        mergedPayload = mergeCloudPayloads(input.payload, null);
      }

      const uploadedFile = await uploadBackup(accessToken, name, mergedPayload, file);
      return json({
        payload: mergedPayload,
        file: uploadedFile,
        hadRemoteBackup: Boolean(remotePayload),
        recoveredCorruptBackup,
        serverTime: new Date().toISOString(),
      });
    } catch (error) {
      if (error?.code === "GOOGLE_REAUTH_REQUIRED") await this.invalidateOauth();
      throw error;
    }
  }

  async revokeAccess() {
    const user = await this.getUser();
    if (user?.encryptedRefreshToken) {
      try {
        const refreshToken = await decryptSecret(
          user.encryptedRefreshToken,
          this.env.TOKEN_ENCRYPTION_KEY,
        );
        await revokeGoogleToken(refreshToken);
      } catch (error) {
        console.warn("Google token revocation failed", error?.message || "unknown error");
      }
    }
    await this.invalidateSessions();
    await this.ctx.storage.deleteAll();
    return new Response(null, { status: 204 });
  }

  async deleteCloudData() {
    let deletedFiles = 0;
    try {
      const { accessToken, refreshToken } = await this.authorizedAccessToken();
      const files = await listManagedBackups(accessToken, backupFileName(this.env));
      for (const file of files) await deleteDriveFile(accessToken, file.id);
      deletedFiles = files.length;
      await revokeGoogleToken(refreshToken);
    } catch (error) {
      if (error?.code === "GOOGLE_REAUTH_REQUIRED") await this.invalidateOauth();
      throw error;
    }
    await this.invalidateSessions();
    await this.ctx.storage.deleteAll();
    return json({ deletedFiles });
  }
}
