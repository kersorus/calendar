import { GoogleAuth, GoogleAuthError } from "./google_auth.js";
import { DriveApi, DriveApiError } from "./drive_api.js";
import { DriveSyncEngine } from "./drive_sync.js";

const PREFERENCES_KEY = "las_cloud_preferences_v2";

function readPreferences() {
  try {
    return {
      autoSync: true,
      previouslyConnected: false,
      ...JSON.parse(localStorage.getItem(PREFERENCES_KEY) || "{}"),
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
    this.applyingMergedState = false;
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

    window.addEventListener("las-state-changed", () => {
      if (this.applyingMergedState) return;
      this.engine.markLocalChanges(window.LaStorage.getState());
      if (this.preferences.autoSync && this.auth.isConnected) {
        this.#scheduleAutomaticSync();
      }
    });

    this.auth.addEventListener("change", () => {
      if (
        !this.auth.isConnected &&
        this.preferences.previouslyConnected &&
        this.phase === "connected"
      ) {
        this.#setPhase(
          "authRequired",
          "Сессия Google истекла. Подключите аккаунт повторно для следующей синхронизации.",
        );
      } else {
        this.#emit();
      }
    });

    if (this.preferences.previouslyConnected && this.auth.cachedAccount) {
      this.#setPhase(
        "authRequired",
        "Резервная копия подключена. Для синхронизации подтвердите Google-аккаунт.",
      );
    } else {
      this.#setPhase("local", "Данные хранятся только на этом устройстве");
    }

    return this.snapshot();
  }

  async connect() {
    this.#setPhase("connecting", "Открываем вход Google…");
    try {
      await this.auth.connect({ prompt: "select_account" });
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
    this.#setPhase(
      "local",
      "Google отключён. Локальные данные сохранены и продолжат работать.",
    );
  }

  setAutoSync(enabled) {
    this.preferences.autoSync = Boolean(enabled);
    this.#savePreferences();
    this.#emit();
    if (this.preferences.autoSync && this.auth.isConnected) {
      this.#scheduleAutomaticSync();
    }
  }

  #scheduleAutomaticSync() {
    window.clearTimeout(this.autoSyncTimer);
    const delay = Number(window.LAS_CONFIG?.AUTO_SYNC_DEBOUNCE_MS) || 1200;
    this.autoSyncTimer = window.setTimeout(() => {
      this.syncNow({ reason: "automatic" }).catch(error => {
        console.warn("Automatic Drive sync failed", error);
      });
    }, delay);
  }

  async syncNow({ reason = "manual" } = {}) {
    if (this.syncPromise) return this.syncPromise;
    if (!this.auth.isConnected) {
      const error = new GoogleAuthError(
        "Нажмите «Подключить Google», чтобы обновить доступ.",
        "AUTH_REQUIRED",
      );
      this.#handleError(error);
      throw error;
    }

    this.#setPhase(
      "syncing",
      reason === "connect" ? "Объединяем локальные данные с Google Drive…" : "Синхронизация…",
    );

    this.syncPromise = this.engine
      .synchronize(window.LaStorage.getState())
      .then(async result => {
        const currentState = window.LaStorage.getState();
        if (!sameState(currentState, result.state)) {
          this.applyingMergedState = true;
          try {
            await window.LaStorage.replaceState(result.state);
          } finally {
            this.applyingMergedState = false;
          }
        }

        const account = this.auth.cachedAccount;
        this.#setPhase(
          "connected",
          account?.email
            ? `Синхронизировано с ${account.email}`
            : "Синхронизировано с Google Drive",
        );
        return result;
      })
      .catch(error => {
        this.#handleError(error);
        throw error;
      })
      .finally(() => {
        this.syncPromise = null;
      });

    return this.syncPromise;
  }

  #handleError(error) {
    const authRequired =
      error?.code === "AUTH_REQUIRED" ||
      (error instanceof DriveApiError && error.status === 401);
    if (authRequired) {
      if (this.auth.isConnected) this.auth.invalidate();
      this.#setPhase(
        "authRequired",
        "Доступ Google истёк. Подключите аккаунт повторно — локальные данные не потеряны.",
        error,
      );
    } else {
      this.#setPhase(
        "error",
        error?.message || "Не удалось синхронизировать данные",
        error,
      );
    }
  }

  snapshot() {
    return {
      initialized: this.initialized,
      phase: this.phase,
      message: this.message,
      error: this.lastError
        ? { message: this.lastError.message, code: this.lastError.code || "ERROR" }
        : null,
      autoSync: this.preferences.autoSync,
      previouslyConnected: this.preferences.previouslyConnected,
      auth: this.auth.snapshot(),
      sync: this.engine.snapshot(),
    };
  }
}

export function createCloudManager(defaults) {
  return new CloudManager(defaults);
}
