-- Набор реплик должен переживать перезапуск сервера и не генерироваться
-- дважды при двух одновременных нажатиях с одного характера.
CREATE TABLE IF NOT EXISTS wolfy.companion_phrase_packs (
    user_id uuid NOT NULL REFERENCES wolfy.users(id) ON DELETE CASCADE,
    profile_hash text NOT NULL,
    phrase_pack jsonb,
    status text NOT NULL CHECK (status IN ('generating', 'ready')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, profile_hash)
);

CREATE INDEX IF NOT EXISTS companion_phrase_packs_updated_idx
    ON wolfy.companion_phrase_packs (updated_at);
