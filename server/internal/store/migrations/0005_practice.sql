-- Per-device opaque practice state (§7).
--
-- Go не знает математику days/counters: каждое устройство хранит свой
-- practice_json как непрозрачный blob, сервер отдаёт известные компоненты,
-- а сливает их Rust core (set union + max per device).
--
-- Таблица хранит один blob на пару (пользователь, устройство). Merge живёт
-- только на клиенте: сервер не сравнивает дни и счётчики, а только
-- записывает и возвращает.
--
-- Состояние чтения (wolfy.user_state.reading) остаётся LWW для обычных
-- настроек (theme, fontScale …). Тренировка живёт отдельно, чтобы старый
-- ноутбук, приславший `answers:80`, не откатил `answers:100` с телефона и не
-- стёр сегодняшний день.
--
-- Во время перехода старый клиент всё ещё шлёт reading, в котором могут быть
-- legacy поля trained_on/streak_days/best_streak/answers/right. Сервер
-- продолжает хранить reading как раньше (последняя запись побеждает для
-- предпочтений), а новое состояние тренировки идёт сюда.
CREATE TABLE wolfy.practice_components (
    user_id       uuid        NOT NULL,
    device_id     text        NOT NULL,
    practice_json jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);
CREATE INDEX practice_components_user_updated_idx
    ON wolfy.practice_components (user_id, updated_at);
