#!/usr/bin/env bash
# Отправляет production-окружение Wolfy на VDS.
#
#   ./deploy/push-env.sh <user>@<vds> ~/.ssh/wolfy_deploy [файл]
#
# Файл по умолчанию — .env.prod в корне репозитория. Секреты идут напрямую с
# машины разработчика: ни в git, ни в GitHub Actions, ни в архив релиза они не
# попадают (deploy/README.md).
#
# Скрипт проверяет файл ДО отправки. Ошибки в окружении не падают шумно: Google
# отвечает `invalid_client`, Postgres — «не найден хост», и ни один из ответов
# не называет причину. Дешевле поймать их здесь.

set -euo pipefail

target="${1:?не передан адрес: user@vds}"
key="${2:?не передан путь к ssh-ключу}"
source_file="${3:-$(dirname "$0")/../.env.prod}"
key="${key/#\~/$HOME}"

remote_path=/opt/wolfy/shared/wolfy.env
stamp="$(date +%s)"
staging="/tmp/wolfy.env.$stamp"
backup="/opt/wolfy/shared/wolfy.env.bak.$stamp"

ssh_run() { ssh -i "$key" -o BatchMode=yes "$target" "$@"; }

[[ -f "$source_file" ]] || { echo "нет файла $source_file" >&2; exit 2; }
[[ -f "$key" ]] || { echo "нет ssh-ключа $key" >&2; exit 2; }

# --- Проверки файла -------------------------------------------------------

problems=0
note() { echo "  ✗ $*" >&2; problems=$((problems + 1)); }

echo "Проверяю $source_file"

# Windows-переводы строк. EnvironmentFile у systemd сохраняет CR внутри
# значения, и client_id тихо становится длиннее на один невидимый знак —
# ровно тот же симптом, что и лишние кавычки.
# Считаем байты через tr: grep и awk в Git Bash работают в текстовом режиме
# и CR не видят вовсе — проверка молча проходила бы там, где она нужнее всего.
# Escape-последовательность разбирает сам tr. Настоящий байт CR сюда писать
# нельзя дважды: в скрипте его не видно глазами, а MSYS ещё и вычищает его
# из аргументов по дороге, и проверка снова перестаёт срабатывать.
if [[ "$(tr -dc '\r' < "$source_file" | wc -c)" -gt 0 ]]; then
    echo "  · убираю CR в концах строк"
fi

# Нормализованная копия: её и отправляем.
clean="$(mktemp)"
trap 'rm -f -- "$clean"' EXIT
tr -d '\r' < "$source_file" > "$clean"

# Кавычки по краям значения. Сервис их снимает сам (config.go), но systemd
# раскавычивает по своим правилам, и лишний пробел после закрывающей кавычки
# оставляет её в значении.
while IFS= read -r line; do
    case "$line" in
        ''|'#'*) continue ;;
    esac
    value="${line#*=}"
    case "$value" in
        \"*|\'*) note "кавычки в значении: ${line%%=*}" ;;
    esac
done < "$clean"

value_of() { sed -n "s/^$1=//p" "$clean" | tail -n 1; }

for name in WOLFY_GOOGLE_WEB_CLIENT_ID WOLFY_GOOGLE_CLIENT_ID \
            WOLFY_GOOGLE_CLIENT_SECRET WOLFY_GOOGLE_CALLBACK_URL \
            WOLFY_OAUTH_STATE_SECRET WOLFY_DB_URL; do
    [[ -n "$(value_of "$name")" ]] || note "не заполнено: $name"
done

# Клиент один, значит и значение одно. Разошлись — либо опечатка, либо
# заведены два клиента, и тогда Читавук не признает аудиторию ID token.
web_id="$(value_of WOLFY_GOOGLE_WEB_CLIENT_ID)"
app_id="$(value_of WOLFY_GOOGLE_CLIENT_ID)"
if [[ -n "$web_id" && -n "$app_id" && "$web_id" != "$app_id" ]]; then
    note "WOLFY_GOOGLE_WEB_CLIENT_ID и WOLFY_GOOGLE_CLIENT_ID различаются"
fi

# Короче 32 знаков — ключ шифрования state не собирается, и вход через Google
# выключается молча (server/internal/social/google.go).
secret="$(value_of WOLFY_OAUTH_STATE_SECRET)"
if [[ -n "$secret" && ${#secret} -lt 32 ]]; then
    note "WOLFY_OAUTH_STATE_SECRET короче 32 знаков (${#secret})"
fi

callback="$(value_of WOLFY_GOOGLE_CALLBACK_URL)"
if [[ -n "$callback" && "$callback" != https://*/v1/auth/google/callback ]]; then
    note "WOLFY_GOOGLE_CALLBACK_URL не похож на адрес возврата Google: $callback"
fi

if [[ "$(value_of WOLFY_ADDR)" != "127.0.0.1:8091" ]]; then
    note "WOLFY_ADDR должен быть 127.0.0.1:8091 — на него смотрит nginx"
fi

if (( problems > 0 )); then
    echo "Отправка отменена: $problems замечани(е/я)." >&2
    exit 1
fi
echo "  ✓ файл в порядке"

# --- Отправка -------------------------------------------------------------

echo "Отправляю на $target"
scp -i "$key" -o BatchMode=yes -q "$clean" "$target:$staging"

# Прежний файл сохраняем до перезапуска: если сервис не поднимется, вернём его.
# /tmp на этой машине общий — там же ISPmanager и чужие сайты, поэтому копия
# стирается сразу после установки, а не остаётся лежать.
ssh_run "
    set -eu
    sudo test -f '$remote_path' && sudo cp -p '$remote_path' '$backup' || true
    sudo install -o wolfy -g wolfy -m 600 '$staging' '$remote_path'
    shred -u '$staging' 2>/dev/null || rm -f '$staging'
    sudo systemctl restart wolfy
"

# --- Проверка и откат -----------------------------------------------------

echo "Жду сервис"
if ssh_run "
    for _ in \$(seq 1 20); do
        if curl -fsS -m 3 http://127.0.0.1:8091/healthz >/dev/null 2>&1; then exit 0; fi
        sleep 1
    done
    exit 1
"; then
    echo "  ✓ сервис отвечает"
    ssh_run "sudo rm -f '$backup'"
else
    echo "  ✗ сервис не поднялся — возвращаю прежнее окружение" >&2
    ssh_run "
        set -eu
        if sudo test -f '$backup'; then
            sudo install -o wolfy -g wolfy -m 600 '$backup' '$remote_path'
            sudo rm -f '$backup'
            sudo systemctl restart wolfy
        fi
    "
    echo "Причина — в журнале: journalctl -u wolfy -n 50 --no-pager" >&2
    exit 1
fi

# Флаг включается, только когда web client ID задан, state-секрет достаточной
# длины и Читавук доступен. Ради него всё и делалось.
#
# Спрашиваем /healthz, а не /v1/config: второй закрыт входом и отвечает 401
# даже с самой машины, так что проверка всегда сообщала бы «выключен».
echo "Проверяю вход через Google"
if ssh_run "curl -fsS -m 5 http://127.0.0.1:8091/healthz" | grep -q '"google":true'; then
    echo "  ✓ вход через Google включён"
else
    echo "  · вход через Google выключен — сверьте ключи и доступность Читавука" >&2
fi
