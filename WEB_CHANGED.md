# Salary PWA fix v0.6

Fixed wrong previous bootstrap patch:
- removed accidental window.LaStorage override from storage.js;
- kept app.js as the single initializer;
- ui.js is loaded only after IndexedDB state and LaStorage API are ready.
