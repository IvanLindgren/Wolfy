-- Персональная лента материалов. Модель названа content, а не books:
-- следующие источники (статьи, журналы) используют те же профиль и реакции.
CREATE TABLE wolfy.discovery_profiles (
    user_id             uuid PRIMARY KEY,
    english_level       text        NOT NULL DEFAULT '',
    genres              jsonb       NOT NULL DEFAULT '[]'::jsonb,
    onboarding_complete boolean     NOT NULL DEFAULT false,
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wolfy.discovery_reactions (
    user_id    uuid        NOT NULL,
    item_id    text        NOT NULL,
    content_type text      NOT NULL DEFAULT 'book',
    liked      boolean     NOT NULL DEFAULT false,
    added      boolean     NOT NULL DEFAULT false,
    embedding  jsonb       NOT NULL DEFAULT '[]'::jsonb,
    genres     jsonb       NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);
CREATE INDEX discovery_reactions_user_updated_idx
    ON wolfy.discovery_reactions (user_id, updated_at DESC);
