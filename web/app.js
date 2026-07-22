import {
  loadState,
  saveState,
  exportBackup,
  parseBackupFile,
  chooseAutomaticBackupFile,
  disableAutomaticBackup,
  getAutomaticBackupStatus,
} from "./storage.js";
import { normalizeState } from "./migration.js";
import { initCloudUi } from "./cloud_ui.js";

export const defaults = Object.freeze({
  settings: {
    basePickPrice: 6.1,
    shiftHours: 10.75,
    hourlyRate: 147,
    taxPercent: 13,
  },
  schedule: { pattern: "", anchorDate: "" },
  shifts: {},
});

let state = normalizeState(await loadState(defaults), defaults);

async function persist() {
  await saveState(state);
  window.dispatchEvent(new CustomEvent("las-state-changed"));
}

window.LaStorage = Object.freeze({
  getState: () => state,

  async replaceState(nextState) {
    state = normalizeState(nextState, defaults);
    await persist();
  },

  async update(mutator) {
    const draft = state;
    const changed = mutator(draft);
    state = normalizeState(changed !== undefined ? changed : draft, defaults);
    await persist();
  },

  async exportJson() {
    await exportBackup(state);
  },

  async importJson(file) {
    const imported = await parseBackupFile(file);
    if (!confirm("Заменить текущие данные импортированными?")) return false;
    state = normalizeState(imported, defaults);
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

  getAutoBackupStatus: getAutomaticBackupStatus,
});

await import("./ui.js");
await initCloudUi(defaults);

if ("serviceWorker" in navigator && location.protocol !== "file:") {
  navigator.serviceWorker.register("./sw.js").catch(error => {
    console.warn("Service worker registration failed", error);
  });
}
