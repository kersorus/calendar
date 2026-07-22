#!/data/data/exec/bash
set -e

python3 - <<'PY'
from pathlib import Path

index = Path("web/index.html")
text = index.read_text()

debug = """
<script>
(function () {
  window.__LAS_DEBUG = [];
  window.LASDebugLog = function(msg) {
    window.__LAS_DEBUG.push(msg);
    console.log("[LAS DEBUG]", msg);
  };

  window.addEventListener("error", function(e) {
    const box = document.createElement("pre");
    box.style = "position:fixed;top:0;left:0;right:0;background:#500;color:white;z-index:99999;padding:12px;white-space:pre-wrap";
    box.textContent =
      "LAS ERROR\\n\\n" +
      e.message + "\\n\\n" +
      e.filename + ":" + e.lineno + ":" + e.colno + "\\n\\n" +
      (e.error ? e.error.stack : "");
    document.body.appendChild(box);
  });

  window.addEventListener("unhandledrejection", function(e) {
    const box = document.createElement("pre");
    box.style = "position:fixed;top:0;left:0;right:0;background:#800;color:white;z-index:99999;padding:12px;white-space:pre-wrap";
    box.textContent = "PROMISE ERROR\\n\\n" + e.reason;
    document.body.appendChild(box);
  });

  window.LASDebugLog("index loaded");
})();
</script>
"""

if "LAS ERROR" not in text:
    text = text.replace("<head>", "<head>" + debug)

index.write_text(text)

for name in ["app.js", "storage.js", "ui.js"]:
    p = Path("web") / name
    if p.exists():
        s = p.read_text()
        if "LASDebugLog" not in s:
            s = 'try { window.LASDebugLog && window.LASDebugLog("' + name + ' loaded"); } catch(e) {}\n' + s
            p.write_text(s)

sw = Path("web/sw.js")
if sw.exists():
    sw.write_text(sw.read_text().replace("las-pwa-v6", "las-pwa-debug-v1"))

PY
