import { config } from "./config.js";
import { SecretBox } from "./crypto.js";
import { Store } from "./store.js";
import { AuthService } from "./auth-service.js";
import { DriveService } from "./drive-service.js";
import { createApp } from "./app.js";

const secretBox = new SecretBox(config.tokenEncryptionKey);
const store = new Store(config);
const authService = new AuthService({
  clientId: config.googleClientId,
  clientSecret: config.googleClientSecret,
  secretBox,
  store,
});
const driveService = new DriveService({
  authService,
  store,
  backupFileName: config.backupFileName,
});

const app = createApp({ config, store, authService, driveService });
app.listen(config.port, () => {
  console.log(`La$ backend is listening on port ${config.port}`);
});
