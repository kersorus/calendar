# Time Calendar Minimal

Минимальное Android-приложение с C/NDK-ядром.

Функции:

- профиль `Работа`;
- старт таймера;
- пауза / продолжить;
- стоп;
- foreground-уведомление с кнопками;
- сохранение сессий в SQLite;
- месячная норма часов;
- отчёт: план к текущему дню, факт, баланс, остаток/переработка;
- расчёты вынесены в C через JNI.

## Сборка на Ubuntu

Нужны:

- JDK 17+
- Android SDK
- Android NDK
- CMake
- Gradle Wrapper из этого репозитория (`./gradlew`)

Самый простой вариант - открыть проект в Android Studio. Она сама предложит
установить Android SDK, NDK и CMake.

Для сборки из терминала установите Android command-line tools и пакеты SDK:

```bash
cd TimeCalendarMinimal

sdkmanager \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"
```

Если SDK установлен не в стандартном месте, создайте `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Сборка debug APK:

```bash
./gradlew assembleDebug
```

APK:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

Установка:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## GitHub

В проект добавлен Gradle Wrapper, поэтому на GitHub Actions APK будет собираться
без установленного системного Gradle. Чтобы загрузить проект в новый репозиторий:

```bash
git init
git add .
git commit -m "Initial Android time calendar app"
git branch -M main
git remote add origin git@github.com:USER/REPO.git
git push -u origin main
```

После push workflow `Android` соберёт debug APK и прикрепит его как artifact
`time-calendar-debug-apk`.

## Примечание по текущей машине

Системный Gradle 4.4.1 из Ubuntu слишком старый для Android Gradle Plugin 8.x.
Используйте `./gradlew`, а не команду `gradle`.
