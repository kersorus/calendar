import crypto from "node:crypto";
import { AppError } from "./errors.js";

function decodeKey(encoded) {
  const key = Buffer.from(encoded, "base64");
  if (key.length !== 32) {
    throw new Error("TOKEN_ENCRYPTION_KEY must be a base64-encoded 32-byte key");
  }
  return key;
}

export class SecretBox {
  constructor(encodedKey) {
    this.key = decodeKey(encodedKey);
  }

  encrypt(value) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", this.key, iv);
    const ciphertext = Buffer.concat([cipher.update(String(value), "utf8"), cipher.final()]);
    const tag = cipher.getAuthTag();
    return ["v1", iv.toString("base64url"), tag.toString("base64url"), ciphertext.toString("base64url")].join(".");
  }

  decrypt(envelope) {
    try {
      const [version, ivText, tagText, ciphertextText] = String(envelope).split(".");
      if (version !== "v1" || !ivText || !tagText || !ciphertextText) throw new Error("invalid envelope");
      const decipher = crypto.createDecipheriv(
        "aes-256-gcm",
        this.key,
        Buffer.from(ivText, "base64url"),
      );
      decipher.setAuthTag(Buffer.from(tagText, "base64url"));
      return Buffer.concat([
        decipher.update(Buffer.from(ciphertextText, "base64url")),
        decipher.final(),
      ]).toString("utf8");
    } catch (error) {
      throw new AppError("Не удалось прочитать сохранённый ключ Google", {
        code: "TOKEN_DECRYPT_FAILED",
        status: 500,
        cause: error,
      });
    }
  }
}

export function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64url");
}

export function hashToken(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

export function randomId() {
  return crypto.randomUUID();
}
