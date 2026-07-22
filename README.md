# Calendar

Приложения для учёта рабочего времени, смен и ориентировочного расчёта зарплаты.

В репозитории находятся:

- Android-приложение с вариантами `timecalendar` и `warehouse`;
- устанавливаемая PWA в каталоге `web`;
- сервер защищённой синхронизации в каталоге `server`.

## Как работает синхронизация

PWA всегда сохраняет рабочую копию локально и продолжает работать без интернета. После подключения Google резервная копия хранится в закрытом пространстве `appDataFolder` Google Drive.

```text
PWA ── Google Identity Services ── одноразовый OAuth code
 │                                      │
 └────────────── Cloud Run API ◄────────┘
                    │       │
               Firestore   Google Drive appDataFolder
```

Браузер не получает Google access token или refresh token. Одноразовый код передаётся backend, refresh token шифруется AES-256-GCM и хранится в Firestore. На устройстве остаётся только случайный идентификатор сессии приложения.

При обмене независимо сравниваются:

- настройки расчёта;
- параметры графика;
- каждая смена по дате;
- отметки об удалении смен.

Параллельная запись одного аккаунта блокируется короткой серверной арендой. Если локальные данные изменились во время сетевого запроса, устаревший ответ не применяется и синхронизация повторяется. Новый телефон с настройками по умолчанию сначала загружает облачную копию и не может затереть её пустыми данными.

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
  src/app.js                 HTTP API, CORS, CSRF-защита и ошибки
  src/auth-service.js        обмен OAuth code и обновление Google-токенов
  src/drive-service.js       чтение, объединение и запись копии в Drive
  src/store.js               пользователи, сессии и блокировки Firestore
  src/cloud-record.js        модель данных и разрешение конфликтов
  src/crypto.js              шифрование refresh token и хеширование сессий
  test/                      тесты объединения и гонок синхронизации
.github/workflows/
  cloud.yml                  проверки frontend и backend
  pages.yml                  публикация каталога web в GitHub Pages
```

## Настройка PWA

Публичные параметры находятся в `web/config.js`:

```js
window.LAS_CONFIG = Object.freeze({
  API_BASE_URL: "https://SERVICE-URL.run.app",
  GOOGLE_CLIENT_ID: "000000000000-example.apps.googleusercontent.com",
  CLOUD_SCHEMA_VERSION: 3,
  AUTO_SYNC_DEBOUNCE_MS: 1200,
});
```

`GOOGLE_CLIENT_ID` не является секретом. Client Secret и ключ шифрования в `web` добавлять нельзя.

Для OAuth-клиента типа **Web application** укажите Authorized JavaScript origin сайта, например:

```text
https://kersorus.github.io
```

Для popup Code Model отдельный OAuth redirect URI не требуется. Backend при обмене кода использует точный origin страницы.

GitHub Actions публикует содержимое `web/` после push в `main`. В настройках репозитория Pages должен быть выбран источник **GitHub Actions**.

## Настройка backend

Обязательные переменные окружения:

| Переменная | Назначение |
|---|---|
| `GOOGLE_CLIENT_ID` | тот же OAuth Client ID типа Web application, что указан в PWA |
| `GOOGLE_CLIENT_SECRET` | OAuth Client Secret |
| `TOKEN_ENCRYPTION_KEY` | 32 случайных байта в Base64 для AES-256-GCM |
| `ALLOWED_ORIGINS` | разрешённые origin PWA через запятую |

Дополнительные параметры перечислены в `server/.env.example`.

Для Cloud Run передавайте `GOOGLE_CLIENT_SECRET` и `TOKEN_ENCRYPTION_KEY` через Secret Manager. Сервисному аккаунту нужны роли для работы с Firestore и чтения этих двух секретов. Ключ `TOKEN_ENCRYPTION_KEY` нельзя заменять после появления пользователей: без прежнего ключа сохранённые refresh token невозможно расшифровать.

После первого подключения backend автоматически получает новые короткоживущие access token через refresh token. Повторный вход обычно требуется только после явного выхода или отзыва доступа, очистки локальной сессии, длительного бездействия либо аннулирования разрешения Google.

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

## Локальный запуск PWA

```bash
cd web
python3 -m http.server 8080
```

Для локальной проверки добавьте `http://localhost:8080` одновременно в `ALLOWED_ORIGINS` backend и в Authorized JavaScript origins OAuth-клиента.
