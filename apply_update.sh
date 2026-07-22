#!/data/data/com.termux/files/usr/bin/bash
set -e

python3 - <<'PY'
from pathlib import Path

p = Path("web/index.html")
s = p.read_text()

s = s.replace('<script src="./storage.js"></script>\n', '')
s = s.replace('<script src="./ui.js"></script>\n', '')

p.write_text(s)

sw = Path("web/sw.js")
if sw.exists():
    sw.write_text(sw.read_text().replace("las-pwa-v4", "las-pwa-v5"))

PY
