import { AppError } from "./errors.js";

export const CLOUD_SCHEMA_VERSION = 3;

const DEFAULTS = Object.freeze({
  settings: {
    basePickPrice: 6.1,
    shiftHours: 10.75,
    hourlyRate: 147,
    taxPercent: 13,
  },
  schedule: { pattern: "", anchorDate: "" },
  shifts: {},
});

function isObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function clone(value) {
  return structuredClone(value);
}

function parseTime(value) {
  const timestamp = Date.parse(value || "");
  return Number.isFinite(timestamp) ? timestamp : 0;
}

function iso(timestamp) {
  return new Date(timestamp).toISOString();
}

function canonical(record) {
  return JSON.stringify({ deleted: Boolean(record?.deleted), value: record?.value ?? null });
}

function normalizeState(input) {
  const source = isObject(input) ? input : {};
  const settings = isObject(source.settings) ? source.settings : {};
  const schedule = isObject(source.schedule) ? source.schedule : {};
  const shifts = {};

  for (const [date, value] of Object.entries(isObject(source.shifts) ? source.shifts : {})) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(date) && isObject(value)) shifts[date] = clone(value);
  }

  return {
    settings: { ...DEFAULTS.settings, ...settings },
    schedule: {
      ...DEFAULTS.schedule,
      ...schedule,
      pattern: typeof schedule.pattern === "string" ? schedule.pattern : "",
      anchorDate: typeof schedule.anchorDate === "string" ? schedule.anchorDate : "",
    },
    shifts,
  };
}

function legacyState(payload) {
  if (!isObject(payload)) return null;
  if (payload.format === "LaSalaryBackup" && isObject(payload.data)) return payload.data;
  if (isObject(payload.settings) || isObject(payload.shifts)) return payload;
  if (isObject(payload.data) && (payload.data.settings || payload.data.shifts)) return payload.data;
  return null;
}

function normalizeCurrentPayload(payload) {
  const state = isObject(payload.state) ? payload.state : {};
  const validSettings = isObject(state.settings) && !state.settings.deleted && isObject(state.settings.value);
  const validSchedule = isObject(state.schedule) && !state.schedule.deleted && isObject(state.schedule.value);
  const normalized = normalizeState({
    settings: validSettings ? state.settings.value : {},
    schedule: validSchedule ? state.schedule.value : {},
    shifts: Object.fromEntries(
      Object.entries(isObject(state.shifts) ? state.shifts : {})
        .filter(([date, record]) =>
          /^\d{4}-\d{2}-\d{2}$/.test(date) &&
          isObject(record) &&
          !record.deleted &&
          isObject(record.value),
        )
        .map(([date, record]) => [date, record.value]),
    ),
  });
  const shifts = {};

  for (const [date, record] of Object.entries(isObject(state.shifts) ? state.shifts : {})) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !isObject(record)) continue;
    if (record.deleted) {
      shifts[date] = { value: null, updatedAt: iso(parseTime(record.updatedAt)), deleted: true };
    } else if (isObject(record.value)) {
      shifts[date] = {
        value: normalized.shifts[date],
        updatedAt: iso(parseTime(record.updatedAt)),
        deleted: false,
      };
    }
  }

  return {
    format: "LaSalaryCloudBackup",
    version: CLOUD_SCHEMA_VERSION,
    app: "las-salary",
    revision: typeof payload.revision === "string" ? payload.revision : "",
    deviceId: typeof payload.deviceId === "string" ? payload.deviceId : "",
    updatedAt: iso(parseTime(payload.updatedAt)),
    state: {
      settings: {
        value: normalized.settings,
        updatedAt: iso(validSettings ? parseTime(state.settings.updatedAt) : 0),
      },
      schedule: {
        value: normalized.schedule,
        updatedAt: iso(validSchedule ? parseTime(state.schedule.updatedAt) : 0),
      },
      shifts,
    },
  };
}

export function migrateCloudPayload(payload) {
  if (
    isObject(payload) &&
    payload.format === "LaSalaryCloudBackup" &&
    Number(payload.version) === CLOUD_SCHEMA_VERSION &&
    isObject(payload.state)
  ) {
    return normalizeCurrentPayload(payload);
  }

  const state = legacyState(payload);
  if (!state) {
    throw new AppError("Облачная копия имеет неизвестный формат", {
      code: "INVALID_BACKUP_FORMAT",
      status: 409,
    });
  }

  const normalized = normalizeState(state);
  const timestamp =
    typeof payload.updatedAt === "string" && parseTime(payload.updatedAt) > 0
      ? payload.updatedAt
      : new Date().toISOString();
  const shifts = {};

  for (const [date, value] of Object.entries(normalized.shifts)) {
    shifts[date] = { value, updatedAt: timestamp, deleted: false };
  }

  return {
    format: "LaSalaryCloudBackup",
    version: CLOUD_SCHEMA_VERSION,
    app: "las-salary",
    revision: typeof payload.revision === "string" ? payload.revision : "legacy-import",
    deviceId: typeof payload.deviceId === "string" ? payload.deviceId : "legacy-device",
    updatedAt: timestamp,
    state: {
      settings: { value: normalized.settings, updatedAt: timestamp },
      schedule: { value: normalized.schedule, updatedAt: timestamp },
      shifts,
    },
  };
}

function chooseRecord(left, right) {
  const leftTime = parseTime(left?.updatedAt);
  const rightTime = parseTime(right?.updatedAt);
  if (leftTime !== rightTime) return leftTime > rightTime ? left : right;
  return canonical(left) >= canonical(right) ? left : right;
}

function chooseValueRecord(left, right) {
  if (left?.deleted && !right?.deleted) return right;
  if (right?.deleted && !left?.deleted) return left;
  return chooseRecord(left, right);
}

function sanitizeRecord(record, fallbackValue = null) {
  return {
    value: record?.deleted ? null : clone(record?.value ?? fallbackValue),
    updatedAt: iso(parseTime(record?.updatedAt)),
    deleted: Boolean(record?.deleted),
  };
}

function sanitizeValueRecord(record, fallbackValue) {
  return {
    value: clone(record?.value ?? fallbackValue),
    updatedAt: iso(parseTime(record?.updatedAt)),
    deleted: false,
  };
}

export function mergeCloudPayloads(localPayload, remotePayload, now = Date.now()) {
  const local = migrateCloudPayload(localPayload);
  const remote = remotePayload ? migrateCloudPayload(remotePayload) : null;

  if (!remote) {
    return {
      ...local,
      revision: crypto.randomUUID(),
      deviceId: "server",
      updatedAt: iso(now),
    };
  }

  const settings = chooseValueRecord(local.state.settings, remote.state.settings);
  const schedule = chooseValueRecord(local.state.schedule, remote.state.schedule);
  const shifts = {};
  const dates = new Set([
    ...Object.keys(local.state.shifts || {}),
    ...Object.keys(remote.state.shifts || {}),
  ]);

  for (const date of dates) {
    const winner = chooseRecord(local.state.shifts?.[date], remote.state.shifts?.[date]);
    if (winner) shifts[date] = sanitizeRecord(winner);
  }

  return {
    format: "LaSalaryCloudBackup",
    version: CLOUD_SCHEMA_VERSION,
    app: "las-salary",
    revision: crypto.randomUUID(),
    deviceId: "server",
    updatedAt: iso(now),
    state: {
      settings: sanitizeValueRecord(settings, DEFAULTS.settings),
      schedule: sanitizeValueRecord(schedule, DEFAULTS.schedule),
      shifts,
    },
  };
}
