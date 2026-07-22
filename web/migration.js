window.LASMigration = {
  currentVersion: 1,
  migrate(data) {
    data.version = data.version || 1;
    return data;
  }
};
