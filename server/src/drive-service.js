import { Readable } from "node:stream";
import { google } from "googleapis";
import { AppError } from "./errors.js";
import { mergeCloudPayloads } from "./cloud-record.js";
import { randomId } from "./crypto.js";

function escapeQuery(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function isInvalidGrant(error) {
  const text = [
    error?.message,
    error?.response?.data?.error,
    error?.response?.data?.error_description,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  return text.includes("invalid_grant") || text.includes("invalid credentials");
}

export class DriveService {
  constructor({ authService, store, backupFileName }) {
    this.authService = authService;
    this.store = store;
    this.backupFileName = backupFileName;
  }

  async withLease(userId, operation) {
    const owner = randomId();
    let lastError = null;
    for (let attempt = 0; attempt < 7; attempt += 1) {
      try {
        await this.store.acquireSyncLease(userId, owner);
        try {
          return await operation();
        } finally {
          await this.store.releaseSyncLease(userId, owner).catch(error => {
            console.error("Sync lease release failed:", error?.message || "unknown error");
          });
        }
      } catch (error) {
        if (error?.code !== "SYNC_BUSY") throw error;
        lastError = error;
        await sleep(180 + Math.floor(Math.random() * 220));
      }
    }
    throw lastError;
  }

  async drive(userId) {
    try {
      const auth = await this.authService.authorizedClient(userId);
      return google.drive({ version: "v3", auth });
    } catch (error) {
      if (error?.status === 401) throw error;
      throw new AppError("Не удалось подключиться к Google Drive", {
        code: "DRIVE_AUTH_FAILED",
        status: 502,
        cause: error,
      });
    }
  }

  async findBackup(drive) {
    const result = await drive.files.list({
      spaces: "appDataFolder",
      q: `name='${escapeQuery(this.backupFileName)}' and trashed=false`,
      orderBy: "modifiedTime desc",
      pageSize: 10,
      fields: "files(id,name,modifiedTime,createdTime,size,appProperties)",
    });
    return result.data.files?.[0] || null;
  }

  async download(drive, file) {
    if (!file) return null;
    const result = await drive.files.get(
      { fileId: file.id, alt: "media" },
      { responseType: "text" },
    );
    try {
      if (typeof result.data === "string") return JSON.parse(result.data);
      if (Buffer.isBuffer(result.data)) return JSON.parse(result.data.toString("utf8"));
      if (result.data && typeof result.data === "object") return result.data;
      throw new Error("empty response");
    } catch (error) {
      throw new AppError("Облачная копия содержит повреждённый JSON", {
        code: "INVALID_BACKUP_JSON",
        status: 409,
        cause: error,
      });
    }
  }

  async upload(drive, payload, file) {
    const body = Readable.from([JSON.stringify(payload)]);
    const media = { mimeType: "application/json", body };
    const fields = "id,name,modifiedTime,createdTime,size,appProperties";

    if (file) {
      const result = await drive.files.update({ fileId: file.id, media, fields });
      return result.data;
    }

    const result = await drive.files.create({
      requestBody: {
        name: this.backupFileName,
        parents: ["appDataFolder"],
        appProperties: { app: "las-salary", schemaVersion: "3" },
      },
      media,
      fields,
    });
    return result.data;
  }

  async preserveCorruptBackup(drive, file) {
    if (!file) return;
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
    await drive.files.update({
      fileId: file.id,
      requestBody: { name: `${this.backupFileName}.corrupt-${timestamp}` },
      fields: "id,name,modifiedTime",
    });
  }


  async listManagedBackups(drive) {
    const files = [];
    let pageToken = undefined;
    do {
      const result = await drive.files.list({
        spaces: "appDataFolder",
        q: "trashed=false",
        pageSize: 1000,
        pageToken,
        fields: "nextPageToken,files(id,name,appProperties)",
      });
      for (const file of result.data.files || []) {
        const managedName = file.name === this.backupFileName
          || file.name?.startsWith(`${this.backupFileName}.corrupt-`);
        if (managedName || file.appProperties?.app === "las-salary") files.push(file);
      }
      pageToken = result.data.nextPageToken || undefined;
    } while (pageToken);
    return files;
  }

  async deleteCloudData(userId) {
    return this.withLease(userId, async () => {
      try {
        const drive = await this.drive(userId);
        const files = await this.listManagedBackups(drive);
        for (const file of files) await drive.files.delete({ fileId: file.id });
        await this.authService.revoke(userId);
        return { deletedFiles: files.length };
      } catch (error) {
        if (isInvalidGrant(error)) {
          await this.authService.invalidateUser(userId);
          throw new AppError("Доступ Google был отозван. Серверные сессии удалены, но копию в Drive нужно удалить через настройки Google Drive.", {
            code: "GOOGLE_REAUTH_REQUIRED",
            status: 401,
            cause: error,
          });
        }
        if (error instanceof AppError) throw error;
        throw new AppError("Не удалось удалить облачные данные", {
          code: "CLOUD_DELETE_FAILED",
          status: 502,
          cause: error,
        });
      }
    });
  }

  async synchronize(userId, localPayload) {
    return this.withLease(userId, async () => {
      try {
        const drive = await this.drive(userId);
        let file = await this.findBackup(drive);
        let remotePayload = null;
        let recoveredCorruptBackup = false;

        if (file) {
          try {
            remotePayload = await this.download(drive, file);
          } catch (error) {
            if (error?.code !== "INVALID_BACKUP_JSON") throw error;
            await this.preserveCorruptBackup(drive, file);
            file = null;
            recoveredCorruptBackup = true;
            console.error("Corrupt Drive backup was preserved:", error?.code || "INVALID_BACKUP");
          }
        }

        let mergedPayload;
        try {
          mergedPayload = mergeCloudPayloads(localPayload, remotePayload);
        } catch (error) {
          if (!remotePayload || error?.code !== "INVALID_BACKUP_FORMAT") throw error;
          await this.preserveCorruptBackup(drive, file);
          file = null;
          recoveredCorruptBackup = true;
          mergedPayload = mergeCloudPayloads(localPayload, null);
        }

        const uploadedFile = await this.upload(drive, mergedPayload, file);
        return {
          payload: mergedPayload,
          file: uploadedFile,
          hadRemoteBackup: Boolean(remotePayload),
          recoveredCorruptBackup,
          serverTime: new Date().toISOString(),
        };
      } catch (error) {
        if (isInvalidGrant(error)) {
          await this.authService.invalidateUser(userId);
          throw new AppError("Доступ Google был отозван. Подключите аккаунт повторно.", {
            code: "GOOGLE_REAUTH_REQUIRED",
            status: 401,
            cause: error,
          });
        }
        if (error instanceof AppError) throw error;
        throw new AppError("Google Drive временно недоступен", {
          code: "DRIVE_SYNC_FAILED",
          status: 502,
          cause: error,
        });
      }
    });
  }
}
