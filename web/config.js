/**
 * Публичная конфигурация PWA. Секретов здесь быть не должно.
 */
window.LAS_CONFIG = Object.freeze({
  API_BASE_URL: "https://YOUR-CLOUD-RUN-SERVICE.run.app",
  GOOGLE_CLIENT_ID: "YOUR-WEB-CLIENT-ID.apps.googleusercontent.com",
  CLOUD_SCHEMA_VERSION: 3,
  AUTO_SYNC_DEBOUNCE_MS: 1200,
});
