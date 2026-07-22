import {
  loadState,
  saveState,
  exportBackup,
  parseBackupFile,
  chooseAutomaticBackupFile,
  disableAutomaticBackup,
  getAutomaticBackupStatus
} from "./storage.js";

const defaults = {
  settings: {
    basePickPrice: 6.1,
    shiftHours: 10.75,
    hourlyRate: 147,
    taxPercent: 13
  },
  schedule: {pattern: "", anchorDate: ""},
  shifts: {}
};

let state = await loadState(defaults);

const $ = id => document.getElementById(id);

async function persist() {
  await saveState(state);
  window.dispatchEvent(new CustomEvent("las-state-changed"));
}

function mergeImportedState(data) {
  return {
    settings: {...defaults.settings, ...(data?.settings || {})},
    schedule: {...defaults.schedule, ...(data?.schedule || {})},
    shifts: data?.shifts && typeof data.shifts === "object" ? data.shifts : {}
  };
}

// Совместимый публичный API для существующего интерфейса.
window.LaStorage = {
  getState: () => state,

  async replaceState(nextState) {
    state = mergeImportedState(nextState);
    await persist();
  },

  async update(mutator) {
    const changed = mutator(state);
    if (changed !== undefined) state = changed;
    await persist();
  },

  async exportJson() {
    await exportBackup(state);
  },

  async importJson(file) {
    const imported = await parseBackupFile(file);
    if (!confirm("Заменить текущие данные импортированными?")) return false;
    state = mergeImportedState(imported);
    await persist();
    return true;
  },

  async enableAutoBackup() {
    await chooseAutomaticBackupFile(state);
    return getAutomaticBackupStatus();
  },

  async disableAutoBackup() {
    await disableAutomaticBackup();
    return getAutomaticBackupStatus();
  },

  getAutoBackupStatus: getAutomaticBackupStatus
};

// Подключаем существующий UI-файл после инициализации IndexedDB.
await import("./ui.js");
