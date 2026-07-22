import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { bytesToBase64Url, utf8 } from "../src/encoding.js";
import {
  accountFromGoogleProfile,
  exchangeAuthorizationCode,
  verifyGoogleIdToken,
} from "../src/google-oauth.js";

if (!globalThis.crypto) globalThis.crypto = webcrypto;

async function signedIdToken(clientId) {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  );
  const publicJwk = await crypto.subtle.exportKey("jwk", pair.publicKey);
  publicJwk.kid = "test-key";
  publicJwk.alg = "RS256";
  publicJwk.use = "sig";

  const now = Math.floor(Date.now() / 1000);
  const header = bytesToBase64Url(utf8(JSON.stringify({ alg: "RS256", kid: "test-key", typ: "JWT" })));
  const payload = bytesToBase64Url(utf8(JSON.stringify({
    iss: "https://accounts.google.com",
    aud: clientId,
    sub: "google-user-1",
    email: "owner@example.com",
    email_verified: true,
    name: "Owner",
    iat: now,
    exp: now + 3600,
  })));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    pair.privateKey,
    utf8(`${header}.${payload}`),
  );
  return {
    token: `${header}.${payload}.${bytesToBase64Url(new Uint8Array(signature))}`,
    publicJwk,
  };
}

test("authorization code exchange sends the page origin as redirect_uri", async () => {
  let submitted = null;
  const fetchImpl = async (_url, options) => {
    submitted = new URLSearchParams(options.body);
    return new Response(JSON.stringify({ access_token: "access", id_token: "id" }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };
  await exchangeAuthorizationCode({
    code: "one-time-code",
    redirectUri: "https://kersorus.github.io",
    clientId: "client-id",
    clientSecret: "client-secret",
    fetchImpl,
  });
  assert.equal(submitted.get("redirect_uri"), "https://kersorus.github.io");
  assert.equal(submitted.get("grant_type"), "authorization_code");
});

test("Google ID token signature and audience are verified", async () => {
  const clientId = "client-id.apps.googleusercontent.com";
  const { token, publicJwk } = await signedIdToken(clientId);
  const fetchImpl = async () => new Response(JSON.stringify({ keys: [publicJwk] }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
  const profile = await verifyGoogleIdToken(token, clientId, { fetchImpl });
  assert.equal(profile.sub, "google-user-1");
  assert.deepEqual(accountFromGoogleProfile(profile), {
    sub: "google-user-1",
    email: "owner@example.com",
    name: "Owner",
    picture: "",
  });
});
