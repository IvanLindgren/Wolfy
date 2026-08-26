-- Исследование книги — отдельный, неизменяемый артефакт. Его намеренно нет
-- в wolfy.books: библиотека должна синхронизироваться быстро и без текста.
CREATE TABLE wolfy.research_analyses (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL,
    book_id          uuid NOT NULL,
    source_sha256    text NOT NULL,
    analysis_version text NOT NULL,
    source_protocol  text NOT NULL,
    request_id       uuid NOT NULL,
    status           text NOT NULL,
    source_digest    text,
    source_chars     bigint NOT NULL DEFAULT 0,
    source_words     bigint NOT NULL DEFAULT 0,
    source_chapters  integer NOT NULL DEFAULT 0,
    artifact         jsonb,
    artifact_sha256  text,
    error_code       text,
    reservation_at   timestamptz NOT NULL DEFAULT now(),
    cost_started_at  timestamptz,
    lease_until      timestamptz,
    worker_id        text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT research_status CHECK (status IN ('awaiting_source','queued','analyzing_chunks','synthesizing','checking_spoilers','validating','ready','failed','cancelled')),
    CONSTRAINT research_source_sha CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT research_source_counts CHECK (source_chars >= 0 AND source_words >= 0 AND source_chapters >= 0),
    UNIQUE (user_id, book_id, source_sha256, analysis_version),
    UNIQUE (user_id, request_id)
);

CREATE INDEX research_analyses_queue_idx
    ON wolfy.research_analyses (status, lease_until, updated_at);
CREATE INDEX research_analyses_user_book_idx
    ON wolfy.research_analyses (user_id, book_id, updated_at DESC);

CREATE TABLE wolfy.research_source_chunks (
    analysis_id uuid NOT NULL REFERENCES wolfy.research_analyses(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    sha256      text NOT NULL,
    byte_count  integer NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (analysis_id, chunk_index),
    CONSTRAINT research_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT research_chunk_bytes CHECK (byte_count > 0 AND byte_count <= 1048576),
    CONSTRAINT research_chunk_sha CHECK (sha256 ~ '^[a-f0-9]{64}$')
);

-- Заряд появляется ровно перед первым вызовом провайдера. Уникальность
-- source_sha256 делает повтор готового исследования и повтор job бесплатным.
CREATE TABLE wolfy.research_quota_charges (
    user_id       uuid NOT NULL,
    source_sha256 text NOT NULL,
    analysis_id   uuid NOT NULL REFERENCES wolfy.research_analyses(id) ON DELETE CASCADE,
    charged_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_sha256),
    CONSTRAINT research_charge_sha CHECK (source_sha256 ~ '^[a-f0-9]{64}$')
);
CREATE INDEX research_quota_recent_idx
    ON wolfy.research_quota_charges (user_id, charged_at DESC);

-- Резерв не является списанием: он защищает недельный лимит, пока клиент
-- догружает источник, и автоматически протухает после обрыва сети.
CREATE TABLE wolfy.research_quota_reservations (
    analysis_id uuid PRIMARY KEY REFERENCES wolfy.research_analyses(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX research_reservations_active_idx
    ON wolfy.research_quota_reservations (user_id, expires_at);

-- Состояние чтения живёт отдельно от артефакта. rev/writer нужны клиенту для
-- LWW, revealed_through сервер мерджит max-ом, чтобы спойлер не «закрылся».
CREATE TABLE wolfy.research_user_state (
    user_id          uuid NOT NULL,
    analysis_id      uuid NOT NULL REFERENCES wolfy.research_analyses(id) ON DELETE CASCADE,
    rev              bigint NOT NULL DEFAULT 0,
    writer           text NOT NULL DEFAULT '',
    active_card_id   text,
    dispositions     jsonb NOT NULL DEFAULT '{}'::jsonb,
    revealed_through integer NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, analysis_id),
    CONSTRAINT research_state_rev CHECK (rev >= 0),
    CONSTRAINT research_revealed CHECK (revealed_through >= 0)
);
