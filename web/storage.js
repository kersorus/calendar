try { window.LASDebugLog && window.LASDebugLog("storage.js loaded"); } catch(e) {}
const DB_NAME = "las_salary";
const DB_VERSION = 1;
const STORE = "state";
const STATE_KEY = "main";
const HANDLE_KEY = "backupFileHandle";

let dbPromise = null;

function openDatabase() {
  if (dbPromise) return dbPromise;

  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE);
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });

  return dbPromise;
}

async function transaction(mode, callback) {
  const db = await openDatabase();

  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, mode);
    const store = tx.objectStore(STORE);

    let result;
    try {
      result = callback(store);
    } catch (error) {
      reject(error);
      return;
    }

    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function loadState(defaultState) {
  const db = await openDatabase();
  const tx = db.transaction(STORE, "readonly");
  const store = tx.objectStore(STORE);
  const stored = await requestResult(store.get(STATE_KEY));

  if (stored) return stored;

  // Однократный импорт из старой localStorage-версии.
  try {
    const legacy = localStorage.getItem("las_salary_pwa_v1");
    if (legacy) {
      const parsed = JSON.parse(legacy);
      await saveState(parsed, {autoBackup: false});
      localStorage.removeItem("las_salary_pwa_v1");
      return parsed;
    }
  } catch (error) {
    console.warn("Legacy migration failed:", error);
  }

  await saveState(defaultState, {autoBackup: false});
  return defaultState;
}

export async function saveState(state, options = {}) {
  const db = await openDatabase();

  await new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(state, STATE_KEY);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });

  if (options.autoBackup !== false) {
    await tryAutomaticBackup(state);
  }
}

export function makeBackup(state) {
  return {
    format: "LaSalaryBackup",
    version: 2,
    exportedAt: new Date().toISOString(),
    data: state
  };
}

export async function exportBackup(state) {
  const blob = new Blob(
    [JSON.stringify(makeBackup(state), null, 2)],
    {type: "application/json"}
  );

  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `las-backup-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

export async function parseBackupFile(file) {
  const parsed = JSON.parse(await file.text());

  if (parsed?.format !== "LaSalaryBackup" || !parsed.data) {
    throw new Error("Выбранный файл не является резервной копией La$.");
  }

  return parsed.data;
}

export function supportsAutomaticFileBackup() {
  return typeof window.showSaveFilePicker === "function";
}

export async function chooseAutomaticBackupFile(state) {
  if (!supportsAutomaticFileBackup()) {
    throw new Error(
      "Этот браузер не поддерживает автоматическую запись в файл. " +
      "Ручной экспорт JSON продолжает работать."
    );
  }

  const handle = await window.showSaveFilePicker({
    suggestedName: "las-auto-backup.json",
    types: [{
      description: "Резервная копия La$",
      accept: {"application/json": [".json"]}
    }]
  });

  await storeFileHandle(handle);
  await writeBackupToHandle(handle, state);
  return handle;
}

export async function disableAutomaticBackup() {
  const db = await openDatabase();

  await new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).delete(HANDLE_KEY);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function getAutomaticBackupStatus() {
  if (!supportsAutomaticFileBackup()) {
    return {supported: false, configured: false};
  }

  const handle = await readFileHandle();
  return {
    supported: true,
    configured: Boolean(handle),
    fileName: handle?.name || ""
  };
}

async function tryAutomaticBackup(state) {
  if (!supportsAutomaticFileBackup()) return;

  const handle = await readFileHandle();
  if (!handle) return;

  try {
    const permission = await handle.queryPermission({mode: "readwrite"});

    // Запрашивать разрешение автоматически нельзя без жеста пользователя.
    if (permission !== "granted") return;

    await writeBackupToHandle(handle, state);
  } catch (error) {
    console.warn("Automatic backup failed:", error);
  }
}

async function writeBackupToHandle(handle, state) {
  const writable = await handle.createWritable();
  await writable.write(JSON.stringify(makeBackup(state), null, 2));
  await writable.close();
}

async function storeFileHandle(handle) {
  const db = await openDatabase();

  await new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(handle, HANDLE_KEY);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

async function readFileHandle() {
  const db = await openDatabase();
  const tx = db.transaction(STORE, "readonly");
  return requestResult(tx.objectStore(STORE).get(HANDLE_KEY));
}


