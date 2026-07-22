export const CURRENT_CLOUD_SCHEMA_VERSION = 3;

const FALLBACK_DEFAULTS = Object.freeze({
  settings: {
    basePickPrice: 6.1,
    shiftHours: 10.75,
    hourlyRate: 147,
    taxPercent: 13,
  },
  schedule: { pattern: "", anchorDate: "" },
  shifts: {},
});

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function finiteOr(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

export function normalizeState(input, defaults = FALLBACK_DEFAULTS) {
  const source = isPlainObject(input) ? input : {};
  const base = isPlainObject(defaults) ? defaults : FALLBACK_DEFAULTS;
  const sourceSettings = isPlainObject(source.settings) ? source.settings : {};
  const sourceSchedule = isPlainObject(source.schedule) ? source.schedule : {};
  const sourceShifts = isPlainObject(source.shifts) ? source.shifts : {};

  const settingsDefaults = { ...FALLBACK_DEFAULTS.settings, ...(base.settings || {}) };
  const shifts = {};

  for (const [date, shift] of Object.entries(sourceShifts)) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(date) && isPlainObject(shift)) {
      shifts[date] = clone(shift);
    }
  }

  return {
    settings: {
      ...settingsDefaults,
      ...sourceSettings,
      basePickPrice: finiteOr(sourceSettings.basePickPrice, settingsDefaults.basePickPrice),
      shiftHours: finiteOr(sourceSettings.shiftHours, settingsDefaults.shiftHours),
      hourlyRate: finiteOr(sourceSettings.hourlyRate, settingsDefaults.hourlyRate),
      taxPercent: finiteOr(sourceSettings.taxPercent, settingsDefaults.taxPercent),
    },
    schedule: {
      ...(base.schedule || FALLBACK_DEFAULTS.schedule),
      ...sourceSchedule,
      pattern: typeof sourceSchedule.pattern === "string" ? sourceSchedule.pattern : "",
      anchorDate:
        typeof sourceSchedule.anchorDate === "string" ? sourceSchedule.anchorDate : "",
    },
    shifts,
  };
}

function legacyStateFromPayload(payload) {
  if (!isPlainObject(payload)) return null;

  if (payload.format === "LaSalaryBackup" && isPlainObject(payload.data)) {
    return payload.data;
  }

  if (isPlainObject(payload.settings) || isPlainObject(payload.shifts)) {
    return payload;
  }

  if (isPlainObject(payload.data) && (payload.data.settings || payload.data.shifts)) {
    return payload.data;
  }

  return null;
}

/**
 * Converts any supported Drive payload to the v3 record model.
 * Older local JSON backups remain importable and become one timestamped snapshot.
 */
export function migrateCloudPayload(payload, defaults = FALLBACK_DEFAULTS) {
  if (
    isPlainObject(payload) &&
    payload.format === "LaSalaryCloudBackup" &&
    Number(payload.version) === CURRENT_CLOUD_SCHEMA_VERSION &&
    isPlainObject(payload.state)
  ) {
    return payload;
  }

  const legacyState = legacyStateFromPayload(payload);
  if (!legacyState) {
    throw new Error("Файл Google Drive имеет неизвестный формат");
  }

  const normalized = normalizeState(legacyState, defaults);
  const timestamp =
    typeof payload.updatedAt === "string" && !Number.isNaN(Date.parse(payload.updatedAt))
      ? payload.updatedAt
      : new Date().toISOString();

  const shifts = {};
  for (const [date, value] of Object.entries(normalized.shifts)) {
    shifts[date] = { value, updatedAt: timestamp, deleted: false };
  }

  return {
    format: "LaSalaryCloudBackup",
    version: CURRENT_CLOUD_SCHEMA_VERSION,
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
