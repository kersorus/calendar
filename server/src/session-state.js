import { DurableObject } from "cloudflare:workers";

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}

function sessionIdleMs(env) {
  const days = Number(env.SESSION_IDLE_DAYS || 180);
  const safeDays = Number.isInteger(days) && days >= 7 && days <= 730 ? days : 180;
  return safeDays * 24 * 60 * 60 * 1000;
}

export class SessionState extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    if (request.method === "POST" && path === "/create") return this.create(request);
    if (request.method === "POST" && path === "/read") return this.read();
    if (request.method === "DELETE" && path === "/delete") return this.remove();
    return json({ error: "not found" }, 404);
  }

  async create(request) {
    const input = await request.json();
    const now = Date.now();
    const record = {
      userId: String(input.userId || ""),
      account: input.account || null,
      createdAt: now,
      lastSeenAt: now,
      expiresAt: now + sessionIdleMs(this.env),
    };
    if (!record.userId || !record.account) return json({ error: "invalid session" }, 400);
    await this.ctx.storage.put("session", record);
    await this.ctx.storage.setAlarm(record.expiresAt);
    return json({ ok: true, expiresAt: record.expiresAt });
  }

  async read() {
    const record = await this.ctx.storage.get("session");
    if (!record) return json({ error: "missing" }, 404);
    const now = Date.now();
    if (Number(record.expiresAt) <= now) {
      await this.ctx.storage.deleteAll();
      return json({ error: "expired" }, 404);
    }

    if (now - Number(record.lastSeenAt || 0) >= 12 * 60 * 60 * 1000) {
      record.lastSeenAt = now;
      record.expiresAt = now + sessionIdleMs(this.env);
      await this.ctx.storage.put("session", record);
      await this.ctx.storage.setAlarm(record.expiresAt);
    }
    return json(record);
  }

  async remove() {
    await this.ctx.storage.deleteAll();
    return new Response(null, { status: 204 });
  }

  async alarm() {
    await this.ctx.storage.deleteAll();
  }
}
