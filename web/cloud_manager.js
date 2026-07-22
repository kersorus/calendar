window.LASCloudManager = {
  connected: false,
  account: null,

  async connect() {
    console.log("Connect Google account");
  },

  async disconnect() {
    this.connected = false;
    this.account = null;
  },

  async sync() {
    console.log("Manual cloud sync");
  }
};
