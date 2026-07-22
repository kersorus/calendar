# Salary PWA fix v0.5

Причина:
- ui.js запускался раньше app.js;
- LaStorage ещё не существовал;
- app.js сам импортирует ui.js после создания API.

Исправление:
- удалён прямой запуск storage.js и ui.js из index.html;
- загрузка теперь идёт только через app.js.
