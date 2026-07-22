import { createCloudManager } from "./cloud_manager.js";

const ONBOARDING_KEY = "las_cloud_onboarding_v2";

function $(id) {
  return document.getElementById(id);
}

function formatDateTime(value) {
  if (!value) return "ещё не было";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "неизвестно";
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function setButtonBusy(button, busy) {
  if (!button) return;
  button.disabled = busy;
  button.setAttribute("aria-busy", String(busy));
}

function showToast(message, kind = "info") {
  const toast = $("cloudToast");
  if (!toast) return;
  toast.textContent = message;
  toast.dataset.kind = kind;
  toast.hidden = false;
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    toast.hidden = true;
  }, 3800);
}

function render(snapshot) {
  const badge = $("cloudStatusBadge");
  const status = $("cloudStatusText");
  const account = $("cloudAccount");
  const connect = $("connectGoogleButton");
  const disconnect = $("disconnectGoogleButton");
  const revoke = $("revokeGoogleButton");
  const deleteCloudData = $("deleteCloudDataButton");
  const sync = $("syncNowButton");
  const auto = $("autoSyncInput");
  const lastSync = $("cloudLastSync");
  const busy = snapshot.phase === "connecting" || snapshot.phase === "syncing";
  const connected = snapshot.auth.connected;

  if (badge) {
    const labels = {
      local: "Локально",
      connecting: "Вход…",
      syncing: "Синхронизация…",
      connected: "Защищено",
      offline: "Офлайн",
      authRequired: "Нужен вход",
      error: "Ошибка",
    };
    badge.textContent = labels[snapshot.phase] || "Локально";
    badge.dataset.state = snapshot.phase;
  }
  if (status) status.textContent = snapshot.message;

  if (account) {
    const cached = snapshot.auth.account;
    account.hidden = !cached;
    account.textContent = cached
      ? `${cached.name || "Google"}${cached.email ? ` · ${cached.email}` : ""}`
      : "";
  }

  if (connect) {
    connect.hidden = connected;
    connect.textContent = snapshot.previouslyConnected || snapshot.auth.account
      ? "Подключить Google снова"
      : "Подключить Google";
    setButtonBusy(connect, busy || !snapshot.auth.ready);
  }
  if (disconnect) {
    disconnect.hidden = !connected;
    setButtonBusy(disconnect, busy);
  }
  if (revoke) {
    revoke.hidden = !connected;
    setButtonBusy(revoke, busy);
  }
  if (deleteCloudData) {
    deleteCloudData.hidden = !connected;
    setButtonBusy(deleteCloudData, busy);
  }
  if (sync) {
    sync.disabled = !connected || busy || !navigator.onLine;
    sync.setAttribute("aria-busy", String(snapshot.phase === "syncing"));
  }
  if (auto) auto.checked = snapshot.autoSync;
  if (lastSync) lastSync.textContent = `Последняя синхронизация: ${formatDateTime(snapshot.sync.lastSyncAt)}`;
}

export async function initCloudUi(defaults) {
  const manager = createCloudManager(defaults);
  window.LASCloudManager = manager;
  manager.addEventListener("change", event => render(event.detail));
  await manager.init();
  render(manager.snapshot());

  $("connectGoogleButton")?.addEventListener("click", async () => {
    try {
      await manager.connect();
      showToast("Аккаунт подключён, данные синхронизированы", "success");
    } catch (error) {
      if (error?.code !== "POPUP_CLOSED") showToast(error?.message || "Не удалось подключить Google", "error");
    }
  });

  $("syncNowButton")?.addEventListener("click", async () => {
    try {
      await manager.syncNow();
      showToast("Синхронизация завершена", "success");
    } catch (error) {
      showToast(error?.message || "Ошибка синхронизации", "error");
    }
  });

  $("disconnectGoogleButton")?.addEventListener("click", async () => {
    if (!confirm("Отключить Google только на этом устройстве? Локальные данные останутся.")) return;
    await manager.disconnect();
    showToast("Google отключён на этом устройстве");
  });

  $("revokeGoogleButton")?.addEventListener("click", async () => {
    if (!confirm("Отозвать доступ Google на всех устройствах? Облачная копия в Drive останется.")) return;
    try {
      await manager.revokeAccess();
      showToast("Доступ Google отозван", "success");
    } catch (error) {
      showToast(error?.message || "Не удалось отозвать доступ", "error");
    }
  });


  $("deleteCloudDataButton")?.addEventListener("click", async () => {
    if (!confirm("Удалить облачную копию, серверные ключи и все сессии? Локальные данные на этом устройстве останутся.")) return;
    try {
      const result = await manager.deleteCloudData();
      showToast(`Облачные данные удалены${result.deletedFiles ? `: файлов ${result.deletedFiles}` : ""}`, "success");
    } catch (error) {
      showToast(error?.message || "Не удалось удалить облачные данные", "error");
    }
  });

  $("autoSyncInput")?.addEventListener("change", event => manager.setAutoSync(event.target.checked));

  const onboarding = $("cloudSetupDialog");
  const onboardingDone = localStorage.getItem(ONBOARDING_KEY) === "1";
  if (!onboardingDone && onboarding?.showModal) window.setTimeout(() => onboarding.showModal(), 250);

  $("cloudSetupLaterButton")?.addEventListener("click", () => {
    localStorage.setItem(ONBOARDING_KEY, "1");
    onboarding?.close();
  });

  $("cloudSetupConnectButton")?.addEventListener("click", async () => {
    try {
      await manager.connect();
      localStorage.setItem(ONBOARDING_KEY, "1");
      onboarding?.close();
      showToast("Готово: данные защищены Google Drive", "success");
    } catch (error) {
      showToast(error?.message || "Не удалось подключить Google", "error");
    }
  });

  return manager;
}
