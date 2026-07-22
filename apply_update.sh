#!/data/data/com.termux/files/usr/bin/bash
set -e

python3 - <<'PY'
from pathlib import Path

index = Path("web/index.html")
text = index.read_text()

text = text.replace(
    '<script type="module" src="./app.js"></script>',
    '<script src="./storage.js"></script>\n<script type="module" src="./app.js"></script>\n<script src="./ui.js"></script>'
)

index.write_text(text)

storage = Path("web/storage.js")
text = storage.read_text()

if "window.LaStorage" not in text:
    text += '''

window.LaStorage = {
  getState: () => window.__LaState || {},
  update: async (callback) => {
    let state = window.__LaState || {};
    state = callback(state) || state;
    window.__LaState = state;
    window.dispatchEvent(new Event("las-state-changed"));
  },
  exportJson: async () => {},
  importJson: async () => {},
  enableAutoBackup: async () => {},
  disableAutoBackup: async () => {},
  getAutoBackupStatus: async () => ({
    supported:false,
    configured:false
  })
};
'''
    storage.write_text(text)

sw = Path("web/sw.js")
if sw.exists():
    sw.write_text(sw.read_text().replace("las-pwa-v3", "las-pwa-v4"))

PY

echo "Fix applied"
