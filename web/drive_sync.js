import {
  CURRENT_CLOUD_SCHEMA_VERSION,
  migrateCloudPayload,
  normalizeState,
} from "./migration.js";

const META_STORAGE_KEY = "las_cloud_meta_v2";

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function json(value) {
  return JSON.stringify(value);
}

function nowIso(timestamp = Date.now()) {
  return new Date(timestamp).toISOString();
}

function parseTime(value) {
  const time = typeof value === "number" ? value : Date.parse(value || "");
  return Number.isFinite(time) ? time : 0;
}

function uuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `las-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function readMeta() {
  try {
    const raw = localStorage.getItem(META_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch (_) {
    return null;
  }
}

function emptyMeta() {
  return {
    deviceId: uuid(),
    settingsUpdatedAt: 0,
    scheduleUpdatedAt: 0,
    shiftUpdatedAt: {},
    shiftDeletedAt: {},
    lastSnapshot: null,
    lastSyncAt: null,
    remoteModifiedTime: null,
    remoteRevision: null,
  };
}

export class DriveSyncEngine {
  constructor(driveApi, defaults) {
    this.driveApi = driveApi;
    this.defaults = defaults;
    this.meta = { ...emptyMeta(), ...(readMeta() || {}) };
    this.meta.shiftUpdatedAt = { ...(this.meta.shiftUpdatedAt || {}) };
    this.meta.shiftDeletedAt = { ...(this.meta.shiftDeletedAt || {}) };
    this.changeVersion = 0;
  }

  #saveMeta() {
    try {
      localStorage.setItem(META_STORAGE_KEY, JSON.stringify(this.meta));
    } catch (error) {
      console.warn("Cloud metadata could not be persisted", error);
    }
  }

  initialize(state) {
    const normalized = normalizeState(state, this.defaults);
    if (!this.meta.lastSnapshot) {
      const timestamp = Date.now();
      const empty = normalizeState({}, this.defaults);
      this.meta.settingsUpdatedAt = json(normalized.settings) === json(empty.settings) ? 0 : timestamp;
      this.meta.scheduleUpdatedAt = json(normalized.schedule) === json(empty.schedule) ? 0 : timestamp;
      for (const date of Object.keys(normalized.shifts)) this.meta.shiftUpdatedAt[date] = timestamp;
      this.meta.lastSnapshot = clone(normalized);
      this.#saveMeta();
    } else {
      this.markLocalChanges(normalized);
    }
    return normalized;
  }

  markLocalChanges(state, timestamp = Date.now()) {
    const normalized = normalizeState(state, this.defaults);
    const previous = this.meta.lastSnapshot
      ? normalizeState(this.meta.lastSnapshot, this.defaults)
      : normalizeState({}, this.defaults);

    if (json(normalized.settings) !== json(previous.settings)) this.meta.settingsUpdatedAt = timestamp;
    if (json(normalized.schedule) !== json(previous.schedule)) this.meta.scheduleUpdatedAt = timestamp;

    const dates = new Set([...Object.keys(previous.shifts), ...Object.keys(normalized.shifts)]);
    for (const date of dates) {
      const hadValue = Object.prototype.hasOwnProperty.call(previous.shifts, date);
      const hasValue = Object.prototype.hasOwnProperty.call(normalized.shifts, date);
      if (hasValue && (!hadValue || json(previous.shifts[date]) !== json(normalized.shifts[date]))) {
        this.meta.shiftUpdatedAt[date] = timestamp;
        delete this.meta.shiftDeletedAt[date];
      } else if (!hasValue && hadValue) {
        delete this.meta.shiftUpdatedAt[date];
        this.meta.shiftDeletedAt[date] = timestamp;
      }
    }

    this.meta.lastSnapshot = clone(normalized);
    this.changeVersion += 1;
    this.#saveMeta();
    return normalized;
  }

  #localShiftRecord(state, date) {
    const deletedAt = Number(this.meta.shiftDeletedAt[date]) || 0;
    const updatedAt = Number(this.meta.shiftUpdatedAt[date]) || 0;
    if (deletedAt >= updatedAt && deletedAt > 0) {
      return { value: null, updatedAt: nowIso(deletedAt), deleted: true };
    }
    if (Object.prototype.hasOwnProperty.call(state.shifts, date)) {
      return {
        value: clone(state.shifts[date]),
        updatedAt: nowIso(updatedAt),
        deleted: false,
      };
    }
    return null;
  }

  buildPayload(state, timestamp = Date.now()) {
    const normalized = this.markLocalChanges(state, timestamp);
    const dates = new Set([
      ...Object.keys(normalized.shifts),
      ...Object.keys(this.meta.shiftDeletedAt),
    ]);
    const shifts = {};
    for (const date of dates) {
      const record = this.#localShiftRecord(normalized, date);
      if (record) shifts[date] = record;
    }

    return {
      format: "LaSalaryCloudBackup",
      version: CURRENT_CLOUD_SCHEMA_VERSION,
      app: "las-salary",
      revision: uuid(),
      deviceId: this.meta.deviceId,
      updatedAt: nowIso(timestamp),
      state: {
        settings: {
          value: clone(normalized.settings),
          updatedAt: nowIso(Number(this.meta.settingsUpdatedAt) || 0),
        },
        schedule: {
          value: clone(normalized.schedule),
          updatedAt: nowIso(Number(this.meta.scheduleUpdatedAt) || 0),
        },
        shifts,
      },
    };
  }

  applyPayload(rawPayload) {
    const payload = migrateCloudPayload(rawPayload, this.defaults);
    const state = {
      settings: clone(payload.state.settings?.value || {}),
      schedule: clone(payload.state.schedule?.value || {}),
      shifts: {},
    };

    this.meta.settingsUpdatedAt = parseTime(payload.state.settings?.updatedAt);
    this.meta.scheduleUpdatedAt = parseTime(payload.state.schedule?.updatedAt);
    this.meta.shiftUpdatedAt = {};
    this.meta.shiftDeletedAt = {};

    for (const [date, record] of Object.entries(payload.state.shifts || {})) {
      const timestamp = parseTime(record.updatedAt);
      if (record.deleted) {
        this.meta.shiftDeletedAt[date] = timestamp;
      } else {
        state.shifts[date] = clone(record.value || {});
        this.meta.shiftUpdatedAt[date] = timestamp;
      }
    }

    const normalized = normalizeState(state, this.defaults);
    this.meta.lastSnapshot = clone(normalized);
    this.meta.remoteRevision = payload.revision || null;
    this.#saveMeta();
    return normalized;
  }

  async synchronize(state) {
    const normalized = this.initialize(state);
    const localPayload = this.buildPayload(normalized);
    const changeVersionAtStart = this.changeVersion;
    const remote = await this.driveApi.synchronize(localPayload);

    // A response produced from an older local snapshot must never overwrite
    // edits made while the network request was in flight.
    if (this.changeVersion !== changeVersionAtStart) {
      return {
        stale: true,
        payload: remote.payload,
        file: remote.file,
        hadRemoteBackup: Boolean(remote.hadRemoteBackup),
        recoveredCorruptBackup: Boolean(remote.recoveredCorruptBackup),
      };
    }

    const merged = this.applyPayload(remote.payload);
    this.meta.lastSyncAt = remote.serverTime || new Date().toISOString();
    this.meta.remoteModifiedTime = remote.file?.modifiedTime || this.meta.lastSyncAt;
    this.meta.remoteRevision = remote.payload?.revision || null;
    this.meta.lastSnapshot = clone(merged);
    this.#saveMeta();

    return {
      stale: false,
      changeVersion: changeVersionAtStart,
      state: merged,
      payload: remote.payload,
      file: remote.file,
      hadRemoteBackup: Boolean(remote.hadRemoteBackup),
      recoveredCorruptBackup: Boolean(remote.recoveredCorruptBackup),
      lastSyncAt: this.meta.lastSyncAt,
    };
  }

  hasChangesSince(version) {
    return this.changeVersion !== version;
  }

  snapshot() {
    return {
      deviceId: this.meta.deviceId,
      lastSyncAt: this.meta.lastSyncAt,
      remoteModifiedTime: this.meta.remoteModifiedTime,
      remoteRevision: this.meta.remoteRevision,
    };
  }
}
