const ACCOUNT_STORAGE_KEY = "las_google_account_v1";

export class GoogleAuthError extends Error {
  constructor(message, code = "AUTH_ERROR", cause = null) {
    super(message);
    this.name = "GoogleAuthError";
    this.code = code;
    this.cause = cause;
  }
}

function readJson(key, fallback = null) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch (_) {
    return fallback;
  }
}

function waitForGoogleIdentity(timeoutMs = 12000) {
  if (window.google?.accounts?.oauth2) return Promise.resolve();

  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      if (window.google?.accounts?.oauth2) {
        window.clearInterval(timer);
        resolve();
      } else if (Date.now() - startedAt >= timeoutMs) {
        window.clearInterval(timer);
        reject(
          new GoogleAuthError(
            "Не удалось загрузить Google Identity Services. Проверьте интернет и блокировщик рекламы.",
            "GIS_UNAVAILABLE",
          ),
        );
      }
    }, 100);
  });
}

async function fetchProfile(accessToken) {
  const response = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
    headers: { Authorization: `Bearer ${accessToken}` },
  });

  if (!response.ok) {
    throw new GoogleAuthError(
      `Google вернул ошибку профиля: ${response.status}`,
      "PROFILE_ERROR",
    );
  }

  const profile = await response.json();
  return {
    sub: profile.sub || "",
    email: profile.email || "",
    name: profile.name || profile.email || "Google",
    picture: profile.picture || "",
  };
}

export class GoogleAuth extends EventTarget {
  constructor(config = window.LAS_CONFIG || {}) {
    super();
    this.clientId = config.GOOGLE_CLIENT_ID || "";
    this.scopes = config.GOOGLE_SCOPES || "";
    this.tokenClient = null;
    this.accessToken = "";
    this.expiresAt = 0;
    this.account = readJson(ACCOUNT_STORAGE_KEY, null);
    this.pendingRequest = null;
    this.expiryTimer = 0;
    this.ready = Boolean(window.google?.accounts?.oauth2);
    this.readyError = null;
    this.preparePromise = this.ready
      ? Promise.resolve(true)
      : this.prepare().catch(error => {
          this.readyError = error;
          return false;
        });
  }

  get cachedAccount() {
    return this.account;
  }

  async prepare() {
    try {
      await waitForGoogleIdentity();
      this.ready = true;
      this.readyError = null;
      this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
      return true;
    } catch (error) {
      this.ready = false;
      this.readyError = error;
      this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
      throw error;
    }
  }

  get isConnected() {
    return Boolean(this.accessToken) && Date.now() < this.expiresAt - 30_000;
  }

  getAccessToken() {
    if (!this.isConnected) {
      throw new GoogleAuthError(
        "Сессия Google истекла. Нажмите «Подключить Google» ещё раз.",
        "AUTH_REQUIRED",
      );
    }
    return this.accessToken;
  }

  async connect({ prompt = "select_account" } = {}) {
    if (!this.clientId || this.clientId.includes("YOUR_")) {
      throw new GoogleAuthError(
        "Укажите OAuth Client ID в web/config.js.",
        "CLIENT_ID_MISSING",
      );
    }

    if (!this.ready) {
      if (this.readyError) throw this.readyError;
      throw new GoogleAuthError(
        "Google ещё загружается. Повторите через несколько секунд.",
        "GIS_LOADING",
      );
    }

    if (this.pendingRequest) return this.pendingRequest;

    this.pendingRequest = this.#requestToken(prompt).finally(() => {
      this.pendingRequest = null;
    });
    return this.pendingRequest;
  }

  async #requestToken(prompt) {
    const tokenResponse = await new Promise((resolve, reject) => {
      this.tokenClient = window.google.accounts.oauth2.initTokenClient({
        client_id: this.clientId,
        scope: this.scopes,
        callback: response => {
          if (response?.error) {
            reject(
              new GoogleAuthError(
                response.error_description || response.error,
                response.error,
              ),
            );
            return;
          }
          resolve(response);
        },
        error_callback: error => {
          reject(
            new GoogleAuthError(
              error?.message || "Окно входа Google было закрыто",
              error?.type || "POPUP_ERROR",
              error,
            ),
          );
        },
      });

      this.tokenClient.requestAccessToken({ prompt });
    });

    const driveScope = "https://www.googleapis.com/auth/drive.appdata";
    const hasDriveScope = window.google.accounts.oauth2.hasGrantedAllScopes
      ? window.google.accounts.oauth2.hasGrantedAllScopes(tokenResponse, driveScope)
      : String(tokenResponse.scope || this.scopes).split(/\s+/).includes(driveScope);
    if (!hasDriveScope) {
      throw new GoogleAuthError(
        "Без разрешения на папку данных Google Drive синхронизация невозможна.",
        "SCOPE_DENIED",
      );
    }

    this.accessToken = tokenResponse.access_token;
    const expiresInSeconds = Number(tokenResponse.expires_in) || 3600;
    this.expiresAt = Date.now() + expiresInSeconds * 1000;
    window.clearTimeout(this.expiryTimer);
    this.expiryTimer = window.setTimeout(() => {
      this.invalidate();
    }, Math.max(0, expiresInSeconds * 1000 - 25_000));

    try {
      this.account = await fetchProfile(this.accessToken);
      try {
        localStorage.setItem(ACCOUNT_STORAGE_KEY, JSON.stringify(this.account));
      } catch (_) {
        // The account label is optional; sync continues when storage is restricted.
      }
    } catch (error) {
      // Drive still works even if the optional profile endpoint is unavailable.
      this.account = this.account || { email: "", name: "Google", picture: "" };
      console.warn("Google profile lookup failed", error);
    }

    this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
    return this.snapshot();
  }

  invalidate() {
    window.clearTimeout(this.expiryTimer);
    this.expiryTimer = 0;
    this.accessToken = "";
    this.expiresAt = 0;
    this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
  }

  async disconnect({ forgetAccount = false } = {}) {
    const token = this.accessToken;
    window.clearTimeout(this.expiryTimer);
    this.expiryTimer = 0;
    this.accessToken = "";
    this.expiresAt = 0;

    if (token && window.google?.accounts?.oauth2?.revoke) {
      await new Promise(resolve => {
        window.google.accounts.oauth2.revoke(token, () => resolve());
      });
    }

    if (forgetAccount) {
      this.account = null;
      try {
        localStorage.removeItem(ACCOUNT_STORAGE_KEY);
      } catch (_) {
        // Ignore restricted storage; the token has already been removed from memory.
      }
    }

    this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
  }

  snapshot() {
    return {
      ready: this.ready,
      connected: this.isConnected,
      expiresAt: this.expiresAt || null,
      account: this.account,
    };
  }
}
