-- Файлы книг живут на диске сервиса, а эта таблица хранит только владельца и
-- метаданные. Так обычная синхронизация не тащит EPUB/PDF через JSON.
CREATE TABLE wolfy.book_files (
    user_id    uuid        NOT NULL,
    book_id    uuid        NOT NULL,
    file_name  text        NOT NULL,
    size_bytes bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256     text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, book_id)
);
CREATE INDEX book_files_user_idx ON wolfy.book_files (user_id);
