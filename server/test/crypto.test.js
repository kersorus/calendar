import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { decryptSecret, encryptSecret, hashToken, randomToken } from "../src/crypto.js";

if (!globalThis.crypto) globalThis.crypto = webcrypto;

const key = Buffer.alloc(32, 7).toString("base64");

test("refresh tokens are encrypted and decrypted", async () => {
  const envelope = await encryptSecret("refresh-token-value", key);
  assert.match(envelope, /^v2\./);
  assert.equal(await decryptSecret(envelope, key), "refresh-token-value");
  assert.ok(!envelope.includes("refresh-token-value"));
});

test("session tokens are random and only their hash is used as an identifier", async () => {
  const first = randomToken();
  const second = randomToken();
  assert.notEqual(first, second);
  assert.notEqual(await hashToken(first), first);
  assert.equal(await hashToken(first), await hashToken(first));
});
