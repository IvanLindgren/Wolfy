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

cd "$ROOT/server"
exec go run ./cmd/server
