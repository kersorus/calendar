try { window.LASDebugLog && window.LASDebugLog("ui.js loaded"); } catch(e) {}
const storage = window.LaStorage;

let state = storage.getState();
let visibleMonth = new Date(
  new Date().getFullYear(),
  new Date().getMonth(),
  1
);
let editMode = "normal";
let pendingPattern = "";
let activeDateKey = "";
let dragStartX = null;

const $ = (id) => document.getElementById(id);
const calendar = $("calendar");

function syncState() {
  state = storage.getState();
}

window.addEventListener("las-state-changed", () => {
  syncState();
  render();
});

function dateKey(date) {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0")
  ].join("-");
}

function dateFromKey(value) {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function daysBetween(from, to) {
  return Math.round((startOfDay(to) - startOfDay(from)) / 86400000);
}

function positiveModulo(value, divisor) {
  return ((value % divisor) + divisor) % divisor;
}

function shiftFor(date) {
  return state.shifts[dateKey(date)] || null;
}

function totalPicks(shift = {}) {
  return [
    "cancel", "accept", "returns", "issue",
    "reject", "payment", "repack"
  ].reduce((sum, field) => sum + Number(shift[field] || 0), 0);
}

function netForShift(shift = {}) {
  const weightedPicks =
    Number(shift.cancel || 0) * 0.6 +
    Number(shift.accept || 0) * 0.8 +
    Number(shift.returns || 0) * 0.9 +
    Number(shift.issue || 0) * 1.1 +
    Number(shift.reject || 0) * 1.1 +
    Number(shift.payment || 0) * 1.1 +
    Number(shift.repack || 0) * 1.3;

  const picksGross = state.settings.basePickPrice * weightedPicks;
  const shiftGross = totalPicks(shift) > 0
    ? state.settings.shiftHours * state.settings.hourlyRate
    : 0;

  return (picksGross + shiftGross) *
    (1 - state.settings.taxPercent / 100);
}

function isScheduledWorkday(date) {
  const { pattern, anchorDate } = state.schedule;
  if (!pattern || !anchorDate) return false;

  const delta = daysBetween(dateFromKey(anchorDate), date);

  if (pattern === "5/2") {
    return positiveModulo(delta, 7) < 5;
  }

  if (pattern === "2/2") {
    return positiveModulo(delta, 4) < 2;
  }

  if (pattern === "3/3") {
    return positiveModulo(delta, 6) < 3;
  }

  return false;
}

function isWorkday(date) {
  const shift = shiftFor(date);

  if (shift?.override === 1 || totalPicks(shift) > 0) return true;
  if (shift?.override === 0) return false;

  return isScheduledWorkday(date);
}

function periodColor(date) {
  const firstHalf = date.getDate() <= 15;
  const evenMonth = (date.getMonth() + 1) % 2 === 0;

  if (evenMonth) {
    return firstHalf ? "#4270a8" : "#cd6937";
  }
  return firstHalf ? "#4c8c54" : "#7f5696";
}

function render() {
  $("monthTitle").textContent = visibleMonth.toLocaleDateString("ru-RU", {
    month: "long",
    year: "numeric"
  });

  $("modeHint").textContent =
    editMode === "add"
      ? "Нажмите дату, чтобы добавить смену"
      : editMode === "remove"
        ? "Нажмите дату, чтобы убрать смену"
        : state.schedule.pattern
          ? `График ${state.schedule.pattern}`
          : "График не выбран";

  renderCalendar();
  renderPayments();
}

function renderCalendar() {
  calendar.innerHTML = "";

  ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"].forEach((weekday) => {
    const element = document.createElement("div");
    element.className = "weekday";
    element.textContent = weekday;
    calendar.append(element);
  });

  const firstDay = new Date(
    visibleMonth.getFullYear(),
    visibleMonth.getMonth(),
    1
  );
  const offset = (firstDay.getDay() + 6) % 7;
  const daysInMonth = new Date(
    visibleMonth.getFullYear(),
    visibleMonth.getMonth() + 1,
    0
  ).getDate();

  for (let index = 0; index < 42; index += 1) {
    const dayNumber = index - offset + 1;
    const cell = document.createElement("button");
    cell.className = "day";

    if (dayNumber < 1 || dayNumber > daysInMonth) {
      cell.disabled = true;
      cell.style.visibility = "hidden";
      calendar.append(cell);
      continue;
    }

    const date = new Date(
      visibleMonth.getFullYear(),
      visibleMonth.getMonth(),
      dayNumber
    );
    const shift = shiftFor(date);
    const amount = shift ? netForShift(shift) : 0;

    cell.textContent =
      `${dayNumber}${amount > 0 ? `\n${Math.round(amount)}₽` : ""}`;

    if (isWorkday(date)) {
      cell.classList.add("work");
      cell.style.background = periodColor(date);
    }

    if (amount >= 5000) {
      cell.classList.add("good");
    }

    if (dateKey(date) === dateKey(new Date())) {
      cell.classList.add("today");
    }

    cell.addEventListener("click", () => onDateClick(date));
    calendar.append(cell);
  }
}

async function onDateClick(date) {
  const key = dateKey(date);

  if (editMode === "add") {
    await storage.update((nextState) => {
      nextState.shifts[key] = {
        ...(nextState.shifts[key] || {}),
        override: 1
      };
      return nextState;
    });
    editMode = "normal";
    return;
  }

  if (editMode === "remove") {
    await storage.update((nextState) => {
      nextState.shifts[key] = {
        ...(nextState.shifts[key] || {}),
        override: 0
      };
      return nextState;
    });
    editMode = "normal";
    return;
  }

  if (!isWorkday(date)) {
    alert("Сначала добавьте смену через меню «График».");
    return;
  }

  activeDateKey = key;
  const shift = state.shifts[key] || { override: 1 };

  $("shiftDateTitle").textContent = date.toLocaleDateString("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric"
  });

  [
    ["cancelCount", "cancel"],
    ["acceptCount", "accept"],
    ["returnCount", "returns"],
    ["issueCount", "issue"],
    ["rejectCount", "reject"],
    ["paymentCount", "payment"],
    ["repackCount", "repack"]
  ].forEach(([id, field]) => {
    $(id).value = shift[field] || "";
  });

  updateShiftPreview();
  $("shiftDialog").showModal();
}

function shiftFromDialog() {
  return {
    ...(state.shifts[activeDateKey] || {}),
    override: 1,
    cancel: Number($("cancelCount").value) || 0,
    accept: Number($("acceptCount").value) || 0,
    returns: Number($("returnCount").value) || 0,
    issue: Number($("issueCount").value) || 0,
    reject: Number($("rejectCount").value) || 0,
    payment: Number($("paymentCount").value) || 0,
    repack: Number($("repackCount").value) || 0
  };
}

function updateShiftPreview() {
  $("shiftPreview").textContent =
    `Чистыми: ${Math.round(netForShift(shiftFromDialog()))} ₽`;
}

function buildPayPeriods(startMonth, monthCount) {
  const result = [];

  for (let index = 0; index < monthCount; index += 1) {
    const month = new Date(
      startMonth.getFullYear(),
      startMonth.getMonth() + index,
      1
    );
    const year = month.getFullYear();
    const monthIndex = month.getMonth();

    const firstHalfFrom = new Date(year, monthIndex, 1);
    const firstHalfTo = new Date(year, monthIndex, 15);

    result.push({
      payDate: new Date(year, monthIndex, 25),
      from: firstHalfFrom,
      to: firstHalfTo,
      color: periodColor(firstHalfFrom)
    });

    const secondHalfFrom = new Date(year, monthIndex, 16);
    const secondHalfTo = new Date(year, monthIndex + 1, 0);

    result.push({
      payDate: new Date(year, monthIndex + 1, 10),
      from: secondHalfFrom,
      to: secondHalfTo,
      color: periodColor(secondHalfFrom)
    });
  }

  return result.sort((left, right) => left.payDate - right.payDate);
}

function payPeriodTotal(period) {
  return Object.entries(state.shifts).reduce((sum, [key, shift]) => {
    const date = dateFromKey(key);

    if (
      date >= startOfDay(period.from) &&
      date <= startOfDay(period.to)
    ) {
      return sum + netForShift(shift);
    }

    return sum;
  }, 0);
}

function formatDate(date) {
  return date.toLocaleDateString("ru-RU", {
    day: "numeric",
    month: "long",
    year: "numeric"
  });
}

function formatShortDate(date) {
  return date.toLocaleDateString("ru-RU", {
    day: "numeric",
    month: "short"
  });
}

function upcomingPayPeriods() {
  const today = startOfDay(new Date());
  const startMonth = new Date(
    today.getFullYear(),
    today.getMonth() - 2,
    1
  );

  return buildPayPeriods(startMonth, 8)
    .filter((period) => startOfDay(period.payDate) >= today)
    .slice(0, 2);
}

function renderPayments() {
  $("payments").innerHTML = "";

  upcomingPayPeriods().forEach((period, index) => {
    const card = document.createElement("button");
    card.className = "payment-card";
    card.style.setProperty("--accent", period.color);
    card.innerHTML = `
      <span>${index === 0 ? "Ближайшая" : "Следующая"}</span>
      <b>${formatDate(period.payDate)}</b>
      <strong>${Math.round(payPeriodTotal(period))} ₽</strong>
      <small>${formatShortDate(period.from)}–${formatShortDate(period.to)}</small>
    `;
    card.addEventListener("click", showAllPayments);
    $("payments").append(card);
  });
}

function showAllPayments() {
  const now = new Date();
  const startMonth = new Date(
    now.getFullYear(),
    now.getMonth() - 12,
    1
  );

  $("paymentsList").innerHTML = buildPayPeriods(startMonth, 25)
    .map((period) => `
      <div
        class="payment-card"
        style="--accent:${period.color};margin-bottom:8px"
      >
        <b>${formatDate(period.payDate)}</b>
        <strong>${Math.round(payPeriodTotal(period))} ₽</strong>
        <small>${formatShortDate(period.from)}–${formatShortDate(period.to)}</small>
      </div>
    `)
    .join("");

  $("menuDialog").close();
  $("paymentsDialog").showModal();
}

function moveMonth(delta) {
  visibleMonth = new Date(
    visibleMonth.getFullYear(),
    visibleMonth.getMonth() + delta,
    1
  );
  render();
}

$("prevMonth").addEventListener("click", () => moveMonth(-1));
$("nextMonth").addEventListener("click", () => moveMonth(1));

$("todayButton").addEventListener("click", () => {
  const now = new Date();
  visibleMonth = new Date(now.getFullYear(), now.getMonth(), 1);
  render();
});

$("monthTitle").addEventListener("click", () => {
  const current = `${visibleMonth.getFullYear()}-${String(
    visibleMonth.getMonth() + 1
  ).padStart(2, "0")}`;

  const value = prompt("Введите месяц в формате ГГГГ-ММ", current);

  if (!/^\d{4}-\d{2}$/.test(value || "")) return;

  const [year, month] = value.split("-").map(Number);
  if (month < 1 || month > 12) return;

  visibleMonth = new Date(year, month - 1, 1);
  render();
});

$("menuButton").addEventListener("click", async () => {
  await refreshStorageStatus();
  $("menuDialog").showModal();
});

$("scheduleButton").addEventListener("click", () => {
  $("scheduleDialog").showModal();
});

$("allPaymentsButton").addEventListener("click", showAllPayments);

document.querySelectorAll("[data-pattern]").forEach((button) => {
  button.addEventListener("click", () => {
    pendingPattern = button.dataset.pattern;
    $("anchorDate").value = dateKey(new Date());
    $("scheduleDialog").close();
    $("anchorDialog").showModal();
  });
});

$("savePatternButton").addEventListener("click", async () => {
  const anchorDate = $("anchorDate").value;
  if (!anchorDate) return;

  await storage.update((nextState) => {
    nextState.schedule = {
      pattern: pendingPattern,
      anchorDate
    };
    return nextState;
  });

  editMode = "normal";
  $("anchorDialog").close();
});

$("addShiftMode").addEventListener("click", () => {
  editMode = "add";
  $("scheduleDialog").close();
  render();
});

$("removeShiftMode").addEventListener("click", () => {
  editMode = "remove";
  $("scheduleDialog").close();
  render();
});

[
  "cancelCount",
  "acceptCount",
  "returnCount",
  "issueCount",
  "rejectCount",
  "paymentCount",
  "repackCount"
].forEach((id) => {
  $(id).addEventListener("input", updateShiftPreview);
});

$("saveShiftButton").addEventListener("click", async () => {
  const shift = shiftFromDialog();

  await storage.update((nextState) => {
    nextState.shifts[activeDateKey] = shift;
    return nextState;
  });

  $("shiftDialog").close();
});

$("settingsButton").addEventListener("click", () => {
  [
    "basePickPrice",
    "shiftHours",
    "hourlyRate",
    "taxPercent"
  ].forEach((id) => {
    $(id).value = state.settings[id];
  });

  $("menuDialog").close();
  $("settingsDialog").showModal();
});

$("saveSettingsButton").addEventListener("click", async () => {
  await storage.update((nextState) => {
    nextState.settings = {
      basePickPrice: Number($("basePickPrice").value) || 6.1,
      shiftHours: Number($("shiftHours").value) || 10.75,
      hourlyRate: Number($("hourlyRate").value) || 147,
      taxPercent: Number($("taxPercent").value) || 13
    };
    return nextState;
  });

  $("settingsDialog").close();
});

$("exportButton").addEventListener("click", () => {
  storage.exportJson();
});

$("importInput").addEventListener("change", async (event) => {
  try {
    const file = event.target.files[0];
    if (file) {
      await storage.importJson(file);
    }
  } catch (error) {
    alert(error.message);
  } finally {
    event.target.value = "";
    $("menuDialog").close();
  }
});

$("enableAutoBackupButton").addEventListener("click", async () => {
  try {
    await storage.enableAutoBackup();
    await refreshStorageStatus();
  } catch (error) {
    alert(error.message);
  }
});

$("disableAutoBackupButton").addEventListener("click", async () => {
  await storage.disableAutoBackup();
  await refreshStorageStatus();
});

async function refreshStorageStatus() {
  const status = await storage.getAutoBackupStatus();

  if (!status.supported) {
    $("storageStatus").textContent =
      "Автосохранение в файл не поддерживается этим браузером";
  } else if (status.configured) {
    $("storageStatus").textContent =
      `Автосохранение: ${status.fileName}`;
  } else {
    $("storageStatus").textContent =
      "Автосохранение в файл выключено";
  }

  $("enableAutoBackupButton").hidden = !status.supported;
  $("disableAutoBackupButton").hidden = !status.configured;
}

calendar.addEventListener("pointerdown", (event) => {
  dragStartX = event.clientX;
});

calendar.addEventListener("pointerup", (event) => {
  if (dragStartX === null) return;

  const deltaX = event.clientX - dragStartX;
  dragStartX = null;

  if (Math.abs(deltaX) > 55) {
    moveMonth(deltaX < 0 ? 1 : -1);
  }
});

render();
