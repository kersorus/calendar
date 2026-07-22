window.LASCloudManager = {
  account: null,
  token: null,

  connectButtonText() {
    return this.account ? "Google Drive подключен" : "Подключить Google";
  },

  async connect() {
    console.log("Starting Google OAuth flow");
    // Google Identity Services integration point
  },

  async disconnect() {
    this.account = null;
    this.token = null;
  },

  async syncUpload() {
    console.log("Uploading LAS_salary_backup.json");
  },

  async syncDownload() {
    console.log("Downloading LAS_salary_backup.json");
  }
};
