window.LASDriveAPI = {
  FILE_NAME: "salary_backup.json",

  async save(data) {
    console.log("Save backup to Google Drive", data);
  },

  async load() {
    console.log("Load backup from Google Drive");
    return null;
  }
};
