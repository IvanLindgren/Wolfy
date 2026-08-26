//! Что библиотека хранит между запусками.

use crate::srs::Card;
use serde::{Deserialize, Serialize};

/// Где пользователь остановился.
///
/// Глава и доля внутри неё, а не общий процент: процент пришлось бы
/// пересчитывать при каждом изменении разбивки на главы, а глава с долей
/// переживает и смену версии ядра, и перенос книги на другое устройство.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    #[serde(default)]
    pub chapter: i32,
    /// Доля прочитанного внутри главы, от нуля до единицы.
    #[serde(default)]
    pub within_chapter: f32,
    /// Стабильный якорь: индекс блока, с которого начинается видимый экран.
    ///
    /// Доля внутри главы — величина производная от высоты блоков и шрифта:
    /// на другом устройстве или после смены кегля она указывает в другое
    /// место. Блок же — та же структура, по которой читалка раскладывает
    /// текст. Минус единица означает «якоря нет», тогда позиции восстанавливаются
    /// по старой доле (совместимость со старыми записями).
    #[serde(default)]
    pub block_index: i32,
    /// Смещение внутри блока-якоря как доля его прокручиваемой высоты.
    ///
    /// Для короткого абзаца это всегда около нуля; для огромного блока,
    /// выше экрана, позволяет вернуться не только в начало абзаца, но и в
    /// середину.
    #[serde(default)]
    pub block_offset: f32,
    /// Когда книгу открывали в последний раз. Ноль — ни разу.
    #[serde(default)]
    pub opened_at: i64,
}

/// Книга в библиотеке пользователя.
///
/// Хранится отдельно от самого файла книги и переживает его переоткрытие:
/// файл ядро разбирает заново при каждом запуске, а прогресс, колода и полка —
/// это то, что пользователь накопил, и терять его нельзя.
///
/// Идентификатор придумывает устройство, а не сервер, и он обязан быть UUID:
/// книга получает номер до того, как впервые дойдёт до сети, иначе её нельзя
/// добавить в самолёте, а на сервере под этот номер отведена колонка uuid.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryBook {
    /// Устойчивый номер книги: переживает перенос файла и синхронизацию.
    pub id: String,
    /// Файл внутри хранилища приложения.
    ///
    /// Пустой путь — книга известна по синхронизации, но файла на этом
    /// устройстве нет. Такое бывает всегда: сервер хранит, что вы читаете, но
    /// не сами книги — книга пользователя это его файл.
    #[serde(default)]
    pub path: String,
    pub title: String,
    #[serde(default)]
    pub author: Option<String>,
    /// `epub`, `txt`, `pdf` — по нему видно, чем книга была.
    #[serde(default)]
    pub format: String,
    /// Отпечаток содержимого файла.
    ///
    /// По нему один и тот же файл, добавленный на телефоне и на компьютере,
    /// узнаётся как одна книга, а не превращается в две с одинаковым
    /// названием.
    #[serde(default)]
    pub source_key: String,
    /// Когда книгу добавили — по нему библиотека сортируется.
    #[serde(default)]
    pub added_at: i64,
    /// Сколько глав нашло ядро. Ноль значит, что книгу ещё не открывали.
    #[serde(default)]
    pub chapters: i32,
    #[serde(default)]
    pub progress: Progress,
    /// Название полки, на которой стоит книга. `None` — книга не разобрана.
    #[serde(default)]
    pub shelf: Option<String>,

    /// Ревизия сервера, с которой запись согласована. Ноль — ни разу.
    #[serde(default)]
    pub rev: i64,
    /// Изменена на этом устройстве и ещё не отправлена.
    #[serde(default = "yes")]
    pub dirty: bool,
    /// Удалена.
    ///
    /// Запись остаётся с пометкой, а не стирается: стёртую второе устройство
    /// не заметит, и книга там воскреснет.
    #[serde(default)]
    pub deleted: bool,
}

fn yes() -> bool {
    true
}

impl LibraryBook {
    /// Новая книга с номером и названием; остальное добирается по мере чтения.
    pub fn new(id: impl Into<String>, title: impl Into<String>) -> LibraryBook {
        LibraryBook {
            id: id.into(),
            path: String::new(),
            title: title.into(),
            author: None,
            format: String::new(),
            source_key: String::new(),
            added_at: 0,
            chapters: 0,
            progress: Progress::default(),
            shelf: None,
            rev: 0,
            dirty: true,
            deleted: false,
        }
    }

    /// Прочитанная доля от нуля до единицы.
    pub fn fraction(&self) -> f32 {
        if self.chapters <= 0 {
            return 0.0;
        }
        ((self.progress.chapter as f32 + self.progress.within_chapter) / self.chapters as f32)
            .clamp(0.0, 1.0)
    }

    /// Открывали ли книгу хоть раз.
    pub fn started(&self) -> bool {
        self.progress.opened_at > 0
    }

    pub fn finished(&self) -> bool {
        self.fraction() >= 0.999
    }

    /// Можно ли книгу открыть на этом устройстве.
    pub fn readable(&self) -> bool {
        !self.path.trim().is_empty()
    }
}

/// Канонический ID книги по source_key — Variant A §5.
///
/// Rust — единственный владелец бизнес-правил, поэтому именно Rust определяет
/// канонический ID из source_key, а не Kotlin/TS. Один и тот же файл,
/// добавленный офлайн на двух устройствах с разными случайными id (A и B, один
/// HASH), после синхронизации должен сойтись к одному логическому id.
///
/// Выбор: детерминированный UUID v5-ish из source_key (FNV-1a 128 -> UUID).
/// Пустой source_key не канонизируется — это «отпечаток снять не удалось», и
/// склеивать по нему нельзя. Для существующих случайных id нужна миграция
/// old->canonical с перепривязкой cards (см. `crate::library::merge::migrate_to_canonical`).
/// Сервер при этом не дропает unique index и не делает ON CONFLICT DO NOTHING;
/// он хранит unique (user_id, source_key) и при конфликте — canonical-alias
/// обработка в `server/internal/store/sync.go`.
///
/// Альтернатива Variant B — server alias — потребовала бы протокол oldId->canonicalId
/// и атомарную переписку ссылок на клиенте для всех связанных сущностей (cards,
/// annotations, path-local). При детерминированном ID достаточно локальной
/// миграции и серверного merge без расширения протокола, что меньше и безопаснее.
///
/// UUID форматируется как 8-4-4-4-12 hex, версия 5 (0101) и вариант 10xx, чтобы
/// пройти серверный `uuidPattern` и отличаться от случайных v4.
pub fn canonical_book_id(source_key: &str) -> Option<String> {
    if source_key.is_empty() {
        return None;
    }
    // FNV-1a 64-bit x2 -> 128 bit, детерминировано и без зависимостей.
    fn fnv1a_64(data: &[u8], mut hash: u64) -> u64 {
        const PRIME: u64 = 1099511628211;
        for b in data {
            hash ^= *b as u64;
            hash = hash.wrapping_mul(PRIME);
        }
        hash
    }
    let h1 = fnv1a_64(source_key.as_bytes(), 14695981039346656037);
    // второй хеш с другим seed чтобы получить независимые 64 бита
    let h2 = fnv1a_64(source_key.as_bytes(), 1099511628211 ^ 14695981039346656037);
    let mut bytes = [0u8; 16];
    bytes[0..8].copy_from_slice(&h1.to_be_bytes());
    bytes[8..16].copy_from_slice(&h2.to_be_bytes());
    // версия 5
    bytes[6] = (bytes[6] & 0x0f) | 0x50;
    // вариант 10xx
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Some(format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    ))
}

/// Полка: имя, под которым читатель сложил несколько книг вместе.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Shelf {
    pub name: String,
    #[serde(default)]
    pub created_at: i64,
}

/// Всё, что библиотека хранит между запусками.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LibraryState {
    #[serde(default)]
    pub books: Vec<LibraryBook>,
    #[serde(default)]
    pub cards: Vec<Card>,
    #[serde(default)]
    pub shelves: Vec<Shelf>,
    /// Ревизия сервера, по которую состояние согласовано.
    ///
    /// Ноль — синхронизации ещё не было. Именно этот номер уходит на сервер
    /// как «покажи всё, что новее».
    #[serde(default)]
    pub cursor: i64,
    /// Локальный счётчик изменений — по нему видно, что писать на диск.
    #[serde(default)]
    pub revision: i64,
}

impl LibraryState {
    /// Карточки книги, кроме удалённых.
    pub fn deck(&self, book_id: &str) -> Vec<&Card> {
        self.cards
            .iter()
            .filter(|card| card.book_id == book_id && !card.deleted)
            .collect()
    }

    /// Книги, которые видит пользователь.
    pub fn visible(&self) -> Vec<&LibraryBook> {
        self.books.iter().filter(|book| !book.deleted).collect()
    }

    /// Книга по номеру — включая удалённую: синхронизации она ещё нужна.
    pub fn book(&self, id: &str) -> Option<&LibraryBook> {
        self.books.iter().find(|book| book.id == id)
    }

    /// Записи, изменённые на этом устройстве и ещё не отправленные.
    pub fn pending(&self) -> (Vec<&LibraryBook>, Vec<&Card>) {
        (
            self.books.iter().filter(|book| book.dirty).collect(),
            self.cards.iter().filter(|card| card.dirty).collect(),
        )
    }

    /// Отмечает изменение состояния.
    ///
    /// Счётчик растёт при любой правке и нужен ровно для одного: отличить
    /// «библиотека не менялась, пока шёл запрос» от «менялась». См.
    /// [`super::merge::apply_server`].
    pub fn touched(&self) -> LibraryState {
        LibraryState {
            revision: self.revision + 1,
            ..self.clone()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn доля_прочитанного_не_выходит_за_единицу() {
        let mut book = LibraryBook::new("1", "Гэтсби");
        assert_eq!(book.fraction(), 0.0, "книгу не открывали, а доля не ноль");

        book.chapters = 4;
        book.progress.chapter = 2;
        book.progress.within_chapter = 0.5;
        assert!((book.fraction() - 0.625).abs() < 1e-6);

        // Ядро пересчитало главы и нашло меньше, чем было: доля не должна
        // вылезти за единицу и показать «107 процентов».
        book.chapters = 2;
        assert_eq!(book.fraction(), 1.0);
        assert!(book.finished());
    }

    #[test]
    fn книга_без_файла_читается_только_на_другом_устройстве() {
        let mut book = LibraryBook::new("1", "Гэтсби");
        assert!(!book.readable(), "книга без пути объявлена читаемой");
        book.path = "books/gatsby.epub".to_string();
        assert!(book.readable());
    }

    #[test]
    fn удалённое_остаётся_в_состоянии_но_не_на_виду() {
        let mut удалённая = LibraryBook::new("2", "Ушла");
        удалённая.deleted = true;
        let state = LibraryState {
            books: vec![LibraryBook::new("1", "Осталась"), удалённая],
            ..Default::default()
        };

        assert_eq!(state.visible().len(), 1);
        // Но синхронизации она по-прежнему видна: иначе второе устройство не
        // узнает, что книгу удалили, и вернёт её обратно.
        assert!(state.book("2").is_some());
        assert_eq!(state.books.len(), 2);
    }

    #[test]
    fn колода_книги_не_берёт_чужих_и_удалённых() {
        let mut своя = Card::new("c1", "book", "book");
        своя.book_id = "1".to_string();
        let mut чужая = Card::new("c2", "shelf", "shelf");
        чужая.book_id = "2".to_string();
        let mut выброшенная = Card::new("c3", "dusk", "dusk");
        выброшенная.book_id = "1".to_string();
        выброшенная.deleted = true;

        let state = LibraryState {
            cards: vec![своя, чужая, выброшенная],
            ..Default::default()
        };
        let колода = state.deck("1");
        assert_eq!(колода.len(), 1);
        assert_eq!(колода[0].id, "c1");
    }

    #[test]
    fn читается_библиотека_записанная_клиентом() {
        let сохранённое = r#"{
            "books": [{
                "id": "3f1c2b4a-0000-4000-8000-000000000001",
                "path": "books/gatsby.epub",
                "title": "The Great Gatsby",
                "author": "F. Scott Fitzgerald",
                "format": "epub",
                "sourceKey": "abc",
                "addedAt": 1700000000000,
                "chapters": 9,
                "progress": {"chapter": 2, "withinChapter": 0.5, "openedAt": 1700000001000},
                "shelf": "Классика",
                "rev": 7,
                "dirty": false,
                "deleted": false
            }],
            "cards": [],
            "shelves": [{"name": "Классика", "createdAt": 1700000000000}],
            "cursor": 7,
            "revision": 12
        }"#;
        let state: LibraryState =
            serde_json::from_str(сохранённое).expect("библиотека клиента не читается");

        assert_eq!(state.books.len(), 1);
        assert_eq!(state.books[0].chapters, 9);
        assert_eq!(state.books[0].shelf.as_deref(), Some("Классика"));
        assert_eq!(state.books[0].progress.chapter, 2);
        assert_eq!(state.cursor, 7);

        // И обратно — теми же именами.
        let json = serde_json::to_string(&state).expect("библиотека не пишется");
        assert!(json.contains("\"sourceKey\""), "поле переименовалось");
        assert!(json.contains("\"withinChapter\""), "поле переименовалось");
    }
}

#[cfg(test)]
mod canonical_tests {
    use super::canonical_book_id;

    /// Эталон, общий с сервером.
    ///
    /// `canonical_book_id` и `canonicalBookID` в `server/internal/store/sync.go`
    /// обязаны давать один и тот же номер: сервер по нему решает, какая из двух
    /// строк с одним `source_key` каноническая. Разойдутся — вернётся вечное
    /// перекидывание книги между устройствами, ради которого §5 и затевалась.
    #[test]
    fn canonical_совпадает_с_серверным_эталоном() {
        let ожидание = [
            ("abc123", "62cca241-2f0a-5f65-9ec5-73768c755796"),
            ("hash123", "87acf686-b595-5a9f-916c-695c49355d5e"),
            ("same-hash-sync", "6f258824-9163-55be-aae4-aa460c08006d"),
            ("deadbeefcafe123", "e06a9c5b-f253-52d8-bbf7-e99284ce4ac1"),
        ];
        for (ключ, номер) in ожидание {
            assert_eq!(
                canonical_book_id(ключ).as_deref(),
                Some(номер),
                "ключ {ключ}"
            );
        }
    }
}
