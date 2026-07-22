#!/data/data/com.termux/files/usr/bin/bash
set -e

python3 - <<'PY'
from pathlib import Path

p = Path("web/ui.js")
s = p.read_text()

old1 = """function totalPicks(shift = {}) {
  return [
    "cancel", "accept", "returns", "issue",
    "reject", "payment", "repack"
  ].reduce((sum, field) => sum + Number(shift[field] || 0), 0);
}"""

new1 = """function totalPicks(shift = {}) {
  shift = shift || {};

  return [
    "cancel", "accept", "returns", "issue",
    "reject", "payment", "repack"
  ].reduce((sum, field) => sum + Number(shift[field] || 0), 0);
}"""

s = s.replace(old1, new1)

old2 = """function netForShift(shift = {}) {
  const weightedPicks ="""

new2 = """function netForShift(shift = {}) {
  shift = shift || {};

  const weightedPicks ="""

s = s.replace(old2, new2)

p.write_text(s)

sw = Path("web/sw.js")
if sw.exists():
    sw.write_text(sw.read_text().replace("las-pwa-debug-v1", "las-pwa-debug-v2"))
PY
