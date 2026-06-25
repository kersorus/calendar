#!/usr/bin/env bash
set -e

ROOT="$(pwd)"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Fixing flavor manifest label conflicts..."

fix_manifest() {
  local file="$1"
  local label="$2"

  if [ ! -f "$file" ]; then
    echo "Skip: $file not found"
    return
  fi

  python3 - "$file" "$label" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
label = sys.argv[2]
text = path.read_text(encoding="utf-8")

# Ensure tools namespace exists on <manifest>
if 'xmlns:tools=' not in text:
    text = text.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools"',
        1
    )

# Ensure the application label override is explicit for flavor manifest
if 'tools:replace="android:label"' not in text:
    text = text.replace(
        '<application',
        '<application\n        tools:replace="android:label"',
        1
    )

# Ensure the intended label is present
import re
text = re.sub(r'android:label="[^"]*"', f'android:label="{label}"', text, count=1)

path.write_text(text, encoding="utf-8")
PY
}

fix_manifest "app/src/timecalendar/AndroidManifest.xml" "Часы и цели"
fix_manifest "app/src/warehouse/AndroidManifest.xml" "Складская зарплата"

echo "Cleaning temporary update archives/folders..."
rm -f calendar_*.zip || true
find . -maxdepth 1 -type d \( -name "calendar_*_update" -o -name "calendar_*_fix" -o -name "calendar_warehouse_manifest_fix" \) -exec rm -rf {} + 2>/dev/null || true

echo "Done."
