-- Базовая схема Wolfy.
--
-- Всё своё живёт в схеме wolfy. Таблицы Читавука (users, sessions) остаются в
-- public и доступны только на чтение: аккаунт общий, а библиотека английских
-- книг и библиотека сербских уроков — разные вещи и пересекаться не должны.
--
-- Модель синхронизации повторяет читавуковскую и по той же причине: у каждого
-- пользователя монотонный счётчик ревизий, любая изменённая запись получает
-- rev = следующее значение, клиент просит «всё, что новее моего курсора».
-- Счётчик свой, а не общий с Читавуком: продвигая чужой курсор, Wolfy заставил
-- бы клиента Читавука качать пустоту.

CREATE SCHEMA IF NOT EXISTS wolfy;

-- Счётчик ревизий и настройки чтения. Строка заводится при первом обращении
-- пользователя, а не при регистрации: регистрация происходит в Читавуке.
CREATE TABLE wolfy.user_state (
    user_id    uuid PRIMARY KEY,
    sync_rev   bigint      NOT NULL DEFAULT 0,
    -- Настройки читалки: шрифт, тема, интервал, палитра частей речи.
    -- JSON, а не колонки: набор меняется каждую неделю на этапе подбора
    -- оформления, и заводить миграцию под каждый ползунок незачем.
    reading    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Книги библиотеки: только метаданные и прогресс. Сам файл лежит на
-- устройстве, сервер его не хранит — книга пользователя это его файл.
CREATE TABLE wolfy.books (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL,
    title        text        NOT NULL DEFAULT '',
    author       text        NOT NULL DEFAULT '',
    format       text        NOT NULL DEFAULT '',
    -- Логический ключ импорта: позволяет узнать один и тот же файл,
    -- добавленный на телефоне и на компьютере. Считается по содержимому.
    source_key   text        NOT NULL DEFAULT '',
    chapter_count integer    NOT NULL DEFAULT 0,
    -- Позиция чтения: глава и смещение внутри неё в единицах UTF-16.
    last_chapter integer     NOT NULL DEFAULT 0,
    last_offset  integer     NOT NULL DEFAULT 0,
    shelf        text        NOT NULL DEFAULT '',
    -- Место в ручной сортировке библиотеки.
    position     integer     NOT NULL DEFAULT 0,
    rev          bigint      NOT NULL DEFAULT 0,
    -- Удаление помечается, а не стирается: иначе оно не доедет до второго
    -- устройства и книга там воскреснет.
    deleted_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX books_user_rev_idx ON wolfy.books (user_id, rev);
CREATE UNIQUE INDEX books_user_source_idx
    ON wolfy.books (user_id, source_key) WHERE source_key <> '';

-- Слова и фразы, сохранённые из книги. Одна таблица на оба вида: у них
-- одинаковая жизнь в колоде, а отличаются они полем kind и длиной текста.
CREATE TABLE wolfy.cards (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL,
    -- Книга, из которой слово пришло. NULL — слово из общей колоды.
    book_id    uuid        REFERENCES wolfy.books (id) ON DELETE SET NULL,
    -- 'word' или 'phrase'.
    kind       text        NOT NULL DEFAULT 'word',
    -- Слово так, как оно стояло в тексте.
    surface    text        NOT NULL,
    -- Начальная форма: по ней слово узнаётся при следующей встрече.
    lemma      text        NOT NULL DEFAULT '',
    translation text       NOT NULL DEFAULT '',
    -- Предложение, в котором слово встретилось: без него перевод не проверить.
    context    text        NOT NULL DEFAULT '',
    pos        text        NOT NULL DEFAULT '',
    cefr       text        NOT NULL DEFAULT '',

    -- Состояние интервального повторения.
    --
    -- hp — «очки здоровья» карточки из задумки продукта: они падают, когда
    -- слово уверенно узнают, и растут при ошибке. Карточка с нулём здоровья
    -- считается выученной.
    hp         integer     NOT NULL DEFAULT 100,
    -- Сколько раз подряд ответили верно; ноль сбрасывает интервал.
    streak     integer     NOT NULL DEFAULT 0,
    interval_days integer  NOT NULL DEFAULT 0,
    due_at     timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,

    rev        bigint      NOT NULL DEFAULT 0,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cards_user_rev_idx ON wolfy.cards (user_id, rev);
CREATE INDEX cards_user_due_idx ON wolfy.cards (user_id, due_at) WHERE deleted_at IS NULL;
CREATE INDEX cards_book_idx ON wolfy.cards (book_id);
-- Одно и то же слово из одной книги сохраняется один раз: повторное нажатие
-- должно открывать сохранённую карточку, а не плодить дубликаты.
CREATE UNIQUE INDEX cards_user_lemma_book_idx
    ON wolfy.cards (user_id, kind, lemma, COALESCE(book_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE deleted_at IS NULL AND lemma <> '';

-- Кэш контекстных переводов.
--
-- Ключ — хеш от текста, направления и контекста: один и тот же абзац у сотни
-- читателей одной книги переводится один раз. Кэш общий для всех
-- пользователей и намеренно не привязан к user_id: в нём лежит перевод
-- фрагмента книги, а не что-либо личное.
CREATE TABLE wolfy.translations (
    hash        bytea PRIMARY KEY,
    source_lang text        NOT NULL,
    target_lang text        NOT NULL,
    source_text text        NOT NULL,
    translated  text        NOT NULL,
    provider    text        NOT NULL DEFAULT 'deepl',
    hits        bigint      NOT NULL DEFAULT 1,
    created_at  timestamptz NOT NULL DEFAULT now(),
    used_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX translations_used_idx ON wolfy.translations (used_at);
