#!/usr/bin/env bash
#
# Запускает сервис локально.
#
# Ключи лежат в `.env` в корне и в репозиторий не попадают. Скрипт читает их
# сам, чтобы не приходилось выписывать переменные руками в командной строке:
# оттуда они попадают в историю оболочки, а история переживает и репозиторий,
# и осторожность.
#
# База — та, что поднимает docker-compose: отдельный Postgres на 5433, а не
# боевой инстанс Читавука. Адрес можно перебить, задав WOLFY_DB_URL заранее.
#
# Использование:
#   tools/run_server.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Читаем .env строка за строкой, а не через `source`: файл пишут руками, в нём
# попадаются кавычки и пробелы вокруг знака равенства, и отдать такое на
# исполнение оболочке — значит однажды выполнить то, что там написано.
if [ -f "$ROOT/.env" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        # Файл правят и в Windows: возврат каретки в конце строки уехал бы в
        # значение ключа и сломал бы адрес базы незаметно.
        line="${line%$'\r'}"

        # Строка без «=» — комментарий, пустая строка или опечатка;
        # экспортировать её нельзя, оболочка ответит на это ошибкой посреди
        # запуска. Имя переменной проверяем по тем же соображениям.
        case "$line" in
            *=*) ;;
            *) continue ;;
        esac

        key="${line%%=*}"
        value="${line#*=}"

        # Пробелы вокруг знака равенства — оформление файла, а не часть имени.
        # Без обрезки `POLZA_AI_KEY =` даёт ключ с хвостовым пробелом, проверка
        # ниже отбрасывает его как опечатку, и переменная молча не доезжает до
        # сервиса. Ключ при этом в файле есть, и искать потерю приходится в
        # последнюю очередь здесь.
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"
        value="${value#"${value%%[![:space:]]*}"}"

        case "$key" in
            [A-Za-z_]*) ;;
            *) continue ;;
        esac
        case "$key" in
            *[!A-Za-z0-9_]*) continue ;;
        esac

        # Кавычки вокруг значения — оформление файла, а не часть ключа.
        value="${value%\"}"
        value="${value#\"}"
        value="${value%\'}"
        value="${value#\'}"

        export "$key=$value"
    done < "$ROOT/.env"
fi

# Локальная база из docker-compose. Пароль здесь не секрет: он задан в самом
# compose-файле и годится только для контейнера на этой машине.
export WOLFY_DB_URL="${WOLFY_DB_URL:-postgres://wolfy:wolfy@localhost:5433/wolfy?sslmode=disable}"
export WOLFY_ENV="${WOLFY_ENV:-dev}"
export WOLFY_ADDR="${WOLFY_ADDR:-:8080}"

# Распознавание страницы ходит в Polza AI. В `.env` ключ живёт под своим
# именем, сервису он нужен под своим — переклеиваем, если он есть.
if [ -z "${WOLFY_OCR_KEY:-}" ] && [ -n "${POLZA_AI_KEY:-}" ]; then
    export WOLFY_OCR_KEY="$POLZA_AI_KEY"
fi

# Социальный вход общий с Читавуком, и ключи в `.env` лежат под его именами.
# Переклеиваем их так же, как ключ распознавания, вместо того чтобы завести в
# файле вторую копию под именем Wolfy: две копии одного секрета живут ровно до
# первой смены, после которой одна из них тихо устаревает.
if [ -z "${WOLFY_GOOGLE_WEB_CLIENT_ID:-}" ] && [ -n "${GOOGLE_CLIENT_ID_WEB:-}" ]; then
    export WOLFY_GOOGLE_WEB_CLIENT_ID="$GOOGLE_CLIENT_ID_WEB"
fi
if [ -z "${WOLFY_GOOGLE_CLIENT_ID:-}" ] && [ -n "${GOOGLE_CLIENT_ID_DESKTOP:-}" ]; then
    export WOLFY_GOOGLE_CLIENT_ID="$GOOGLE_CLIENT_ID_DESKTOP"
fi
if [ -z "${WOLFY_GOOGLE_CLIENT_SECRET:-}" ] && [ -n "${GOOGLE_CLIENT_SECRET_DESKTOP:-}" ]; then
    export WOLFY_GOOGLE_CLIENT_SECRET="$GOOGLE_CLIENT_SECRET_DESKTOP"
fi

# Origin SPA в разработке — тот, что поднимает `npm run dev`. Без него сервис
# оставляет только same-origin, и браузер режет вход с 5180 ещё до ответа.
export WOLFY_WEB_ORIGIN="${WOLFY_WEB_ORIGIN:-http://localhost:5180}"
export WOLFY_GOOGLE_CALLBACK_URL="${WOLFY_GOOGLE_CALLBACK_URL:-http://localhost:8080/v1/auth/google/callback}"

# Секрет для шифрования OAuth state генерируем на каждый запуск. Поток входа
# живёт секунды, переживать перезапуск ему незачем, а постоянное значение
# здесь было бы третьим секретом в хозяйстве, который никто не бережёт.
if [ -z "${WOLFY_OAUTH_STATE_SECRET:-}" ]; then
    # Подстановка сама снимает перевод строки, поэтому убрать остаётся только
    # выравнивающие знаки base64.
    WOLFY_OAUTH_STATE_SECRET="$(head -c 32 /dev/urandom | base64 | tr -d '=')"
    export WOLFY_OAUTH_STATE_SECRET
fi

# Яндекс намеренно остаётся выключенным: web-возврат требует доверенного
# returnUrl на стороне Читавука (docs/ops/citavuk-wolfy-oauth.patch), и без
# него кнопка увела бы читателя на страницу, с которой он не вернётся.

cd "$ROOT/server"
exec go run ./cmd/server
