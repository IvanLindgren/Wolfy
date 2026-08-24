# Production-деплой Wolfy

Полная пошаговая инструкция для агентов, включая связку OAuth с Читавуком,
проверки и откат: [`docs/agents/deploy.md`](../docs/agents/deploy.md).

`master` выкладывается GitHub Actions только после успешных тестов Rust, Go и
React. Workflow собирает один архив, загружает его на VDS и атомарно меняет
`/opt/wolfy/current`. Если `/healthz` нового процесса не отвечает, скрипт
возвращает предыдущий релиз.

## Одноразовая подготовка VDS

1. Запустить `deploy/install-server.sh` от root.
2. Создать `/opt/wolfy/shared/wolfy.env` с правами `600`, владельцем
   `wolfy:wolfy`. Обязательные production-переменные перечислены в
   `.env.example`; сервис слушает только `127.0.0.1:8091`.
3. Положить словарь в `/opt/wolfy/shared/wolfy_dictionary.tsv` и сжатую копию
   рядом. Пакеты приложений, если они публикуются, лежат в
   `/opt/wolfy/shared/releases`.
4. Выпустить сертификат:

   ```bash
   certbot --nginx -d wolfy.citavuk.ru --non-interactive --redirect
   nginx -t && systemctl reload nginx
   ```

## GitHub secrets

- `WOLFY_VDS_HOST` — адрес VDS;
- `WOLFY_VDS_USER` — пользователь с правом управлять `wolfy.service`;
- `WOLFY_VDS_SSH_KEY` — закрытый deploy-ключ;
- `WOLFY_VDS_KNOWN_HOSTS` — заранее проверенная строка host key.

Секреты приложения остаются только на сервере и никогда не передаются в
GitHub Actions или production-архив.
