import { AppError } from "./errors.js";
import {
  base64ToBytes,
  base64UrlToBytes,
  bytesToBase64Url,
  utf8,
  text,
} from "./encoding.js";

const TOKEN_AAD = utf8("las-calendar-google-refresh-token-v1");

function decodeEncryptionKey(encoded) {
  try {
    const key = base64ToBytes(String(encoded).trim());
    if (key.length !== 32) throw new Error("wrong key length");
    return key;
  } catch (error) {
    throw new AppError("Ключ шифрования сервера настроен неверно", {
      code: "TOKEN_ENCRYPTION_KEY_INVALID",
      status: 500,
      cause: error,
    });
  }
}

async function importEncryptionKey(encoded) {
  return crypto.subtle.importKey(
    "raw",
    decodeEncryptionKey(encoded),
    { name: "AES-GCM" },
    false,
    ["encrypt", "decrypt"],
  );
}

export async function encryptSecret(value, encodedKey) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await importEncryptionKey(encodedKey);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, additionalData: TOKEN_AAD, tagLength: 128 },
    key,
    utf8(value),
  );
  return `v2.${bytesToBase64Url(iv)}.${bytesToBase64Url(new Uint8Array(ciphertext))}`;
}

export async function decryptSecret(envelope, encodedKey) {
  try {
    const [version, ivText, ciphertextText] = String(envelope).split(".");
    if (version !== "v2" || !ivText || !ciphertextText) throw new Error("invalid envelope");
    const key = await importEncryptionKey(encodedKey);
    const plaintext = await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: base64UrlToBytes(ivText),
        additionalData: TOKEN_AAD,
        tagLength: 128,
      },
      key,
      base64UrlToBytes(ciphertextText),
    );
    return text(plaintext);
  } catch (error) {
    throw new AppError("Не удалось прочитать сохранённый ключ Google", {
      code: "TOKEN_DECRYPT_FAILED",
      status: 500,
      cause: error,
    });
  }
}

export function randomToken(bytes = 32) {
  return bytesToBase64Url(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function hashToken(value) {
  const digest = await crypto.subtle.digest("SHA-256", utf8(value));
  return bytesToBase64Url(new Uint8Array(digest));
}

export function timingSafeEqualText(left, right) {
  const a = utf8(left);
  const b = utf8(right);
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let index = 0; index < a.length; index += 1) difference |= a[index] ^ b[index];
  return difference === 0;
}
