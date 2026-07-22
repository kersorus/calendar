import { GoogleAuth, GoogleAuthError } from "./google_auth.js";
import { DriveApi, DriveApiError } from "./drive_api.js";
import { DriveSyncEngine } from "./drive_sync.js";

const PREFERENCES_KEY = "las_cloud_preferences_v3";

function readPreferences() {
  try {
    const previous = JSON.parse(localStorage.getItem("las_cloud_preferences_v2") || "{}");
    const current = JSON.parse(localStorage.getItem(PREFERENCES_KEY) || "{}");
    return {
      autoSync: true,
      previouslyConnected: false,
      ...previous,
      ...current,
    };
  } catch (_) {
    return { autoSync: true, previouslyConnected: false };
  }
}

function sameState(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

export class CloudManager extends EventTarget {
  constructor(defaults) {
    super();
    this.defaults = defaults;
    this.preferences = readPreferences();
    this.auth = new GoogleAuth();
    this.drive = new DriveApi(this.auth);
    this.engine = new DriveSyncEngine(this.drive, defaults);
    this.phase = "local";
    this.message = "Данные хранятся только на этом устройстве";
    this.lastError = null;
    this.initialized = false;
    this.syncPromise = null;
    this.autoSyncTimer = 0;
  }

  #savePreferences() {
    try {
      localStorage.setItem(PREFERENCES_KEY, JSON.stringify(this.preferences));
    } catch (error) {
      console.warn("Cloud preferences could not be persisted", error);
    }
  }

  #emit() {
    this.dispatchEvent(new CustomEvent("change", { detail: this.snapshot() }));
  }

  #setPhase(phase, message, error = null) {
    this.phase = phase;
    this.message = message;
    this.lastError = error;
    this.#emit();
  }

  async init() {
    if (this.initialized) return this.snapshot();
    if (!window.LaStorage) throw new Error("LaStorage ещё не инициализирован");

    this.engine.initialize(window.LaStorage.getState());
    this.initialized = true;

    window.addEventListener("las-state-changed", event => {
      if (event.detail?.source === "cloud") return;
      this.engine.markLocalChanges(window.LaStorage.getState());
      if (this.preferences.autoSync && this.auth.isConnected) this.#scheduleAutomaticSync();
    });

    window.addEventListener("online", () => {
      if (this.auth.isConnected && this.preferences.autoSync) this.#scheduleAutomaticSync(200);
    });
    window.addEventListener("offline", () => {
      if (this.auth.isConnected) {
        this.#setPhase("offline", "Нет интернета. Изменения сохраняются локально и синхронизируются позже.");
      }
    });

    this.auth.addEventListener("change", () => this.#emit());
    await this.auth.prepare();

    if (!this.auth.ready) {
      this.#setPhase("error", this.auth.snapshot().error?.message || "Не настроен сервер синхронизации");
    } else if (this.auth.isConnected && navigator.onLine) {
      try {
        await this.syncNow({ reason: "startup" });
      } catch (_) {
        // syncNow already updates the visible status.
      }
    } else if (this.auth.isConnected) {
      this.#setPhase("offline", "Аккаунт подключён. Синхронизация продолжится после появления интернета.");
    } else if (this.preferences.previouslyConnected || this.auth.cachedAccount) {
      this.#setPhase("authRequired", "После обновления нужно один раз подключить Google заново.");
    } else {
      this.#setPhase("local", "Данные хранятся только на этом устройстве");
    }

    return this.snapshot();
  }

  async connect() {
    this.#setPhase("connecting", "Открываем безопасный вход Google…");
    try {
      await this.auth.connect();
      this.preferences.previouslyConnected = true;
      this.#savePreferences();
      return await this.syncNow({ reason: "connect" });
    } catch (error) {
      this.#handleError(error);
      throw error;
    }
  }

  async disconnect() {
    window.clearTimeout(this.autoSyncTimer);
    await this.auth.disconnect({ forgetAccount: true });
    this.preferences.previouslyConnected = false;
    this.#savePreferences();
    this.#setPhase("local", "Google отключён на этом устройстве. Локальные данные сохранены.");
  }

  async revokeAccess() {
    window.clearTimeout(this.autoSyncTimer);
    await this.auth.revokeAccess();
    this.preferences.previouslyConnected = false;
    this.#savePreferences();
    this.#setPhase("local", "Доступ Google отозван на всех устройствах. Локальные данные сохранены.");
  }


  async deleteCloudData() {
    window.clearTimeout(this.autoSyncTimer);
    const result = await this.auth.deleteCloudData();
    this.preferences.previouslyConnected = false;
    this.#savePreferences();
    this.#setPhase("local", "Облачная копия и доступ Google удалены. Локальные данные сохранены.");
    return result;
  }

  setAutoSync(enabled) {
    this.preferences.autoSync = Boolean(enabled);
    this.#savePreferences();
    this.#emit();
    if (this.preferences.autoSync && this.auth.isConnected) this.#scheduleAutomaticSync();
  }

  #scheduleAutomaticSync(delay = null) {
    window.clearTimeout(this.autoSyncTimer);
    const configuredDelay = Number(window.LAS_CONFIG?.AUTO_SYNC_DEBOUNCE_MS) || 1200;
    const timeout = delay ?? configuredDelay;
    this.autoSyncTimer = window.setTimeout(() => {
      this.syncNow({ reason: "automatic" }).catch(error => {
        if (error?.code !== "NETWORK_ERROR") console.warn("Automatic sync failed", error);
      });
    }, timeout);
  }

  async syncNow({ reason = "manual" } = {}) {
    if (this.syncPromise) return this.syncPromise;
    if (!this.auth.isConnected) {
      const error = new GoogleAuthError("Подключите Google для синхронизации.", "AUTH_REQUIRED");
      this.#handleError(error);
      throw error;
    }
    if (!navigator.onLine) {
      const error = new DriveApiError("Нет интернета", "NETWORK_ERROR");
      this.#handleError(error);
      throw error;
    }

    this.syncPromise = this.#performSync(reason).finally(() => {
      this.syncPromise = null;
    });
    return this.syncPromise;
  }

  async #performSync(reason) {
    this.#setPhase("syncing", reason === "automatic" ? "Сохраняем изменения…" : "Синхронизируем данные…");
    try {
      let result = null;
      for (let attempt = 0; attempt < 5; attempt += 1) {
        result = await this.engine.synchronize(window.LaStorage.getState());
        if (!result.stale) break;
      }

      if (result?.stale) {
        this.#scheduleAutomaticSync(150);
        this.#setPhase("connected", "Новые изменения сохранены локально и будут синхронизированы следом.");
        return result;
      }

      if (!sameState(result.state, window.LaStorage.getState())) {
        await window.LaStorage.replaceState(result.state);
      }
      if (this.engine.hasChangesSince(result.changeVersion)) this.#scheduleAutomaticSync(150);

      const message = result.recoveredCorruptBackup
        ? "Повреждённая копия сохранена отдельно, создана новая исправная копия."
        : "Данные защищены и синхронизированы через Google Drive";
      this.#setPhase("connected", message);
      return result;
    } catch (error) {
      this.#handleError(error);
      throw error;
    }
  }

  #handleError(error) {
    const code = error?.code || "UNKNOWN";
    if (["AUTH_REQUIRED", "GOOGLE_REAUTH_REQUIRED"].includes(code)) {
      this.auth.invalidate({ forgetAccount: false });
      this.#setPhase("authRequired", error.message || "Подключите Google повторно", error);
    } else if (code === "NETWORK_ERROR") {
      this.#setPhase("offline", "Нет связи. Изменения сохранены локально и будут отправлены позже.", error);
    } else {
      this.#setPhase("error", error?.message || "Ошибка синхронизации", error);
    }
  }

  snapshot() {
    return {
      phase: this.phase,
      message: this.message,
      lastError: this.lastError,
      autoSync: this.preferences.autoSync,
      previouslyConnected: this.preferences.previouslyConnected,
      auth: this.auth.snapshot(),
      sync: this.engine.snapshot(),
    };
  }
}

let singleton = null;
export function createCloudManager(defaults) {
  if (!singleton) singleton = new CloudManager(defaults);
  return singleton;
}
