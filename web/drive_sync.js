import {
  CURRENT_CLOUD_SCHEMA_VERSION,
  migrateCloudPayload,
  normalizeState,
} from "./migration.js";

const META_STORAGE_KEY = "las_cloud_meta_v2";
const TOMBSTONE_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;

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

function recordTime(record) {
  return parseTime(record?.updatedAt);
}

function chooseRemoteOnTie(localRecord, remoteRecord) {
  const localCanonical = json({ deleted: Boolean(localRecord.deleted), value: localRecord.value });
  const remoteCanonical = json({ deleted: Boolean(remoteRecord.deleted), value: remoteRecord.value });
  return remoteCanonical > localCanonical;
}

export class DriveSyncEngine {
  constructor(driveApi, defaults) {
    this.driveApi = driveApi;
    this.defaults = defaults;
    this.meta = { ...emptyMeta(), ...(readMeta() || {}) };
    this.meta.shiftUpdatedAt = { ...(this.meta.shiftUpdatedAt || {}) };
    this.meta.shiftDeletedAt = { ...(this.meta.shiftDeletedAt || {}) };
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
      this.meta.settingsUpdatedAt =
        json(normalized.settings) === json(empty.settings) ? 0 : timestamp;
      this.meta.scheduleUpdatedAt =
        json(normalized.schedule) === json(empty.schedule) ? 0 : timestamp;
      for (const date of Object.keys(normalized.shifts)) {
        this.meta.shiftUpdatedAt[date] = timestamp;
      }
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

    if (json(normalized.settings) !== json(previous.settings)) {
      this.meta.settingsUpdatedAt = timestamp;
    }
    if (json(normalized.schedule) !== json(previous.schedule)) {
      this.meta.scheduleUpdatedAt = timestamp;
    }

    const dates = new Set([
      ...Object.keys(previous.shifts),
      ...Object.keys(normalized.shifts),
    ]);
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
    this.#pruneTombstones(timestamp);
    this.#saveMeta();
    return normalized;
  }

  #pruneTombstones(timestamp = Date.now()) {
    for (const [date, deletedAt] of Object.entries(this.meta.shiftDeletedAt)) {
      if (timestamp - Number(deletedAt) > TOMBSTONE_RETENTION_MS) {
        delete this.meta.shiftDeletedAt[date];
      }
    }
  }

  #localRecord(state, date) {
    const deletedAt = Number(this.meta.shiftDeletedAt[date]) || 0;
    const updatedAt = Number(this.meta.shiftUpdatedAt[date]) || 0;
    if (deletedAt >= updatedAt && deletedAt > 0) {
      return { value: null, updatedAt: nowIso(deletedAt), deleted: true };
    }
    if (Object.prototype.hasOwnProperty.call(state.shifts, date)) {
      return {
        value: clone(state.shifts[date]),
        updatedAt: nowIso(updatedAt || Date.now()),
        deleted: false,
      };
    }
    return null;
  }

  buildPayload(state, timestamp = Date.now()) {
    const normalized = this.markLocalChanges(state, timestamp);
    const shiftDates = new Set([
      ...Object.keys(normalized.shifts),
      ...Object.keys(this.meta.shiftDeletedAt),
    ]);
    const shifts = {};
    for (const date of shiftDates) {
      const record = this.#localRecord(normalized, date);
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
          updatedAt: nowIso(this.meta.settingsUpdatedAt || timestamp),
        },
        schedule: {
          value: clone(normalized.schedule),
          updatedAt: nowIso(this.meta.scheduleUpdatedAt || timestamp),
        },
        shifts,
      },
    };
  }

  merge(state, rawRemotePayload) {
    const local = this.markLocalChanges(state);
    const remote = migrateCloudPayload(rawRemotePayload, this.defaults);
    const merged = clone(local);

    const remoteSettingsTime = recordTime(remote.state.settings);
    const localSettingsTime = Number(this.meta.settingsUpdatedAt) || 0;
    if (remoteSettingsTime > localSettingsTime) {
      merged.settings = clone(remote.state.settings.value || {});
      this.meta.settingsUpdatedAt = remoteSettingsTime;
    }

    const remoteScheduleTime = recordTime(remote.state.schedule);
    const localScheduleTime = Number(this.meta.scheduleUpdatedAt) || 0;
    if (remoteScheduleTime > localScheduleTime) {
      merged.schedule = clone(remote.state.schedule.value || {});
      this.meta.scheduleUpdatedAt = remoteScheduleTime;
    }

    const dates = new Set([
      ...Object.keys(local.shifts),
      ...Object.keys(this.meta.shiftDeletedAt),
      ...Object.keys(remote.state.shifts || {}),
    ]);

    for (const date of dates) {
      const localRecord = this.#localRecord(local, date) || {
        value: null,
        updatedAt: new Date(0).toISOString(),
        deleted: true,
      };
      const remoteRecord = remote.state.shifts?.[date] || {
        value: null,
        updatedAt: new Date(0).toISOString(),
        deleted: true,
      };
      const localTime = recordTime(localRecord);
      const remoteTime = recordTime(remoteRecord);
      const remoteWins =
        remoteTime > localTime ||
        (remoteTime === localTime && chooseRemoteOnTie(localRecord, remoteRecord));
      const winner = remoteWins ? remoteRecord : localRecord;
      const winnerTime = Math.max(localTime, remoteTime);

      if (winner.deleted) {
        delete merged.shifts[date];
        delete this.meta.shiftUpdatedAt[date];
        if (winnerTime > 0) this.meta.shiftDeletedAt[date] = winnerTime;
      } else {
        merged.shifts[date] = clone(winner.value || {});
        this.meta.shiftUpdatedAt[date] = winnerTime || Date.now();
        delete this.meta.shiftDeletedAt[date];
      }
    }

    const normalizedMerged = normalizeState(merged, this.defaults);
    this.meta.lastSnapshot = clone(normalizedMerged);
    this.meta.remoteRevision = remote.revision || null;
    this.#saveMeta();
    return normalizedMerged;
  }

  async synchronize(state) {
    const normalized = this.initialize(state);
    const remoteDownload = await this.driveApi.downloadBackup();
    const merged = remoteDownload
      ? this.merge(normalized, remoteDownload.payload)
      : normalized;
    const payload = this.buildPayload(merged);
    const uploadedFile = await this.driveApi.uploadBackup(payload, remoteDownload?.file || null);

    this.meta.lastSyncAt = new Date().toISOString();
    this.meta.remoteModifiedTime = uploadedFile.modifiedTime || this.meta.lastSyncAt;
    this.meta.remoteRevision = payload.revision;
    this.meta.lastSnapshot = clone(merged);
    this.#saveMeta();

    return {
      state: merged,
      payload,
      file: uploadedFile,
      hadRemoteBackup: Boolean(remoteDownload),
      lastSyncAt: this.meta.lastSyncAt,
    };
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
