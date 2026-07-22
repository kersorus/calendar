# Calendar

Приложения для учёта рабочего времени, смен и ориентировочного расчёта зарплаты.

В репозитории находятся:

- Android-приложение с вариантами `timecalendar` и `warehouse`;
- устанавливаемая PWA в каталоге `web`;
- Cloudflare Worker защищённой синхронизации в каталоге `server`.

## Синхронизация

PWA всегда сохраняет рабочую копию локально и продолжает работать без интернета. После подключения Google резервная копия хранится в закрытом пространстве `appDataFolder` Google Drive.

```text
PWA ── Google Identity Services ── одноразовый OAuth code
 │                                      │
 └──────── Cloudflare Worker ◄──────────┘
                 │           │
        Durable Objects     Google Drive appDataFolder
```

Браузер не получает Google access token или refresh token. Одноразовый код передаётся Worker, refresh token шифруется AES-256-GCM и хранится в SQLite-backed Durable Object пользователя. На устройстве остаётся только случайный ключ серверной сессии.

Для каждого Google-аккаунта используется отдельный Durable Object. Все операции синхронизации одного аккаунта выполняются последовательно, поэтому одновременные записи с нескольких устройств не конфликтуют на уровне сервера.

При объединении независимо сравниваются:

- настройки расчёта;
- параметры графика;
- каждая смена по дате;
- отметки об удалении смен.

Если локальные данные изменились во время сетевого запроса, устаревший ответ не применяется и синхронизация повторяется. Новый телефон с настройками по умолчанию сначала загружает облачную копию и не может затереть её пустыми данными.

## Структура

```text
app/                         Android-код и ресурсы
web/
  app.js                     запуск PWA и единая точка изменения состояния
  storage.js                 IndexedDB, импорт и экспорт JSON
  migration.js               проверка и миграция старых форматов
  google_auth.js             GIS popup code flow и серверная сессия
  drive_api.js               запросы к API синхронизации
  drive_sync.js              локальные версии записей и подготовка снимка
  cloud_manager.js           автосинхронизация и обработка состояний
  cloud_ui.js                интерфейс подключения и управления копией
  privacy.html               политика конфиденциальности
  terms.html                 условия использования
server/
  src/index.js               HTTP API, CORS, OAuth code exchange и маршрутизация
  src/user-state.js          пользователь, refresh token и последовательная синхронизация
  src/session-state.js       серверная сессия устройства и автоматическое истечение
  src/google-oauth.js        обмен, проверка и обновление Google-токенов
  src/google-drive.js        операции с закрытой папкой Google Drive
  src/cloud-record.js        модель данных и разрешение конфликтов
  src/crypto.js              AES-GCM и хеширование ключей сессий
  wrangler.jsonc             конфигурация Worker и Durable Objects
  test/                      тесты миграции, конфликтов, OAuth и шифрования
.github/workflows/
  cloud.yml                  проверки frontend и Worker
  pages.yml                  публикация каталога web в GitHub Pages
```

## Публичная конфигурация PWA

`web/config.js`:

```js
window.LAS_CONFIG = Object.freeze({
  API_BASE_URL: "https://las-calendar-sync.example.workers.dev",
  GOOGLE_CLIENT_ID: "446294536354-ako75ioe9j7ssjpulsr6g0996ftifmr1.apps.googleusercontent.com",
  CLOUD_SCHEMA_VERSION: 3,
  AUTO_SYNC_DEBOUNCE_MS: 1200,
});
```

`GOOGLE_CLIENT_ID` не является секретом. Google Client Secret и ключ шифрования в `web` добавлять нельзя.

Для OAuth-клиента типа **Web application** Authorized JavaScript origin должен быть:

```text
https://kersorus.github.io
```

Для GIS Popup Code Model отдельный OAuth redirect URI не требуется. Worker при обмене кода использует точный origin страницы.

## Развёртывание Cloudflare Worker

Worker публикуется из каталога `server`. В Cloudflare Workers Builds укажите:

```text
Root directory: server
Deploy command: npx wrangler deploy
```

`wrangler.jsonc` уже содержит Client ID, разрешённый origin и декларативные `exports` для двух SQLite-backed Durable Object-классов.

После первого развёртывания добавьте в настройках Worker два секрета:

```text
GOOGLE_CLIENT_SECRET
TOKEN_ENCRYPTION_KEY
```

`TOKEN_ENCRYPTION_KEY` — Base64-строка из 32 случайных байт. После появления пользователей этот секрет нельзя менять: старые refresh token зашифрованы прежним ключом.

Полученный адрес `*.workers.dev` укажите в `web/config.js`, затем отправьте изменение в `main`.

## Управление данными

- **Отключить устройство** — удаляет только сессию текущего браузера.
- **Отозвать доступ** — удаляет refresh token и завершает сессии на всех устройствах; резервная копия остаётся в Drive.
- **Удалить облачную копию** — удаляет файлы приложения из `appDataFolder`, refresh token и все серверные сессии. Локальная база текущего устройства сохраняется.

Для важных данных дополнительно используйте ручной экспорт JSON.

## Проверки

```bash
cd server
npm install --no-audit --no-fund
npm run check
npm test

cd ..
find web -type f -name '*.js' -print0 | xargs -0 -n1 node --check
```

Android-сборка:

```bash
./gradlew assembleTimecalendarDebug assembleWarehouseDebug
```
