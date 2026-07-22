/**
 * Public browser configuration.
 * OAuth client IDs are not secrets; never put a client secret in a PWA.
 */
window.LAS_CONFIG = Object.freeze({
  GOOGLE_CLIENT_ID:
    "446294536354-ako75ioe9j7ssjpulsr6g0996ftifmr1.apps.googleusercontent.com",
  GOOGLE_SCOPES: [
    "https://www.googleapis.com/auth/drive.appdata",
    "openid",
    "email",
    "profile",
  ].join(" "),
  DRIVE_BACKUP_FILE_NAME: "las_salary_backup.json",
  CLOUD_SCHEMA_VERSION: 3,
  AUTO_SYNC_DEBOUNCE_MS: 1200,
});
