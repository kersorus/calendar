export class DriveApiError extends Error {
  constructor(message, code = "CLOUD_ERROR", status = 0, cause = null) {
    super(message, { cause });
    this.name = "DriveApiError";
    this.code = code;
    this.status = status;
  }
}

async function parseError(response) {
  try {
    const body = await response.json();
    return {
      message: body?.error?.message || `Сервер вернул HTTP ${response.status}`,
      code: body?.error?.code || "CLOUD_REQUEST_FAILED",
    };
  } catch (_) {
    return { message: `Сервер вернул HTTP ${response.status}`, code: "CLOUD_REQUEST_FAILED" };
  }
}

function sleep(ms) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
}

export class DriveApi {
  constructor(auth) {
    this.auth = auth;
  }

  async #request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", `Bearer ${this.auth.getSessionToken()}`);
    headers.set("Content-Type", "application/json");
    headers.set("X-Requested-With", "XMLHttpRequest");

    let response;
    try {
      response = await fetch(this.auth.endpoint(path), { ...options, headers });
    } catch (error) {
      throw new DriveApiError(
        "Нет связи с сервером синхронизации",
        "NETWORK_ERROR",
        0,
        error,
      );
    }

    if (!response.ok) {
      const failure = await parseError(response);
      throw new DriveApiError(
        failure.message,
        response.status === 401 ? "AUTH_REQUIRED" : failure.code,
        response.status,
      );
    }
    return response;
  }

  async synchronize(payload) {
    let lastError = null;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const response = await this.#request("api/sync", {
          method: "POST",
          body: JSON.stringify({ payload }),
        });
        return await response.json();
      } catch (error) {
        lastError = error;
        if (error.code !== "SYNC_BUSY" || attempt === 2) throw error;
        await sleep(350 + attempt * 450);
      }
    }
    throw lastError;
  }
}
