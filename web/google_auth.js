const SESSION_STORAGE_KEY = "las_cloud_session_v1";
const ACCOUNT_STORAGE_KEY = "las_cloud_account_v2";
const GOOGLE_SCOPES = [
  "openid",
  "email",
  "profile",
  "https://www.googleapis.com/auth/drive.appdata",
].join(" ");

export class GoogleAuthError extends Error {
  constructor(message, code = "AUTH_ERROR", cause = null) {
    super(message, { cause });
    this.name = "GoogleAuthError";
    this.code = code;
  }
}

function readJson(key, fallback = null) {
  try {
    const value = localStorage.getItem(key);
    return value ? JSON.parse(value) : fallback;
  } catch (_) {
    return fallback;
  }
}

function normalizedApiBase(config) {
  const raw = String(config.API_BASE_URL || "").trim().replace(/\/$/, "");
  if (!raw || raw.includes("YOUR-CLOUD-RUN-SERVICE")) {
    throw new GoogleAuthError(
      "Укажите адрес Cloud Run в web/config.js.",
      "API_URL_MISSING",
    );
  }
  const url = new URL(raw);
  if (url.protocol !== "https:" && url.hostname !== "localhost") {
    throw new GoogleAuthError("Сервер синхронизации должен работать по HTTPS.", "API_URL_INVALID");
  }
  return url;
}

function normalizedClientId(config) {
  const value = String(config.GOOGLE_CLIENT_ID || "").trim();
  if (!value || value.includes("YOUR-WEB-CLIENT-ID")) {
    throw new GoogleAuthError(
      "Укажите Web OAuth Client ID в web/config.js.",
      "GOOGLE_CLIENT_ID_MISSING",
    );
  }
  if (!value.endsWith(".apps.googleusercontent.com")) {
    throw new GoogleAuthError("Некорректный Google OAuth Client ID.", "GOOGLE_CLIENT_ID_INVALID");
  }
  return value;
}

async function responseError(response) {
  try {
    const body = await response.json();
    return new GoogleAuthError(
      body?.error?.message || `Сервер вернул HTTP ${response.status}`,
      body?.error?.code || (response.status === 401 ? "AUTH_REQUIRED" : "API_ERROR"),
    );
  } catch (_) {
    return new GoogleAuthError(
      `Сервер вернул HTTP ${response.status}`,
      response.status === 401 ? "AUTH_REQUIRED" : "API_ERROR",
    );
  }
}

function waitForGoogleIdentity(timeoutMs = 15_000) {
  if (globalThis.google?.accounts?.oauth2?.initCodeClient) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const timer = window.setInterval(() => {
      if (globalThis.google?.accounts?.oauth2?.initCodeClient) {
        window.clearInterval(timer);
        resolve();
      } else if (Date.now() >= deadline) {
        window.clearInterval(timer);
        reject(new GoogleAuthError(
          "Не удалось загрузить библиотеку входа Google. Проверьте блокировщики и соединение.",
          "GOOGLE_LIBRARY_UNAVAILABLE",
        ));
      }
    }, 100);
  });
}

function popupError(error) {
  const type = error?.type || "unknown";
  if (type === "popup_closed") {
    return new GoogleAuthError("Окно входа было закрыто", "POPUP_CLOSED");
  }
  if (type === "popup_failed_to_open") {
    return new GoogleAuthError(
      "Браузер заблокировал окно входа. Разрешите всплывающие окна для сайта.",
      "POPUP_BLOCKED",
    );
  }
  return new GoogleAuthError("Не удалось открыть вход Google", "GOOGLE_POPUP_ERROR");
}

export class GoogleAuth extends EventTarget {
  constructor(config = window.LAS_CONFIG || {}) {
    super();
    this.config = config;
    this.sessionToken = "";
    this.account = readJson(
      ACCOUNT_STORAGE_KEY,
      readJson("las_google_account_v1", null),
    );
    this.ready = false;
    this.onlineValidated = false;
    this.readyError = null;
    this.pendingConnect = null;

    try {
      this.apiUrl = normalizedApiBase(config);
      this.clientId = normalizedClientId(config);
      this.sessionToken = localStorage.getItem(SESSION_STORAGE_KEY) || "";
      this.ready = true;
    } catch (error) {
      this.readyError = error;
    }
  }

  get cachedAccount() {
    return this.account;
  }

  get apiBaseUrl() {
    return this.apiUrl?.toString().replace(/\/$/, "") || "";
  }

  get isConnected() {
    return Boolean(this.sessionToken);
  }

  getSessionToken() {
    if (!this.sessionToken) {
      throw new GoogleAuthError("Подключите Google для синхронизации.", "AUTH_REQUIRED");
    }
    return this.sessionToken;
  }

  endpoint(path) {
    if (!this.apiUrl) throw this.readyError;
    return new URL(path, `${this.apiBaseUrl}/`).toString();
  }

  #persist() {
    try {
      if (this.sessionToken) localStorage.setItem(SESSION_STORAGE_KEY, this.sessionToken);
      else localStorage.removeItem(SESSION_STORAGE_KEY);
      if (this.account) localStorage.setItem(ACCOUNT_STORAGE_KEY, JSON.stringify(this.account));
      else localStorage.removeItem(ACCOUNT_STORAGE_KEY);
    } catch (error) {
      console.warn("Cloud session could not be persisted", error);
    }
  }

  #emit() {
    this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
  }

  async #api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (this.sessionToken) headers.set("Authorization", `Bearer ${this.sessionToken}`);
    headers.set("X-Requested-With", "XmlHttpRequest");

    let response;
    try {
      response = await fetch(this.endpoint(path), { ...options, headers });
    } catch (error) {
      throw new GoogleAuthError("Нет связи с сервером синхронизации.", "NETWORK_ERROR", error);
    }

    if (!response.ok) throw await responseError(response);
    return response;
  }

  async prepare() {
    if (!this.ready) {
      this.#emit();
      return this.snapshot();
    }

    if (!this.sessionToken) {
      this.onlineValidated = false;
      this.#emit();
      return this.snapshot();
    }

    try {
      const response = await this.#api("api/auth/session");
      const body = await response.json();
      this.account = body.account || this.account;
      this.onlineValidated = true;
      this.#persist();
    } catch (error) {
      if (error.code === "AUTH_REQUIRED") {
        this.invalidate({ forgetAccount: false });
      } else if (error.code === "NETWORK_ERROR") {
        this.onlineValidated = false;
      } else {
        this.readyError = error;
      }
    }

    this.#emit();
    return this.snapshot();
  }

  async connect() {
    if (!this.ready) throw this.readyError;
    if (this.pendingConnect) return this.pendingConnect;
    this.pendingConnect = this.#requestAuthorizationCode().finally(() => {
      this.pendingConnect = null;
    });
    return this.pendingConnect;
  }

  async #requestAuthorizationCode() {
    await waitForGoogleIdentity();

    const code = await new Promise((resolve, reject) => {
      const client = google.accounts.oauth2.initCodeClient({
        client_id: this.clientId,
        scope: GOOGLE_SCOPES,
        include_granted_scopes: true,
        select_account: true,
        ux_mode: "popup",
        callback: response => {
          if (response?.error || !response?.code) {
            reject(new GoogleAuthError(
              response?.error_description || response?.error || "Google не вернул код авторизации",
              response?.error || "GOOGLE_CODE_MISSING",
            ));
            return;
          }
          resolve(response.code);
        },
        error_callback: error => reject(popupError(error)),
      });
      client.requestCode();
    });

    const response = await this.#api("api/auth/code", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
    const result = await response.json();

    this.sessionToken = result.sessionToken;
    this.account = result.account || null;
    this.onlineValidated = true;
    this.#persist();
    this.#emit();
    return this.snapshot();
  }

  invalidate({ forgetAccount = false } = {}) {
    this.sessionToken = "";
    this.onlineValidated = false;
    if (forgetAccount) this.account = null;
    this.#persist();
    this.#emit();
  }

  async disconnect({ forgetAccount = true } = {}) {
    if (this.sessionToken) {
      try {
        await this.#api("api/auth/logout", { method: "POST" });
      } catch (error) {
        if (error.code !== "NETWORK_ERROR" && error.code !== "AUTH_REQUIRED") throw error;
      }
    }
    this.invalidate({ forgetAccount });
  }

  async revokeAccess() {
    if (!this.sessionToken) return;
    await this.#api("api/auth/access", { method: "DELETE" });
    this.invalidate({ forgetAccount: true });
  }

  async deleteCloudData() {
    if (!this.sessionToken) return { deletedFiles: 0 };
    const response = await this.#api("api/cloud-data", { method: "DELETE" });
    const result = await response.json();
    this.invalidate({ forgetAccount: true });
    return result;
  }

  snapshot() {
    return {
      ready: this.ready,
      connected: this.isConnected,
      onlineValidated: this.onlineValidated,
      account: this.account,
      apiBaseUrl: this.apiBaseUrl,
      error: this.readyError,
    };
  }
}
