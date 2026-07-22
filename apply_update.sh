#!/data/data/com.termux/files/usr/bin/bash
set -e

python3 - <<'PY'
from pathlib import Path
import re

p = Path("web/storage.js")
s = p.read_text()

# Remove broken compatibility block that was appended to an ES module.
s = re.sub(
    r'\nwindow\.LaStorage = \{.*?\n\};\s*$',
    '\n',
    s,
    flags=re.S
)

p.write_text(s)

sw = Path("web/sw.js")
if sw.exists():
    sw.write_text(sw.read_text().replace("las-pwa-v5", "las-pwa-v6"))

PY
