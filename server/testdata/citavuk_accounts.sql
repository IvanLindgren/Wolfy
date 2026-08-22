-- Таблицы аккаунтов Читавука для локальной разработки.
--
-- В продакшене эти таблицы уже существуют и принадлежат Читавуку; Wolfy
-- читает их и никогда не изменяет. Локально их надо чем-то заменить, иначе
-- невозможно проверить вход.
--
-- Определения скопированы из `0001_init.sql` Читавука ровно в той части, что
-- нужна авторизации. Если там что-то поменяется, поменять надо и здесь —
-- поэтому лишних полей тут нет: чем меньше копия, тем реже она расходится с
-- оригиналом.

CREATE TABLE IF NOT EXISTS users (
    id            uuid PRIMARY KEY,
    email         text        NOT NULL UNIQUE,
    password_hash text,
    display_name  text        NOT NULL DEFAULT '',
    sync_rev      bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash   bytea       PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id    text        NOT NULL DEFAULT '',
    device_name  text        NOT NULL DEFAULT '',
    platform     text        NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions (user_id);
