window.LASCloud = {
  enabled: false,

  async sync() {
    if (!this.enabled) return;
    console.log("Cloud sync");
  }
};
