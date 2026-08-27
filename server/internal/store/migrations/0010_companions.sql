-- Профиль книжного компаньона: одна запись на пользователя.
--
-- Профиль и набор реплик лежат в jsonb, а не в колонках: схема внешности
-- меняется вместе с паком ассетов, и перегонять старые устройства через
-- миграцию ради нового слота шляпы не стоит. Сервер доверяет этому JSON
-- только после проверки: размер ограничен, структура валидируется в library.
CREATE TABLE IF NOT EXISTS wolfy.companions (
    user_id uuid PRIMARY KEY REFERENCES wolfy.users(id) ON DELETE CASCADE,
    companion_id uuid NOT NULL,
    profile jsonb NOT NULL,
    phrase_pack jsonb,
    profile_hash text NOT NULL DEFAULT '',
    rev bigint NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
