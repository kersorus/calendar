import test from "node:test";
import assert from "node:assert/strict";
import { DriveSyncEngine } from "../../web/drive_sync.js";

function memoryStorage() {
  const values = new Map();
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    clear: () => values.clear(),
  };
}

test("an in-flight response cannot overwrite a newer local edit", async () => {
  globalThis.localStorage = memoryStorage();

  let releaseRequest;
  let requestStarted;
  const started = new Promise(resolve => { requestStarted = resolve; });
  const waiting = new Promise(resolve => { releaseRequest = resolve; });

  const api = {
    async synchronize(payload) {
      requestStarted();
      await waiting;
      return {
        payload,
        file: { modifiedTime: "2026-07-22T10:00:00.000Z" },
        serverTime: "2026-07-22T10:00:00.000Z",
      };
    },
  };

  const defaults = {
    settings: { hourlyRate: 147 },
    schedule: { pattern: "", anchorDate: "" },
    shifts: {},
  };
  const engine = new DriveSyncEngine(api, defaults);
  const firstState = { ...defaults, shifts: {} };
  const pending = engine.synchronize(firstState);

  await started;
  const newerState = {
    ...defaults,
    shifts: { "2026-07-22": { issue: 42 } },
  };
  engine.markLocalChanges(newerState, Date.parse("2026-07-22T10:00:01.000Z"));
  releaseRequest();

  const result = await pending;
  assert.equal(result.stale, true);

  const retryPayload = engine.buildPayload(newerState, Date.parse("2026-07-22T10:00:02.000Z"));
  assert.equal(retryPayload.state.shifts["2026-07-22"].value.issue, 42);
  assert.equal(retryPayload.state.shifts["2026-07-22"].deleted, false);
});
