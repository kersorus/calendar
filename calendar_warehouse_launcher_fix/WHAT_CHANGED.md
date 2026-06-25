# Warehouse launcher fix

- Исправлен warehouse APK: добавляется MAIN/LAUNCHER activity.
- Если activity найдена в `app/src/warehouse/java`, скрипт пропишет её в `app/src/warehouse/AndroidManifest.xml`.
- Приложение должно появиться в списке приложений и открываться по ярлыку.
