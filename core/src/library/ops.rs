//! Операции над библиотекой.
//!
//! Каждая — чистый переход: состояние на входе, состояние на выходе. Работы с
//! файлами здесь нет вовсе; там, где она нужна, ядро отвечает на вопрос «что
//! делать», а делает клиент. Так дедупликация книг, воскрешение карточек и
//! каскад удаления проверяются тестом без единого файла на диске — и
//! одинаково работают на обеих платформах.
//!
//! «Сейчас» и новые номера приходят параметрами по той же причине: своих
//! часов и своей случайности у ядра нет.

use super::book::{LibraryBook, LibraryState, Progress, Shelf};
use crate::srs::Card;

/// Вид карточки-фразы.
pub const PHRASE: &str = "phrase";

/// Вид карточки-правила.
pub const RULE: &str = "rule";

/// Разделитель между снятыми страницами.
///
/// Пустая строка: для ядра граница абзаца это и граница предложения, и без
/// неё последняя фраза страницы слиплась бы с первой фразой следующей — а
/// вместе с ней уехала бы в контекст перевода.
pub const PAGE_BREAK: &str = "\n\n";

/// Книга, к которой стоит вернуться.
///
/// Последняя открытая и не дочитанная. Дочитанную предлагать бессмысленно —
/// читатель уже закрыл её, — а не начатую предлагать рано: «книга дня» это
/// продолжение, а не выбор. Книга без файла тоже не годится: предложить
/// продолжить и не суметь открыть хуже, чем не предлагать.
pub fn continue_reading(state: &LibraryState) -> Option<&LibraryBook> {
    state
        .books
        .iter()
        .filter(|book| !book.deleted && book.started() && !book.finished() && book.readable())
        .max_by_key(|book| book.progress.opened_at)
}

/// Что делать с книгой, которую читатель добавляет.
///
/// Решение отделено от действия, потому что действие — копирование файла в
/// хранилище приложения — стоит дорого и на некоторых путях не нужно вовсе.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AddPlan {
    /// Такая книга уже есть и открывается: копировать файл незачем.
    Known(String),
    /// Книга есть, но без файла — приехала синхронизацией. Нужен только файл.
    Attach(String),
    /// Книга была удалена, но читатель добавляет тот же файл заново.
    ///
    /// Третье действие, не совпадение с Attach: удаление — отдельное
    /// пользовательское решение, и устаревшая копия снимать его не должна, но
    /// явное повторное добавление обязано вернуть книгу к жизни на том же
    /// номере — с той же колодой, прогрессом и заметками.
    Revive(String),
    /// Книги нет: копировать файл и заводить запись.
    Fresh,
}

/// Решает, заводить ли книгу заново.
///
/// Если книга с таким же содержимым уже есть — например, приехала
/// синхронизацией с другого устройства, — она не заводится второй раз, а
/// получает файл. Иначе библиотека, синхронизированная между телефоном и
/// компьютером, удваивалась бы при первом же переносе файлов.
///
/// Пустой отпечаток не совпадает ни с чем: это не «книга без содержимого», а
/// «отпечаток снять не удалось», и склеивать по нему разные книги нельзя.
pub fn plan_add(state: &LibraryState, fingerprint: &str) -> AddPlan {
    if fingerprint.is_empty() {
        return AddPlan::Fresh;
    }
    match state
        .books
        .iter()
        .find(|book| book.source_key == fingerprint)
    {
        Some(book) if book.deleted => AddPlan::Revive(book.id.clone()),
        Some(book) if book.readable() => AddPlan::Known(book.id.clone()),
        Some(book) => AddPlan::Attach(book.id.clone()),
        None => AddPlan::Fresh,
    }
}

/// Возвращает удалённую книгу к жизни: тот же номер, та же колода, новый файл.
///
/// Отличается от простого копирования записи тем, что долг повторного
/// добавления снимается осознанно. Отправить «снова живую» книгу может только
/// этот путь: устаревшая копия, не видевшая удаления, так подделать
/// воскрешение не может — сервер отвергает её по ревизии.
pub fn revive_book(state: &LibraryState, id: &str, path: &str, fingerprint: &str) -> LibraryState {
    let Some(at) = state
        .books
        .iter()
        .position(|book| book.id == id && book.deleted)
    else {
        return state.clone();
    };
    let mut books = state.books.clone();
    books[at] = LibraryBook {
        deleted: false,
        path: path.to_string(),
        source_key: if fingerprint.is_empty() {
            books[at].source_key.clone()
        } else {
            fingerprint.to_string()
        },
        dirty: true,
        ..books[at].clone()
    };
    LibraryState {
        books,
        ..state.clone()
    }
    .touched()
}

/// Кладёт в библиотеку готовую запись книги.
///
/// Если у книги есть `source_key`, её id заменяется на детерминированный
/// canonical id (§5 Variant A): один и тот же файл на двух офлайн-устройствах
/// получает один id, и серверный unique (user_id, source_key) не ловит конфликт.
/// Kotlin/TS по-прежнему шлют случайный id, но Rust его переопределяет.
/// Пустой source_key не канонизируется — это «отпечаток снять не удалось».
pub fn add_book(state: &LibraryState, book: LibraryBook) -> LibraryState {
    let mut book = book;
    if let Some(canonical) = super::book::canonical_book_id(&book.source_key) {
        book.id = canonical;
    }
    // Если такая книга уже есть (гонка plan_add vs фактическая вставка, или
    // повторный add после синхронизации), не заводим дубликат — считаем attach.
    if state.books.iter().any(|b| b.id == book.id) {
        return state.clone();
    }
    let mut books = state.books.clone();
    books.push(book);
    LibraryState {
        books,
        ..state.clone()
    }
    .touched()
}

/// Привязывает файл к книге, приехавшей по синхронизации.
///
/// Сервер знает, что вы читаете «Гэтсби» и на какой вы главе, но самого файла
/// у него нет. Поэтому на втором устройстве книга сначала появляется без
/// файла, а читатель показывает, где он его держит.
pub fn attach_file(state: &LibraryState, id: &str, path: &str, fingerprint: &str) -> LibraryState {
    edit_book(state, id, |book| LibraryBook {
        path: path.to_string(),
        source_key: if fingerprint.is_empty() {
            book.source_key.clone()
        } else {
            fingerprint.to_string()
        },
        ..book.clone()
    })
}

/// Запоминает, что ядро нашло в книге при открытии.
pub fn describe(
    state: &LibraryState,
    id: &str,
    title: &str,
    author: Option<&str>,
    chapters: i32,
) -> LibraryState {
    edit_book(state, id, |book| LibraryBook {
        // Название из файла точнее того, что видно по имени файла, но пустое
        // название хуже имени файла — оставляем прежнее.
        title: if title.trim().is_empty() {
            book.title.clone()
        } else {
            title.to_string()
        },
        author: author.map(str::to_string).or_else(|| book.author.clone()),
        chapters,
        ..book.clone()
    })
}

/// Запоминает, где читатель остановился.
///
/// Кроме доли внутри главы сохраняется стабильный якорь «блок + смещение
/// внутри блока»: доля зависит от высоты блоков и кегля, а блок — та же
/// структура, по которой читалка раскладывает страницу. Так позиция
/// возвращается точнее на другом устройстве и после смены шрифта.
pub fn remember_progress(
    state: &LibraryState,
    id: &str,
    chapter: i32,
    within_chapter: f32,
    block_index: i32,
    block_offset: f32,
    now: i64,
) -> LibraryState {
    edit_book(state, id, |book| LibraryBook {
        progress: Progress {
            chapter,
            within_chapter: within_chapter.clamp(0.0, 1.0),
            block_index: block_index.max(-1),
            block_offset: block_offset.clamp(0.0, 1.0),
            opened_at: now,
        },
        ..book.clone()
    })
}

/// Как собрать текст снимков со страниц.
///
/// Отдельная функция ради одного правила — пустой строки между страницами.
/// Оно легко теряется при переписывании, а цена потери не видна глазом:
/// фразы слипаются, и в карточку уезжает чужой контекст.
pub fn appended_page(before: &str, page: &str) -> String {
    let before = before.trim_end();
    let page = page.trim();
    if before.is_empty() {
        return page.to_string();
    }
    format!("{before}{PAGE_BREAK}{page}")
}

/// Кладёт слово в колоду книги.
///
/// Повторное сохранение того же слова из той же книги ничего не меняет:
/// колода не должна забиваться одним словом в разных формах.
///
/// Удалённая карточка возвращается той же записью, а не заводится новой:
/// новая приехала бы на второе устройство рядом со старой.
#[allow(clippy::too_many_arguments)]
pub fn save_word(
    state: &LibraryState,
    book_id: &str,
    surface: &str,
    lemma: &str,
    translation: &str,
    context: &str,
    pos: &str,
    cefr: &str,
    fresh_id: &str,
    now: i64,
) -> (LibraryState, Card) {
    let existing = state
        .cards
        .iter()
        .position(|card| card.book_id == book_id && card.lemma == lemma);

    if let Some(at) = existing {
        return revived(state, at);
    }

    let card = Card {
        book_id: book_id.to_string(),
        translation: translation.to_string(),
        context: context.to_string(),
        pos: pos.to_string(),
        cefr: cefr.to_string(),
        due_at: now,
        added_at: now,
        ..Card::new(fresh_id, surface, lemma)
    };
    (added_card(state, card.clone()), card)
}

/// Кладёт в колоду фразу целиком.
///
/// Фраза хранится той же карточкой, что слово: у неё те же прочность, срок и
/// серия, и заводить ради неё вторую сущность значит писать вторую
/// синхронизацию, вторую миграцию и второй экран. Отличает её `kind`.
///
/// Ключ — сама фраза: одно и то же предложение из одной книги сохраняется
/// один раз, сколько бы раз читатель по нему ни нажал.
///
/// `None` — фраза пустая, и сохранять нечего.
pub fn save_phrase(
    state: &LibraryState,
    book_id: &str,
    sentence: &str,
    translation: &str,
    fresh_id: &str,
    now: i64,
) -> Option<(LibraryState, Card)> {
    let text = sentence.trim();
    if text.is_empty() {
        return None;
    }

    let existing = state
        .cards
        .iter()
        .position(|card| card.book_id == book_id && card.kind == PHRASE && card.surface == text);

    if let Some(at) = existing {
        return Some(revived(state, at));
    }

    let card = Card {
        book_id: book_id.to_string(),
        kind: PHRASE.to_string(),
        translation: translation.trim().to_string(),
        context: text.to_string(),
        due_at: now,
        added_at: now,
        // Начальная форма у фразы — она сама: искать её не по чему, а пустое
        // поле сломало бы всех, кто по нему показывает карточку.
        ..Card::new(fresh_id, text, text)
    };
    Some((added_card(state, card.clone()), card))
}

/// Карточка правила — заводится в тот момент, когда правило впервые спросили.
///
/// Заранее их не создают: правил под шесть десятков, и завести все разом
/// значило бы отправить на сервер колоду, которой читатель не заказывал, и
/// показать ему шесть десятков «к повторению» в первый же день.
pub fn rule_card(
    state: &LibraryState,
    rule: &str,
    title: &str,
    fresh_id: &str,
    now: i64,
) -> (LibraryState, Card) {
    let existing = state
        .cards
        .iter()
        .position(|card| card.kind == RULE && card.lemma == rule);

    if let Some(at) = existing {
        return revived(state, at);
    }

    let card = Card {
        kind: RULE.to_string(),
        due_at: now,
        added_at: now,
        ..Card::new(fresh_id, title, rule)
    };
    (added_card(state, card.clone()), card)
}

/// Меняет карточку после ответа.
///
/// Само расписание живёт в [`crate::srs::scheduler`] и о библиотеке не знает —
/// оно чистое и потому проверяемое. Библиотека же не знает о расписании: её
/// дело — записать то, что посчитали.
pub fn update_card(
    state: &LibraryState,
    id: &str,
    change: impl FnOnce(&Card) -> Card,
) -> LibraryState {
    let Some(at) = state.cards.iter().position(|card| card.id == id) else {
        return state.clone();
    };
    let mut cards = state.cards.clone();
    cards[at] = Card {
        dirty: true,
        ..change(&cards[at])
    };
    LibraryState {
        cards,
        ..state.clone()
    }
    .touched()
}

/// Убирает слово из колоды книги.
pub fn remove_word(state: &LibraryState, book_id: &str, lemma: &str) -> LibraryState {
    let found = state
        .cards
        .iter()
        .find(|card| card.book_id == book_id && card.lemma == lemma && !card.deleted)
        .map(|card| card.id.clone());

    match found {
        Some(id) => update_card(state, &id, |card| Card {
            deleted: true,
            ..card.clone()
        }),
        None => state.clone(),
    }
}

/// Ставит книгу на полку или снимает с неё.
pub fn move_to_shelf(
    state: &LibraryState,
    id: &str,
    shelf: Option<&str>,
    now: i64,
) -> LibraryState {
    let moved = edit_book(state, id, |book| LibraryBook {
        shelf: shelf.map(str::to_string),
        ..book.clone()
    });

    // Полка, на которую что-то поставили, обязана существовать в списке:
    // иначе она исчезнет, как только с неё снимут последнюю книгу.
    match shelf {
        Some(name) => add_shelf(&moved, name, now).0,
        None => moved,
    }
}

/// Убирает книгу.
///
/// Запись остаётся с пометкой, а не стирается: стёртую второе устройство не
/// заметит, и книга там воскреснет. Путь при этом очищается — файл клиент
/// удаляет сам, и хранить дорогу к тому, чего нет, незачем.
pub fn remove_book(state: &LibraryState, id: &str) -> LibraryState {
    if state.book(id).is_none() {
        return state.clone();
    }

    let books = state
        .books
        .iter()
        .map(|book| {
            if book.id != id {
                return book.clone();
            }
            LibraryBook {
                deleted: true,
                path: String::new(),
                dirty: true,
                ..book.clone()
            }
        })
        .collect();

    // Карточки книги уходят вместе с ней: колода без книги бессмысленна, а на
    // сервере у карточки внешний ключ на книгу.
    let cards = state
        .cards
        .iter()
        .map(|card| {
            if card.book_id != id || card.deleted {
                return card.clone();
            }
            Card {
                deleted: true,
                dirty: true,
                ..card.clone()
            }
        })
        .collect();

    LibraryState {
        books,
        cards,
        ..state.clone()
    }
    .touched()
}

/// Заводит полку. Уже существующая возвращается как есть.
pub fn add_shelf(state: &LibraryState, name: &str, now: i64) -> (LibraryState, Shelf) {
    let trimmed = name.trim();
    if let Some(existing) = state.shelves.iter().find(|shelf| shelf.name == trimmed) {
        return (state.clone(), existing.clone());
    }

    let shelf = Shelf {
        name: trimmed.to_string(),
        created_at: now,
    };
    let mut shelves = state.shelves.clone();
    shelves.push(shelf.clone());
    (
        LibraryState {
            shelves,
            ..state.clone()
        }
        .touched(),
        shelf,
    )
}

/// Убирает полку.
pub fn remove_shelf(state: &LibraryState, name: &str) -> LibraryState {
    let shelves = state
        .shelves
        .iter()
        .filter(|shelf| shelf.name != name)
        .cloned()
        .collect();

    // Книги с удалённой полки не пропадают, а возвращаются к неразобранным:
    // полка это место, а не свойство книги.
    let books = state
        .books
        .iter()
        .map(|book| {
            if book.shelf.as_deref() != Some(name) {
                return book.clone();
            }
            LibraryBook {
                shelf: None,
                dirty: true,
                ..book.clone()
            }
        })
        .collect();

    LibraryState {
        shelves,
        books,
        ..state.clone()
    }
    .touched()
}

// --- внутреннее ---

/// Правка книги: помечает изменённой и двигает счётчик.
fn edit_book(
    state: &LibraryState,
    id: &str,
    change: impl FnOnce(&LibraryBook) -> LibraryBook,
) -> LibraryState {
    let Some(at) = state.books.iter().position(|book| book.id == id) else {
        return state.clone();
    };
    let mut books = state.books.clone();
    books[at] = LibraryBook {
        dirty: true,
        ..change(&books[at])
    };
    LibraryState {
        books,
        ..state.clone()
    }
    .touched()
}

fn added_card(state: &LibraryState, card: Card) -> LibraryState {
    let mut cards = state.cards.clone();
    cards.push(card);
    LibraryState {
        cards,
        ..state.clone()
    }
    .touched()
}

/// Возвращает уже существующую карточку, при надобности воскрешая её.
fn revived(state: &LibraryState, at: usize) -> (LibraryState, Card) {
    if !state.cards[at].deleted {
        return (state.clone(), state.cards[at].clone());
    }
    let id = state.cards[at].id.clone();
    let next = update_card(state, &id, |card| Card {
        deleted: false,
        ..card.clone()
    });
    let card = next.cards[at].clone();
    (next, card)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;

    fn книга(id: &str, title: &str) -> LibraryBook {
        LibraryBook {
            path: format!("books/{id}.epub"),
            added_at: NOW,
            chapters: 10,
            ..LibraryBook::new(id, title)
        }
    }

    fn пустая() -> LibraryState {
        LibraryState::default()
    }

    #[test]
    fn продолжить_предлагают_последнюю_начатую_и_недочитанную() {
        let mut давняя = книга("1", "Давняя");
        давняя.progress = Progress {
            block_index: 0,
            block_offset: 0.0,
            chapter: 1,
            within_chapter: 0.0,
            opened_at: NOW - 10_000,
        };
        let mut свежая = книга("2", "Свежая");
        свежая.progress = Progress {
            block_index: 0,
            block_offset: 0.0,
            chapter: 1,
            within_chapter: 0.0,
            opened_at: NOW,
        };

        let state = LibraryState {
            books: vec![давняя, свежая],
            ..пустая()
        };
        assert_eq!(continue_reading(&state).map(|b| b.id.as_str()), Some("2"));
    }

    #[test]
    fn дочитанную_и_неначатую_продолжать_не_предлагают() {
        let mut дочитана = книга("1", "Дочитана");
        дочитана.progress = Progress {
            block_index: 0,
            block_offset: 0.0,
            chapter: 10,
            within_chapter: 1.0,
            opened_at: NOW,
        };
        let неначата = книга("2", "Неначата");

        let state = LibraryState {
            books: vec![дочитана, неначата],
            ..пустая()
        };
        assert!(continue_reading(&state).is_none());
    }

    #[test]
    fn книгу_без_файла_продолжать_не_предлагают() {
        // Предложить продолжить и не суметь открыть хуже, чем не предлагать.
        let mut без_файла = книга("1", "Приехала синхронизацией");
        без_файла.path = String::new();
        без_файла.progress = Progress {
            block_index: 0,
            block_offset: 0.0,
            chapter: 1,
            within_chapter: 0.0,
            opened_at: NOW,
        };

        let state = LibraryState {
            books: vec![без_файла],
            ..пустая()
        };
        assert!(continue_reading(&state).is_none());
    }

    #[test]
    fn один_файл_не_заводит_две_книги() {
        let mut известная = книга("1", "Гэтсби");
        известная.source_key = "отпечаток".to_string();
        let state = LibraryState {
            books: vec![известная],
            ..пустая()
        };

        assert_eq!(
            plan_add(&state, "отпечаток"),
            AddPlan::Known("1".to_string())
        );
        assert_eq!(plan_add(&state, "другой"), AddPlan::Fresh);
    }

    #[test]
    fn приехавшая_синхронизацией_книга_ждёт_файла() {
        let mut без_файла = книга("1", "Гэтсби");
        без_файла.path = String::new();
        без_файла.source_key = "отпечаток".to_string();
        let state = LibraryState {
            books: vec![без_файла],
            ..пустая()
        };

        assert_eq!(
            plan_add(&state, "отпечаток"),
            AddPlan::Attach("1".to_string())
        );

        let after = attach_file(&state, "1", "books/gatsby.epub", "отпечаток");
        assert!(after.books[0].readable());
        assert!(after.books[0].dirty);
    }

    #[test]
    fn пустой_отпечаток_не_склеивает_разные_книги() {
        // Пустой отпечаток — это «снять не удалось», а не «книга без
        // содержимого»: склеивать по нему нельзя.
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби")],
            ..пустая()
        };
        assert_eq!(plan_add(&state, ""), AddPlan::Fresh);
    }

    #[test]
    fn удалённая_книга_не_воскресает_от_совпадения_отпечатка() {
        // Ошибиться здесь — значит дать устаревшему обновлению права снять
        // tombstone: телефон удалил книгу, ноутбук держит её копию и по
        // встряске приносят тот же файл. Удаление обязано пережить встречу.
        let mut удалённая = книга("1", "Гэтсби");
        удалённая.source_key = "отпечаток".to_string();
        удалённая.deleted = true;
        let state = LibraryState {
            books: vec![удалённая],
            ..пустая()
        };

        // plan_add обязан отделить «добавляю заново осознанно» от
        // «устаревшая копия» — как минимум не вернуть Known/Attach молча.
        assert_eq!(
            plan_add(&state, "отпечаток"),
            AddPlan::Revive("1".to_string()),
            "план добавления не распознал воскрешение"
        );
    }

    #[test]
    fn воскрешение_сохраняет_номер_и_колоду_и_делает_запись_грязной() {
        let mut удалённая = книга("1", "Гэтсби");
        удалённая.source_key = "отпечаток".to_string();
        удалённая.deleted = true;
        удалённая.rev = 20;
        удалённая.dirty = false;
        удалённая.path = String::new();

        let (state, _) = save_word(
            &LibraryState {
                books: vec![удалённая],
                ..пустая()
            },
            "1",
            "libraries",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c1",
            NOW,
        );

        let after = revive_book(&state, "1", "books/gatsby.epub", "отпечаток");
        let book = &after.books[0];
        assert!(!book.deleted, "книга осталась удалённой");
        assert_eq!(book.id, "1", "воскрешение сменило номер — колода осиротела");
        assert_eq!(
            book.path, "books/gatsby.epub",
            "файл не привязался к воскрешённой книге"
        );
        assert!(book.dirty, "воскрешение не попало в отправку");
        // Ревизия — память о tombstone: сервер по равенству принимает
        // воскрешение, а устаревшая живая версия с меньшим номером — нет.
        assert_eq!(book.rev, 20, "воскрешение не сохранило память о tombstone");
        assert!(
            after.cards[0].book_id.as_str() == "1",
            "колода потеряла книгу"
        );
    }

    #[test]
    fn воскрешение_не_трогает_чужую_или_живую_книгу() {
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби")],
            ..пустая()
        };
        let чужая = revive_book(&state, "нет-такой", "books/a.epub", "отпечаток");
        assert_eq!(чужая, state.clone(), "неизвестная книга сдвинула состояние");

        let живая = revive_book(&state, "1", "books/a.epub", "отпечаток");
        assert_eq!(живая, state.clone(), "живая книга пережила «воскрешение»");
    }

    #[test]
    fn пустое_название_из_файла_не_затирает_прежнее() {
        let state = LibraryState {
            books: vec![книга("1", "Имя файла")],
            ..пустая()
        };
        let after = describe(&state, "1", "   ", None, 9);
        assert_eq!(after.books[0].title, "Имя файла");
        assert_eq!(after.books[0].chapters, 9);
    }

    #[test]
    fn прогресс_не_вылезает_за_единицу() {
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби")],
            ..пустая()
        };
        let after = remember_progress(&state, "1", 3, 4.2, -1, 0.0, NOW);
        assert_eq!(after.books[0].progress.within_chapter, 1.0);
        assert_eq!(after.books[0].progress.opened_at, NOW);
        assert!(after.books[0].dirty);
    }

    #[test]
    fn одно_слово_не_кладётся_в_колоду_дважды() {
        let (state, первая) = save_word(
            &пустая(),
            "1",
            "libraries",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c1",
            NOW,
        );
        let (после, вторая) = save_word(
            &state,
            "1",
            "library",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c2",
            NOW,
        );

        assert_eq!(после.cards.len(), 1, "слово легло в колоду дважды");
        assert_eq!(первая.id, вторая.id);
        assert_eq!(после.revision, state.revision, "состояние изменилось зря");
    }

    #[test]
    fn удалённая_карточка_воскресает_той_же_записью() {
        let (state, карточка) = save_word(
            &пустая(),
            "1",
            "library",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c1",
            NOW,
        );
        let убрана = remove_word(&state, "1", "library");
        assert!(убрана.cards[0].deleted);

        let (снова, вернулась) = save_word(
            &убрана,
            "1",
            "library",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c2",
            NOW,
        );
        assert_eq!(снова.cards.len(), 1, "завелась вторая запись");
        // Новая запись приехала бы на второе устройство рядом со старой.
        assert_eq!(вернулась.id, карточка.id);
        assert!(!снова.cards[0].deleted);
    }

    #[test]
    fn пустая_фраза_в_колоду_не_идёт() {
        assert!(save_phrase(&пустая(), "1", "   ", "", "c1", NOW).is_none());
    }

    #[test]
    fn одна_фраза_не_кладётся_дважды() {
        let текст = "I have been reading this book.";
        let (state, первая) =
            save_phrase(&пустая(), "1", текст, "Я читаю", "c1", NOW).expect("фраза не сохранилась");
        let (после, вторая) = save_phrase(&state, "1", &format!("  {текст}  "), "", "c2", NOW)
            .expect("фраза не сохранилась");

        assert_eq!(после.cards.len(), 1);
        assert_eq!(первая.id, вторая.id);
        // Начальная форма у фразы — она сама.
        assert_eq!(первая.lemma, текст);
        assert_eq!(первая.kind, PHRASE);
    }

    #[test]
    fn карточка_правила_заводится_один_раз() {
        let (state, первая) = rule_card(&пустая(), "present-perfect", "Present Perfect", "c1", NOW);
        let (после, вторая) = rule_card(&state, "present-perfect", "Present Perfect", "c2", NOW);

        assert_eq!(после.cards.len(), 1);
        assert_eq!(первая.id, вторая.id);
        assert_eq!(первая.kind, RULE);
        assert_eq!(первая.lemma, "present-perfect");
    }

    #[test]
    fn удаление_книги_уносит_её_колоду() {
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби"), книга("2", "Моби Дик")],
            ..пустая()
        };
        let (state, _) = save_word(
            &state,
            "1",
            "library",
            "library",
            "библиотека",
            "",
            "",
            "",
            "c1",
            NOW,
        );
        let (state, _) = save_word(&state, "2", "whale", "whale", "кит", "", "", "", "c2", NOW);

        let after = remove_book(&state, "1");

        let книга_один = after.book("1").expect("запись книги стёрта");
        assert!(книга_один.deleted, "книга не помечена удалённой");
        assert!(книга_один.path.is_empty(), "путь к стёртому файлу остался");
        // Запись остаётся: стёртую второе устройство не заметит.
        assert_eq!(after.books.len(), 2);

        let своя = after
            .cards
            .iter()
            .find(|c| c.id == "c1")
            .expect("карточка пропала");
        let чужая = after
            .cards
            .iter()
            .find(|c| c.id == "c2")
            .expect("карточка пропала");
        assert!(своя.deleted, "колода осталась без книги");
        assert!(!чужая.deleted, "удаление задело чужую колоду");
    }

    #[test]
    fn полка_заводится_вместе_с_первой_книгой_на_ней() {
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби")],
            ..пустая()
        };
        let after = move_to_shelf(&state, "1", Some("Классика"), NOW);

        assert_eq!(after.books[0].shelf.as_deref(), Some("Классика"));
        // Иначе полка исчезнет, как только с неё снимут последнюю книгу.
        assert_eq!(after.shelves.len(), 1);
        assert_eq!(after.shelves[0].name, "Классика");
    }

    #[test]
    fn полка_не_заводится_дважды() {
        let (state, первая) = add_shelf(&пустая(), "Классика", NOW);
        let (после, вторая) = add_shelf(&state, "  Классика  ", NOW + 1);

        assert_eq!(после.shelves.len(), 1);
        assert_eq!(первая, вторая);
        assert_eq!(после.revision, state.revision, "состояние изменилось зря");
    }

    #[test]
    fn удаление_полки_не_уносит_книги() {
        let state = LibraryState {
            books: vec![книга("1", "Гэтсби")],
            ..пустая()
        };
        let state = move_to_shelf(&state, "1", Some("Классика"), NOW);
        let after = remove_shelf(&state, "Классика");

        assert!(after.shelves.is_empty());
        // Полка это место, а не свойство книги.
        assert_eq!(after.books.len(), 1);
        assert!(after.books[0].shelf.is_none());
        assert!(after.books[0].dirty);
    }

    #[test]
    fn страницы_снимков_разделяются_пустой_строкой() {
        assert_eq!(appended_page("", "Первая"), "Первая");
        assert_eq!(appended_page("Первая", "Вторая"), "Первая\n\nВторая");
        // Хвостовые переводы строк не должны копиться от страницы к странице.
        assert_eq!(
            appended_page("Первая\n\n\n", "  Вторая  "),
            "Первая\n\nВторая"
        );
    }

    #[test]
    fn правка_несуществующей_записи_ничего_не_ломает() {
        let state = пустая();
        assert_eq!(describe(&state, "нет такой", "Название", None, 3), state);
        assert_eq!(remove_book(&state, "нет такой"), state);
        assert_eq!(remove_word(&state, "1", "нет такого"), state);
        assert_eq!(update_card(&state, "нет такой", |c| c.clone()), state);
    }
}
