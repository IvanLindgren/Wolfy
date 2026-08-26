-- Лимит Beta-подсказок считается на аккаунт и календарный день UTC.
CREATE TABLE wolfy.ai_daily_usage (
    user_id uuid NOT NULL,
    day     date NOT NULL,
    used    smallint NOT NULL DEFAULT 0 CHECK (used >= 0 AND used <= 10),
    PRIMARY KEY (user_id, day)
);
