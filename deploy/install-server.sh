#!/usr/bin/env bash
# Одноразовая установка изолированного окружения Wolfy на общей VDS.

set -euo pipefail

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    echo "запустите от root" >&2
    exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! id wolfy >/dev/null 2>&1; then
    useradd --system --home-dir /opt/wolfy --shell /usr/sbin/nologin wolfy
fi

install -d -m 755 /opt/wolfy /opt/wolfy/releases
install -d -o wolfy -g wolfy -m 750 /opt/wolfy/shared /opt/wolfy/shared/releases /opt/wolfy/shared/book-files
install -m 644 "$script_dir/wolfy.service" /etc/systemd/system/wolfy.service
install -m 644 "$script_dir/nginx-wolfy.conf" /etc/nginx/conf.d/wolfy.conf

systemctl daemon-reload
systemctl enable wolfy.service
nginx -t
systemctl reload nginx

echo "Окружение установлено. До первого релиза сервис остаётся выключенным."
