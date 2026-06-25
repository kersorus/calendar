# Repository cleanup and Warehouse launcher fix

- Удалены временные архивы/папки обновлений из репозитория.
- Исправлен `app/src/timecalendar/AndroidManifest.xml`: label задаётся через flavor override.
- Исправлен `app/src/warehouse/AndroidManifest.xml`: добавлен `MAIN/LAUNCHER` для `WarehouseActivity`.
- Warehouse APK должен появляться в списке приложений и открываться по ярлыку.
