// Google Identity Services integration placeholder
// Uses LAS_CONFIG.GOOGLE_CLIENT_ID

window.LASGoogleAuth = {
  token: null,
  account: null,

  async connect() {
    alert("Google OAuth flow will be connected here.");
  },

  isConnected() {
    return !!this.token;
  }
};
