# Wolfy

Читалка английских книг, которая превращает любую книгу в тихий урок: тап по слову —
контекстный перевод, разбор формы и коллокации; отмеченные слова уходят в колоду
интервальных повторений.

## Из чего собран

```
core/     Rust — потоковые парсеры книг, токенизация, лемматизация, грамматика
server/   Go   — общая с Читавуком авторизация, синхронизация, прокси внешних API
client/   Kotlin Compose Multiplatform — Android и Windows
proto/    общие DTO клиента, сервера и ядра
rules/    правила разработки по слоям — читать перед правкой слоя
tools/    генераторы словарей и вспомогательные скрипты
```

Ядро на Rust линкуется в клиент напрямую (JNI на Android, C-FFI на Windows): разбор
слова считается на устройстве и не ждёт сети. Сервер нужен для того, чего локально
не сделать — контекстного перевода, синхронизации между устройствами и OCR.

## Связь с Читавуком

Аккаунты общие. Wolfy проверяет тот же JWT (`WOLFY_JWT_SECRET` = секрет Читавука) и
читает таблицу пользователей Читавука, но собственные данные держит в схеме `wolfy`:
библиотеки, словари и колоды двух приложений не пересекаются.

## Требования к машине

| Инструмент      | Версия              |
|-----------------|---------------------|
| Rust            | 1.90+               |
| Go              | 1.25+               |
| JDK             | 21 (подойдёт jbr Android Studio) |
| Полный JDK 17   | только для установщика Windows: в jbr нет jpackage, Gradle скачает сам |
| Android SDK/NDK | NDK 27 или 28       |
| Docker          | для локального Postgres и Redis |

## Быстрый старт

```bash
cp .env.example .env      # заполнить ключи
docker compose up -d      # Postgres и Redis для разработки
cd core && cargo test     # ядро
cd server && go test ./...
tools/run_server.sh       # сервис на :8080, ключи берутся из .env
```

Клиент:

```bash
tools/build_core.sh host                    # ядро на Rust под текущую систему
cd client && ./gradlew :shared:desktopTest  # тесты общего кода
./gradlew :desktopApp:packageExe            # установщик под Windows
./gradlew :androidApp:assembleDebug         # apk
```

## Документация

- `rules/` — правила по слоям: [ядро](rules/rust_core.md), [сервер](rules/go_server.md),
  [клиент](rules/kotlin_client.md), [грамматика](rules/grammar_engine.md),
  [общий протокол](rules/shared_protocol.md)
- `docs/architecture.md` — как слои связаны и почему именно так
