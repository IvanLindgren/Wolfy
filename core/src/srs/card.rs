//! Карточка повторений.

use serde::{Deserialize, Serialize};

/// Карточка повторений.
///
/// Поля названы так же, как в клиенте на Kotlin, и сериализуются тем же
/// `camelCase`: карточки уже лежат на устройствах и ездят на сервер, и
/// переезд логики в ядро не повод ломать ни файл на диске, ни протокол.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Card {
    pub id: String,
    /// Книга, из которой слово пришло. Пусто — карточка из общей колоды.
    #[serde(default)]
    pub book_id: String,
    /// Что за карточка: `word`, `phrase` или `rule`.
    ///
    /// Три колоды хаба повторений различаются только этим полем. Отдельных
    /// типов нет намеренно: прочность, срок и серия у них одни и те же, а
    /// разные типы заставили бы синхронизацию, хранилище и расписание знать
    /// про каждый.
    #[serde(default = "default_kind")]
    pub kind: String,
    /// Слово так, как оно стояло в тексте.
    pub surface: String,
    /// Начальная форма: по ней слово узнаётся при следующей встрече.
    pub lemma: String,
    #[serde(default)]
    pub translation: String,
    /// Предложение, в котором слово встретилось: без него перевод не проверить.
    #[serde(default)]
    pub context: String,
    #[serde(default)]
    pub pos: String,
    #[serde(default)]
    pub cefr: String,

    /// «Очки здоровья» карточки.
    ///
    /// Падают, когда слово уверенно узнают, и растут при ошибке. Карточка
    /// с нулём считается выученной — так задумана механика повторений.
    #[serde(default = "default_hp")]
    pub hp: i32,
    #[serde(default)]
    pub streak: i32,
    #[serde(default)]
    pub interval_days: i32,
    /// Когда карточку показать снова, в миллисекундах эпохи.
    #[serde(default)]
    pub due_at: i64,
    #[serde(default)]
    pub reviewed_at: i64,
    #[serde(default)]
    pub added_at: i64,

    #[serde(default)]
    pub rev: i64,
    #[serde(default = "default_true")]
    pub dirty: bool,
    #[serde(default)]
    pub deleted: bool,
}

fn default_kind() -> String {
    "word".to_string()
}

fn default_hp() -> i32 {
    super::scheduler::FULL_HP
}

fn default_true() -> bool {
    true
}

impl Card {
    /// Новая карточка со словом: полная прочность, срок не назначен.
    pub fn new(id: impl Into<String>, surface: impl Into<String>, lemma: impl Into<String>) -> Card {
        Card {
            id: id.into(),
            book_id: String::new(),
            kind: default_kind(),
            surface: surface.into(),
            lemma: lemma.into(),
            translation: String::new(),
            context: String::new(),
            pos: String::new(),
            cefr: String::new(),
            hp: super::scheduler::FULL_HP,
            streak: 0,
            interval_days: 0,
            due_at: 0,
            reviewed_at: 0,
            added_at: 0,
            rev: 0,
            dirty: true,
            deleted: false,
        }
    }

    /// Выучена ли: прочность сведена к нулю.
    pub fn learned(&self) -> bool {
        !self.deleted && self.hp <= 0
    }
}
