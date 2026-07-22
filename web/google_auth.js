window.LASGoogleAuth = {
  token: null,
  email: null,

  async connect() {
    console.log("Google OAuth start");
    // Google Identity Services hook
    // Real token flow is attached here
  },

  disconnect() {
    this.token = null;
    this.email = null;
  }
};
