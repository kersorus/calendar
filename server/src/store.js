import { Firestore } from "@google-cloud/firestore";
import { AppError } from "./errors.js";
import { hashToken, randomToken } from "./crypto.js";

function millis(value) {
  if (!value) return 0;
  if (value instanceof Date) return value.getTime();
  if (typeof value.toMillis === "function") return value.toMillis();
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export class Store {
  constructor({ sessionIdleDays, syncLeaseSeconds }) {
    this.db = new Firestore();
    this.sessionIdleMs = sessionIdleDays * 24 * 60 * 60 * 1000;
    this.syncLeaseMs = syncLeaseSeconds * 1000;
  }

  async getUser(userId) {
    const snapshot = await this.db.collection("oauth_users").doc(userId).get();
    return snapshot.exists ? snapshot.data() : null;
  }

  async saveUser(userId, data) {
    const ref = this.db.collection("oauth_users").doc(userId);
    const existing = await ref.get();
    await ref.set(
      {
        ...data,
        createdAt: existing.exists ? existing.data().createdAt : new Date(),
        updatedAt: new Date(),
      },
      { merge: true },
    );
  }

  async deleteUser(userId) {
    await this.db.collection("oauth_users").doc(userId).delete();
  }

  async createSession(userId, account) {
    const token = randomToken(32);
    const now = Date.now();
    await this.db.collection("cloud_sessions").doc(hashToken(token)).set({
      userId,
      account,
      createdAt: new Date(now),
      lastSeenAt: new Date(now),
      expiresAt: new Date(now + this.sessionIdleMs),
    });
    return token;
  }

  async readSession(token, { touch = true } = {}) {
    const ref = this.db.collection("cloud_sessions").doc(hashToken(token));
    const snapshot = await ref.get();
    if (!snapshot.exists) return null;
    const data = snapshot.data();
    const now = Date.now();
    if (millis(data.expiresAt) <= now) {
      await ref.delete();
      return null;
    }

    if (touch && now - millis(data.lastSeenAt) > 12 * 60 * 60 * 1000) {
      await ref.update({
        lastSeenAt: new Date(now),
        expiresAt: new Date(now + this.sessionIdleMs),
      });
    }

    return { id: snapshot.id, ...data };
  }

  async deleteSession(token) {
    await this.db.collection("cloud_sessions").doc(hashToken(token)).delete();
  }

  async deleteSessionById(id) {
    await this.db.collection("cloud_sessions").doc(id).delete();
  }

  async deleteUserSessions(userId) {
    const snapshots = await this.db.collection("cloud_sessions").where("userId", "==", userId).get();
    for (let offset = 0; offset < snapshots.docs.length; offset += 400) {
      const batch = this.db.batch();
      for (const document of snapshots.docs.slice(offset, offset + 400)) batch.delete(document.ref);
      await batch.commit();
    }
  }

  async acquireSyncLease(userId, owner) {
    const ref = this.db.collection("sync_locks").doc(userId);
    const now = Date.now();
    await this.db.runTransaction(async transaction => {
      const snapshot = await transaction.get(ref);
      const data = snapshot.exists ? snapshot.data() : null;
      if (data && data.owner !== owner && millis(data.expiresAt) > now) {
        throw new AppError("Синхронизация уже выполняется на другом устройстве", {
          code: "SYNC_BUSY",
          status: 409,
        });
      }
      transaction.set(ref, {
        owner,
        acquiredAt: new Date(now),
        expiresAt: new Date(now + this.syncLeaseMs),
      });
    });
  }

  async releaseSyncLease(userId, owner) {
    const ref = this.db.collection("sync_locks").doc(userId);
    await this.db.runTransaction(async transaction => {
      const snapshot = await transaction.get(ref);
      if (snapshot.exists && snapshot.data().owner === owner) transaction.delete(ref);
    });
  }
}
