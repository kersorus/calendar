import test from "node:test";
import assert from "node:assert/strict";
import { mergeCloudPayloads, migrateCloudPayload } from "../src/cloud-record.js";

function payload({ settingsAt = 0, scheduleAt = 0, settings = {}, schedule = {}, shifts = {} } = {}) {
  const at = value => new Date(value).toISOString();
  return {
    format: "LaSalaryCloudBackup",
    version: 3,
    app: "las-salary",
    revision: "test",
    deviceId: "test",
    updatedAt: at(Math.max(settingsAt, scheduleAt, 0)),
    state: {
      settings: { value: settings, updatedAt: at(settingsAt) },
      schedule: { value: schedule, updatedAt: at(scheduleAt) },
      shifts,
    },
  };
}

test("remote settings beat untouched defaults on a new device", () => {
  const local = payload({ settingsAt: 0, settings: { hourlyRate: 147 } });
  const remote = payload({ settingsAt: 10_000, settings: { hourlyRate: 250 } });
  const merged = mergeCloudPayloads(local, remote, 20_000);
  assert.equal(merged.state.settings.value.hourlyRate, 250);
});

test("changes from two devices are kept", () => {
  const local = payload({
    shifts: {
      "2026-07-20": { value: { issue: 10 }, updatedAt: new Date(10_000).toISOString(), deleted: false },
    },
  });
  const remote = payload({
    shifts: {
      "2026-07-21": { value: { issue: 20 }, updatedAt: new Date(11_000).toISOString(), deleted: false },
    },
  });
  const merged = mergeCloudPayloads(local, remote);
  assert.deepEqual(Object.keys(merged.state.shifts).sort(), ["2026-07-20", "2026-07-21"]);
});

test("a newer deletion wins and is preserved", () => {
  const local = payload({
    shifts: {
      "2026-07-20": { value: null, updatedAt: new Date(20_000).toISOString(), deleted: true },
    },
  });
  const remote = payload({
    shifts: {
      "2026-07-20": { value: { issue: 10 }, updatedAt: new Date(10_000).toISOString(), deleted: false },
    },
  });
  const merged = mergeCloudPayloads(local, remote);
  assert.equal(merged.state.shifts["2026-07-20"].deleted, true);
});

test("ties are deterministic", () => {
  const timestamp = new Date(10_000).toISOString();
  const a = payload({ shifts: { "2026-07-20": { value: { issue: 1 }, updatedAt: timestamp, deleted: false } } });
  const b = payload({ shifts: { "2026-07-20": { value: { issue: 2 }, updatedAt: timestamp, deleted: false } } });
  const first = mergeCloudPayloads(a, b).state.shifts["2026-07-20"];
  const second = mergeCloudPayloads(b, a).state.shifts["2026-07-20"];
  assert.deepEqual(first, second);
});

test("legacy backup is migrated", () => {
  const migrated = migrateCloudPayload({
    format: "LaSalaryBackup",
    updatedAt: "2026-07-20T10:00:00.000Z",
    data: { settings: { hourlyRate: 200 }, schedule: {}, shifts: { "2026-07-20": { issue: 3 } } },
  });
  assert.equal(migrated.version, 3);
  assert.equal(migrated.state.settings.value.hourlyRate, 200);
  assert.equal(migrated.state.shifts["2026-07-20"].deleted, false);
});


test("settings and schedule cannot become tombstones", () => {
  const local = payload({ settingsAt: 10_000, settings: { hourlyRate: 180 } });
  const remote = payload({ settingsAt: 20_000, settings: { hourlyRate: 999 } });
  remote.state.settings.deleted = true;
  remote.state.settings.value = null;
  remote.state.schedule.deleted = true;
  remote.state.schedule.value = null;

  const merged = mergeCloudPayloads(local, remote, 30_000);
  assert.equal(merged.state.settings.deleted, false);
  assert.equal(merged.state.settings.value.hourlyRate, 180);
  assert.equal(merged.state.schedule.deleted, false);
  assert.deepEqual(merged.state.schedule.value, { pattern: "", anchorDate: "" });
});

test("malformed v3 records are normalized instead of spreading to devices", () => {
  const timestamp = "2026-07-22T12:00:00.000Z";
  const malformed = payload();
  malformed.updatedAt = timestamp;
  malformed.state = {
    settings: { value: "broken", updatedAt: "not-a-date", deleted: true },
    schedule: { value: null, updatedAt: null, deleted: true },
    shifts: {
      "not-a-date": { value: { issueCount: 2 }, updatedAt: timestamp },
      "2026-07-22": { value: "broken", updatedAt: timestamp },
      "2026-07-23": { value: { issueCount: 3 }, updatedAt: timestamp },
    },
  };

  const merged = mergeCloudPayloads(malformed, null, Date.parse(timestamp));
  assert.equal(merged.state.settings.deleted, undefined);
  assert.equal(merged.state.settings.value.basePickPrice, 6.1);
  assert.equal(merged.state.schedule.value.pattern, "");
  assert.deepEqual(Object.keys(merged.state.shifts), ["2026-07-23"]);
  assert.equal(merged.state.shifts["2026-07-23"].value.issueCount, 3);
});
