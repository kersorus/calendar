function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function integer(name, fallback, min, max) {
  const raw = process.env[name];
  const value = raw == null || raw === "" ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}

function origins() {
  const values = required("ALLOWED_ORIGINS")
    .split(",")
    .map(value => value.trim().replace(/\/$/, ""))
    .filter(Boolean);

  for (const value of values) {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || url.pathname !== '/') {
      throw new Error(`Invalid origin in ALLOWED_ORIGINS: ${value}`);
    }
  }

  return new Set(values);
}

export const config = Object.freeze({
  port: integer("PORT", 8080, 1, 65535),
  googleClientId: required("GOOGLE_CLIENT_ID"),
  googleClientSecret: required("GOOGLE_CLIENT_SECRET"),
  tokenEncryptionKey: required("TOKEN_ENCRYPTION_KEY"),
  allowedOrigins: origins(),
  sessionIdleDays: integer("SESSION_IDLE_DAYS", 180, 7, 730),
  backupFileName: process.env.DRIVE_BACKUP_FILE_NAME?.trim() || "las_salary_backup.json",
  syncLeaseSeconds: integer("SYNC_LEASE_SECONDS", 30, 5, 120),
});
