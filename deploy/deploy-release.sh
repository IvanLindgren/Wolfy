#!/usr/bin/env bash
# Атомарно включает уже проверенный GitHub Actions релиз на production-VDS.

set -euo pipefail

sha="${1:?не передан SHA релиза}"
archive="${2:?не передан архив релиза}"
root=/opt/wolfy
releases="$root/releases"
release="$releases/$sha"
staging="$releases/.$sha.new"
current="$root/current"
next="$root/current.new"

if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "некорректный SHA релиза" >&2
    exit 2
fi
if [[ "$archive" != "/tmp/wolfy-$sha.tar.gz" || ! -f "$archive" ]]; then
    echo "архив релиза не найден в ожидаемом месте" >&2
    exit 2
fi

# Не распаковываем архив, способный выйти из каталога релиза.
while IFS= read -r entry; do
    case "/$entry/" in
        */../*|//* )
            echo "небезопасный путь в архиве: $entry" >&2
            exit 2
            ;;
    esac
done < <(tar -tzf "$archive")

install -d -m 755 "$releases"
rm -rf -- "$staging"
install -d -m 755 "$staging"
tar -xzf "$archive" -C "$staging"
rm -f -- "$archive"

test -x "$staging/server/wolfy-server"
test -f "$staging/web/index.html"
# Пакет приходит из той же проверенной GitHub Actions сборки, что и сервер.
# Публикуем его до переключения current: клиент либо видит старую цель, либо
# уже полностью готовый новый APK, но никогда недокачанный файл.
if compgen -G "$staging/apps/Wolfy-*.apk" > /dev/null; then
    install -d -o wolfy -g wolfy -m 750 "$root/shared/releases"
    for package in "$staging"/apps/Wolfy-*.apk; do
        install -o wolfy -g wolfy -m 640 "$package" "$root/shared/releases/$(basename "$package")"
    done
fi
find "$staging" -type d -exec chmod 755 {} +
find "$staging" -type f -exec chmod 644 {} +
chmod 755 "$staging/server/wolfy-server"
chown -R root:root "$staging"

if [[ -e "$release" ]]; then
    rm -rf -- "$release"
fi
mv "$staging" "$release"

previous=""
if [[ -L "$current" ]]; then
    previous="$(readlink -f "$current" || true)"
fi

ln -s "$release" "$next"
mv -Tf "$next" "$current"
systemctl restart wolfy

healthy=0
for _ in {1..20}; do
    if curl -fsS -m 3 http://127.0.0.1:8091/healthz >/dev/null; then
        healthy=1
        break
    fi
    sleep 1
done

if [[ "$healthy" != 1 ]]; then
    echo "новый релиз не прошёл healthcheck, возвращаю предыдущий" >&2
    journalctl -u wolfy -n 40 --no-pager >&2 || true
    if [[ -n "$previous" && -d "$previous" ]]; then
        ln -s "$previous" "$next"
        mv -Tf "$next" "$current"
        systemctl restart wolfy
    else
        systemctl stop wolfy || true
    fi
    exit 1
fi

systemctl is-active --quiet wolfy
echo "Wolfy $sha активирован"
