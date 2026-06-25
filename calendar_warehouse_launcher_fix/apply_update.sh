#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
UPDATE_NAME="$(basename "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

python3 - <<'PY'
from pathlib import Path
import re
import sys

project = Path(".")
warehouse_java = project / "app/src/warehouse/java"

if not warehouse_java.exists():
    print("Error: app/src/warehouse/java not found")
    sys.exit(1)

candidates = []

for java_file in warehouse_java.rglob("*.java"):
    text = java_file.read_text(encoding="utf-8")
    package_match = re.search(r'^\s*package\s+([a-zA-Z0-9_.]+)\s*;', text, re.M)
    class_match = re.search(r'public\s+class\s+([A-Za-z0-9_]+)\s+extends\s+([A-Za-z0-9_.]+)', text)

    if not package_match or not class_match:
        continue

    package_name = package_match.group(1)
    class_name = class_match.group(1)
    parent = class_match.group(2)

    if "Activity" in parent:
        full_name = package_name + "." + class_name
        score = 0
        lower = class_name.lower()
        if "warehouse" in lower:
            score += 10
        if "main" in lower:
            score += 5
        candidates.append((score, full_name, str(java_file)))

if not candidates:
    print("Error: no Activity class found in app/src/warehouse/java")
    sys.exit(1)

candidates.sort(reverse=True)
activity_name = candidates[0][1]

manifest = project / "app/src/warehouse/AndroidManifest.xml"
manifest.parent.mkdir(parents=True, exist_ok=True)

manifest_text = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:label="Складская зарплата"
        tools:replace="android:label">

        <activity
            android:name="{activity_name}"
            android:exported="true"
            tools:node="merge">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

        </activity>

    </application>

</manifest>
'''

manifest.write_text(manifest_text, encoding="utf-8")
print(f"Warehouse launcher activity set to: {activity_name}")
PY

if [ -f "$SCRIPT_DIR/WHAT_CHANGED.md" ]; then
  cp "$SCRIPT_DIR/WHAT_CHANGED.md" "$PROJECT_DIR/WHAT_CHANGED.md"
fi

rm -f "$PROJECT_DIR/$UPDATE_NAME.zip"
rm -rf "$SCRIPT_DIR"

echo "Warehouse launcher fix applied."
