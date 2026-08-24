# Деплой Wolfy для агентов

Этот документ — рабочая инструкция для изменения и выпуска production-сайта
`https://wolfy.citavuk.ru`. Источник истины — ветка `master` репозитория Wolfy;
ручная сборка на VDS не является штатным способом выпуска.

## Карта production

- DNS: `wolfy.citavuk.ru` указывает A-записью на `85.137.89.21`.
- Nginx принимает HTTPS, раздаёт SPA из `/opt/wolfy/current/web` и передаёт
  `/v1/` и `/healthz` сервису на `127.0.0.1:8091`.
- systemd unit: `wolfy.service`.
- неизменяемые релизы: `/opt/wolfy/releases/<git-sha>`;
- активный релиз: симлинк `/opt/wolfy/current`;
- секреты и общие данные: `/opt/wolfy/shared/`;
- окружение: `/opt/wolfy/shared/wolfy.env`, владелец `wolfy:wolfy`, режим `600`.

Шаблоны сервера лежат в `deploy/`: `install-server.sh`, `wolfy.service` и
`nginx-wolfy.conf`. Атомарное включение и автоматический откат делает
`deploy/deploy-release.sh`.

## Перед первым выпуском

1. Создать A-запись `wolfy.citavuk.ru -> 85.137.89.21` и дождаться публичного
   разрешения DNS.
2. На VDS от root запустить `deploy/install-server.sh`, затем проверить
   `nginx -t` и `systemctl status wolfy`.
3. Создать `/opt/wolfy/shared/wolfy.env` по `.env.example`; реальные значения
   не добавлять ни в git, ни в логи задачи.
4. Положить словарь в `/opt/wolfy/shared/wolfy_dictionary.tsv` и сжатую копию
   рядом, если её использует раздача словаря.
5. Выпустить сертификат:

   ```bash
   certbot --nginx -d wolfy.citavuk.ru --non-interactive --redirect
   nginx -t
   systemctl reload nginx
   ```

6. Добавить GitHub Actions secrets:

   - `WOLFY_VDS_HOST`;
   - `WOLFY_VDS_USER`;
   - `WOLFY_VDS_SSH_KEY`;
   - `WOLFY_VDS_KNOWN_HOSTS`.

Deploy-пользователю нужны только права загрузить архив в `/tmp` и выполнить
ограниченный production-скрипт/перезапуск `wolfy`; не расширять их без причины.

## Общий вход с Читавуком

Wolfy и Читавук используют одну учётную запись, поэтому социальный вход —
межпроектная настройка.

В окружении Wolfy должны быть:

```dotenv
WOLFY_WEB_ORIGIN=https://wolfy.citavuk.ru
WOLFY_GOOGLE_WEB_CLIENT_ID=<публичный GOOGLE_CLIENT_ID_WEB Читавука>
WOLFY_CITAVUK_YANDEX_WEB_RETURN=true
```

В окружении Читавука `CITAVUK_ALLOWED_ORIGINS` должно включать
`https://wolfy.citavuk.ru`. Читавук должен поддерживать доверенный `returnUrl`
для Яндекс web-flow; для старой установки companion-патч сохранён в
`docs/ops/citavuk-wolfy-oauth.patch`.

В Google Cloud для того же Web client ID добавить точный Authorized JavaScript
origin `https://wolfy.citavuk.ru`. Это origin, не redirect URI. Client secret в
SPA и в `WOLFY_GOOGLE_WEB_CLIENT_ID` не помещать.

Яндекс продолжает возвращать пользователя в callback Читавука; отдельный
callback Wolfy в кабинете Яндекса не нужен. Читавук после проверки провайдера
возвращает одноразовый completion code на доверенный `/auth/return` Wolfy.

После изменения окружения перезапустить соответствующий сервис и проверить, что
публичные healthcheck показывают `google: true` и `yandex: true`.

## Штатный выпуск

1. До коммита локально прогнать проверки, соответствующие workflow:

   ```bash
   cargo test --manifest-path core/Cargo.toml --locked
   cargo check --manifest-path core/Cargo.toml --lib \
     --target wasm32-unknown-unknown --no-default-features --features wasm
   (cd server && go vet ./... && go test -p 1 ./...)
   (cd web && npm ci && npm run core && npm test && npm run build && npm run e2e)
   ```

2. Просмотреть `git diff`, убедиться, что в изменениях нет `.env`, ключей,
   токенов, приватных SSH-файлов и собранных локальных артефактов.
3. Отправить проверенный коммит в `master`. Workflow
   `.github/workflows/ci-deploy.yml` сначала тестирует Rust, Go и web, затем
   собирает единый архив и только после успеха запускает deploy job.
4. Дождаться обеих зелёных jobs. Не обходить красный CI ручной выкладкой.
5. Проверить production:

   ```bash
   curl --fail --silent --show-error https://wolfy.citavuk.ru/ -o /dev/null
   curl --fail --silent --show-error https://wolfy.citavuk.ru/healthz
   ```

6. В браузере проверить `/account`, библиотеку, импорт маленького PDF и открытие
   карточки слова. Для OAuth достаточно убедиться, что фирменная кнопка Google
   отрисовалась, а Яндекс начинает авторизацию и сохраняет origin Wolfy; не
   завершать вход под чужой учётной записью.

## Откат и диагностика

`deploy-release.sh` сам возвращает предыдущий симлинк, если новый процесс не
проходит локальный `/healthz`. При ручной диагностике сначала использовать
только чтение:

```bash
systemctl status wolfy --no-pager
journalctl -u wolfy -n 100 --no-pager
readlink -f /opt/wolfy/current
curl --fail --silent --show-error http://127.0.0.1:8091/healthz
nginx -t
```

Ручной откат допустим только на существующий полный каталог в
`/opt/wolfy/releases/`: атомарно заменить `/opt/wolfy/current`, перезапустить
`wolfy` и повторить локальный и публичный healthcheck. Не удалять предыдущие
релизы и резервные копии в ходе аварийного отката.

