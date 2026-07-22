export class DriveApiError extends Error {
  constructor(message, code = "DRIVE_ERROR", status = 0, details = null) {
    super(message);
    this.name = "DriveApiError";
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

function escapeDriveQuery(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

async function parseError(response) {
  try {
    const body = await response.json();
    return body?.error?.message || body?.error_description || JSON.stringify(body);
  } catch (_) {
    return response.statusText || `HTTP ${response.status}`;
  }
}

export class DriveApi {
  constructor(auth, config = window.LAS_CONFIG || {}) {
    this.auth = auth;
    this.fileName = config.DRIVE_BACKUP_FILE_NAME || "las_salary_backup.json";
    this.apiBase = "https://www.googleapis.com/drive/v3";
    this.uploadBase = "https://www.googleapis.com/upload/drive/v3";
  }

  async #request(url, options = {}) {
    const accessToken = this.auth.getAccessToken();
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", `Bearer ${accessToken}`);

    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
      const message = await parseError(response);
      const code = response.status === 401 ? "AUTH_REQUIRED" : "DRIVE_REQUEST_FAILED";
      throw new DriveApiError(message, code, response.status);
    }
    return response;
  }

  async findBackupFile() {
    const query = [
      `name='${escapeDriveQuery(this.fileName)}'`,
      "trashed=false",
    ].join(" and ");
    const params = new URLSearchParams({
      spaces: "appDataFolder",
      q: query,
      orderBy: "modifiedTime desc",
      pageSize: "10",
      fields: "files(id,name,modifiedTime,createdTime,size,appProperties)",
    });

    const response = await this.#request(`${this.apiBase}/files?${params}`);
    const body = await response.json();
    return Array.isArray(body.files) && body.files.length ? body.files[0] : null;
  }

  async downloadBackup(file = null) {
    const backupFile = file || (await this.findBackupFile());
    if (!backupFile) return null;

    const response = await this.#request(
      `${this.apiBase}/files/${encodeURIComponent(backupFile.id)}?alt=media`,
    );

    let payload;
    try {
      payload = await response.json();
    } catch (error) {
      throw new DriveApiError(
        "Резервная копия в Google Drive повреждена",
        "INVALID_BACKUP_JSON",
        response.status,
        error,
      );
    }

    return { file: backupFile, payload };
  }

  async uploadBackup(payload, file = null) {
    const json = JSON.stringify(payload, null, 2);
    const existing = file || (await this.findBackupFile());

    if (existing?.id) {
      const params = new URLSearchParams({ fields: "id,name,modifiedTime,size,appProperties" });
      const response = await this.#request(
        `${this.uploadBase}/files/${encodeURIComponent(existing.id)}?uploadType=media&${params}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json; charset=UTF-8" },
          body: json,
        },
      );
      return response.json();
    }

    const boundary = `las_salary_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    const metadata = {
      name: this.fileName,
      parents: ["appDataFolder"],
      mimeType: "application/json",
      appProperties: {
        app: "las-salary",
        schema: String(payload.version || 3),
      },
    };
    const body = [
      `--${boundary}`,
      "Content-Type: application/json; charset=UTF-8",
      "",
      JSON.stringify(metadata),
      `--${boundary}`,
      "Content-Type: application/json; charset=UTF-8",
      "",
      json,
      `--${boundary}--`,
      "",
    ].join("\r\n");
    const params = new URLSearchParams({
      uploadType: "multipart",
      fields: "id,name,modifiedTime,size,appProperties",
    });

    const response = await this.#request(`${this.uploadBase}/files?${params}`, {
      method: "POST",
      headers: { "Content-Type": `multipart/related; boundary=${boundary}` },
      body,
    });
    return response.json();
  }
}
