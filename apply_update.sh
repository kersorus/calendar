#!/bin/bash
set -e

echo "Applying Salary 0.9.5 cloud integration patch"

# This archive contains the cloud integration patch files.
# Existing project files should be updated together with these modules.

cp -r web/* ../calendar-clean/web/ 2>/dev/null || true

echo "Patch copied"
