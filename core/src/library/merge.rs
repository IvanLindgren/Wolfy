//! Слияние библиотеки с ответом сервера.
//!
//! Самая тонкая часть библиотеки и главная причина, по которой она обязана
//! жить в ядре: два устройства одного читателя должны разрешать столкновения
//! одинаково. Две реализации этих правил рано или поздно разойдутся, и
//! разойдутся тихо — прочитанная глава просто исчезнет.

use super::book::{LibraryBook, LibraryState, Shelf};
use crate::srs::Card;
use std::collections::{HashMap, HashSet};

/// Снимок отправки: что ушло на сервер и в каком состоянии была библиотека.
///
/// Нужен, чтобы отличить эхо собственной отправки от чужого изменения. Пока
/// запрос идёт по сети, читатель продолжает читать, и запись успевает
/// измениться ещё раз — принять после этого своё же старое эхо значит
/// потерять то, что он только что сделал.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Sent {
    pub revision: i64,
    pub books: HashSet<String>,
    pub cards: HashSet<String>,
}

impl Sent {
    /// Что отправляем и с какого состояния.
    pub fn of(state: &LibraryState) -> Sent {
        let (books, cards) = state.pending();
        Sent {
            revision: state.revision,
            books: books.iter().map(|book| book.id.clone()).collect(),
            cards: cards.iter().map(|card| card.id.clone()).collect(),
        }
    }

    /// Ничего не отправляли — только спрашивали, что нового.
    pub fn nothing(state: &LibraryState) -> Sent {
        Sent {
            revision: state.revision,
            books: HashSet::new(),
            cards: HashSet::new(),
        }
    }
}

/// Принимает ответ сервера.
///
/// Всё, что было отправлено, приезжает назад с присвоенной ревизией — по ней
/// записи и снимаются с отправки. Свежее чужое просто заменяет местное:
/// побеждает последний записавший, и решение об этом принято на сервере.
///
/// Местная правка, которая ещё не уехала, ответом не затирается. Исключение
/// одно: если библиотека не менялась, пока шёл запрос, и запись была в той же
/// отправке — значит, приехало её собственное эхо, и оно новее.
///
/// Путь к файлу — единственное, что не приходит извне и не затирается: он у
/// каждого устройства свой, а у второго его может не быть вовсе.
pub fn apply_server(
    state: &LibraryState,
    cursor: i64,
    books: &[LibraryBook],
    cards: &[Card],
    sent: &Sent,
    now: i64,
) -> LibraryState {
    // Изменилась ли библиотека, пока шёл запрос. Если да, ответ сервера
    // старше местных правок, и принимать его на них нельзя.
    let quiet = state.revision == sent.revision;

    let mut merged_books = state.books.clone();
    for incoming in books {
        let local = merged_books.iter().position(|book| book.id == incoming.id);

        match local {
            Some(at) => {
                // Ответ старше уже принятого: откатывать состояние назад
                // запрещено даже для чистых записей. Пока запрос шёл по сети,
                // приехал более свежий ответ, и старый должен уйти в никуда.
                if incoming.rev < merged_books[at].rev {
                    continue;
                }
                if merged_books[at].dirty && !(quiet && sent.books.contains(&incoming.id)) {
                    // Местная правка новее ответа: оставляем её ждать отправки.
                    continue;
                }
                merged_books[at] = LibraryBook {
                    path: merged_books[at].path.clone(),
                    dirty: false,
                    ..incoming.clone()
                };
            }
            None => {
                // Новую запись из старого ответа не добавляем: её ревизия уже
                // за курсором не повторится, и мёртвый призрак остался бы
                // навсегда. Вползти сюда может только запись, которой в
                // текущем снимке нет, — то есть выпавшая из более новой версии.
                // Нулевая ревизия — это ещё не отправлявшаяся запись, её
                // сервер не штампует, и отсекать её по курсору нельзя.
                if incoming.rev >= 1 && incoming.rev <= state.cursor {
                    continue;
                }
                merged_books.push(LibraryBook {
                    path: String::new(),
                    dirty: false,
                    ..incoming.clone()
                });
            }
        }
    }

    let mut merged_cards = state.cards.clone();
    for incoming in cards {
        let local = merged_cards.iter().position(|card| card.id == incoming.id);
        match local {
            Some(at) => {
                if incoming.rev < merged_cards[at].rev {
                    continue;
                }
                if merged_cards[at].dirty && !(quiet && sent.cards.contains(&incoming.id)) {
                    continue;
                }
                merged_cards[at] = Card {
                    dirty: false,
                    ..incoming.clone()
                };
            }
            None => {
                if incoming.rev >= 1 && incoming.rev <= state.cursor {
                    continue;
                }
                merged_cards.push(Card {
                    dirty: false,
                    ..incoming.clone()
                });
            }
        }
    }

    // §5 dedup по source_key (§5 Variant A).
    // После слияния по id может остаться дубликат: локальная старая A и
    // приехавшая каноническая C с одним source_key но разными id (офлайн
    // два устройства, старый клиент, или миграция ещё не прошла).
    // Без схлопывания получилось бы две записи одной логической книги.
    {
        use super::book::canonical_book_id;
        // Группировка по каноническому ключу (canonical id) или по самому source_key если каноники нет
        let mut source_to_indices: HashMap<String, Vec<usize>> = HashMap::new();
        for (idx, book) in merged_books.iter().enumerate() {
            if book.source_key.is_empty() {
                continue;
            }
            let key =
                canonical_book_id(&book.source_key).unwrap_or_else(|| book.source_key.clone());
            source_to_indices.entry(key).or_default().push(idx);
        }
        let mut to_remove: HashSet<usize> = HashSet::new();
        let mut book_rebind: HashMap<String, String> = HashMap::new(); // oldId -> survivorId
        for (canonical, indices) in source_to_indices {
            if indices.len() <= 1 {
                continue;
            }
            // Выбор survivor: предпочтём книгу с id == canonical (детерминированная), иначе max rev
            let mut survivor_idx = indices[0];
            for &idx in &indices[1..] {
                let a = &merged_books[survivor_idx];
                let b = &merged_books[idx];
                let a_is_canonical = a.id == canonical;
                let b_is_canonical = b.id == canonical;
                let choose_b = if b_is_canonical && !a_is_canonical {
                    true
                } else if !b_is_canonical && a_is_canonical {
                    false
                } else if b.rev != a.rev {
                    b.rev > a.rev
                } else {
                    b.progress.opened_at > a.progress.opened_at
                };
                if choose_b {
                    survivor_idx = idx;
                }
            }
            let survivor_id = merged_books[survivor_idx].id.clone();
            // Если у survivor нет файла, украсть у дубликата
            let survivor_path_empty = merged_books[survivor_idx].path.trim().is_empty();
            if survivor_path_empty {
                for &idx in &indices {
                    if idx == survivor_idx {
                        continue;
                    }
                    if !merged_books[idx].path.trim().is_empty() {
                        merged_books[survivor_idx].path = merged_books[idx].path.clone();
                        break;
                    }
                }
            }
            for &idx in &indices {
                if idx == survivor_idx {
                    continue;
                }
                let old_id = merged_books[idx].id.clone();
                book_rebind.insert(old_id, survivor_id.clone());
                to_remove.insert(idx);
            }
        }
        if !to_remove.is_empty() {
            let mut new_books = Vec::new();
            for (idx, book) in merged_books.into_iter().enumerate() {
                if !to_remove.contains(&idx) {
                    new_books.push(book);
                }
            }
            merged_books = new_books;
            for card in &mut merged_cards {
                if let Some(new_id) = book_rebind.get(&card.book_id) {
                    card.book_id = new_id.clone();
                    // Перепривязку обязан увидеть сервер: иначе он продолжит
                    // отдавать карточку со старым book_id, и схлопывание
                    // придётся повторять на каждой синхронизации, а другие
                    // устройства так и не сойдутся. `rev` не сбрасываем —
                    // id карточки не менялся, и серверная защита от
                    // воскрешения tombstone сравнивает именно его.
                    card.dirty = true;
                }
            }
            // Дедупликация карточек после перепривязки: один и тот же lemma в одной книге не должен дублироваться
            let mut card_groups: HashMap<(String, String, String), Vec<usize>> = HashMap::new();
            for (i, card) in merged_cards.iter().enumerate() {
                if card.lemma.is_empty() || card.deleted {
                    continue;
                }
                let key = (card.book_id.clone(), card.kind.clone(), card.lemma.clone());
                card_groups.entry(key).or_default().push(i);
            }
            let mut card_remove: HashSet<usize> = HashSet::new();
            for (_, indices) in card_groups {
                if indices.len() <= 1 {
                    continue;
                }
                let mut survivor = indices[0];
                for &idx in &indices[1..] {
                    if merged_cards[idx].rev > merged_cards[survivor].rev {
                        survivor = idx;
                    }
                }
                for &idx in &indices {
                    if idx != survivor {
                        card_remove.insert(idx);
                    }
                }
            }
            if !card_remove.is_empty() {
                let mut new_cards = Vec::new();
                for (i, card) in merged_cards.into_iter().enumerate() {
                    if !card_remove.contains(&i) {
                        new_cards.push(card);
                    }
                }
                merged_cards = new_cards;
            }
        }
    }

    merged_books.sort_by_key(|book| book.added_at);
    merged_cards.sort_by_key(|card| card.added_at);

    // Полки восстанавливаются из книг: своей таблицы у них нет, и приехавшая
    // с другого устройства «Классика» иначе осталась бы без строки в списке.
    let mut shelves = state.shelves.clone();
    let mut known: HashSet<String> = shelves.iter().map(|shelf| shelf.name.clone()).collect();
    for book in &merged_books {
        let Some(name) = &book.shelf else { continue };
        if known.insert(name.clone()) {
            shelves.push(Shelf {
                name: name.clone(),
                created_at: now,
            });
        }
    }

    LibraryState {
        books: merged_books,
        cards: merged_cards,
        shelves,
        // Курсор не откатывается назад: отклик-старик, приехавший после
        // свежего, не должен вернуть глаз на ревизию, по которой он ушёл.
        cursor: state.cursor.max(cursor),
        ..state.clone()
    }
    .touched()
}

/// Приводит прочитанное состояние к нынешнему виду.
///
/// Две поправки:
/// 1. Старые не-UUID номера -> случайные свежие (legacy).
/// 2. Детерминированный канонический ID из source_key (§5 Variant A): один и
///    тот же файл на двух офлайн-устройствах (id=A, id=B, source_key=HASH)
///    обязан сойтись к одному логическому id = canonical(source_key).
///
/// Новые номера для (1) приходят снаружи: своего RNG у ядра нет.
/// Для (2) RNG не нужен — id детерминирован.
/// Если нужных свежих номеров не хватит, книга остаётся и попробует переехать
/// в следующий раз. Каноническая миграция идемпотентна.
pub fn migrate(state: &LibraryState, fresh_ids: &mut impl Iterator<Item = String>) -> LibraryState {
    let after_uuid = migrate_uuid(state, fresh_ids);
    migrate_to_canonical(&after_uuid)
}

fn migrate_uuid(
    state: &LibraryState,
    fresh_ids: &mut impl Iterator<Item = String>,
) -> LibraryState {
    let mut renamed: Vec<(String, String)> = Vec::new();
    let books: Vec<LibraryBook> = state
        .books
        .iter()
        .map(|book| {
            if looks_like_uuid(&book.id) {
                return book.clone();
            }
            let Some(fresh) = fresh_ids.next() else {
                return book.clone();
            };
            renamed.push((book.id.clone(), fresh.clone()));
            LibraryBook {
                id: fresh,
                rev: 0,
                dirty: true,
                ..book.clone()
            }
        })
        .collect();

    let mut cards_changed = false;
    let cards: Vec<Card> = state
        .cards
        .iter()
        .map(|card| {
            let owner = renamed
                .iter()
                .find(|(old, _)| *old == card.book_id)
                .map(|(_, fresh)| fresh.clone());
            let new_id = if looks_like_uuid(&card.id) {
                None
            } else {
                fresh_ids.next()
            };
            if owner.is_none() && new_id.is_none() {
                return card.clone();
            }
            cards_changed = true;
            Card {
                id: new_id.unwrap_or_else(|| card.id.clone()),
                book_id: owner.unwrap_or_else(|| card.book_id.clone()),
                rev: 0,
                dirty: true,
                ..card.clone()
            }
        })
        .collect();

    if renamed.is_empty() && !cards_changed {
        return state.clone();
    }

    LibraryState {
        books,
        cards,
        ..state.clone()
    }
    .touched()
}

/// Миграция к детерминированному каноническому ID (§5 Variant A).
///
/// Детектирует книги с непустым source_key, где id != canonical(source_key),
/// и перепривязывает их и их карточки к canonical. Дубликаты (две книги с
/// одним source_key, разными id) схлопываются в одну с canonical id, карточки
/// перепривязываются, дубликаты карточек по (book_id, kind, lemma) тоже
/// схлопываются (оставшийся — с максимальным rev).
///
/// Идемпотентна: повторный вызов ничего не меняет.
/// Path книги: если у survivor путь пустой, берётся путь из другого дубликата
/// с непустым путём. Прогресс/полка/название берётся у survivor (max rev).
pub fn migrate_to_canonical(state: &LibraryState) -> LibraryState {
    use super::book::canonical_book_id;
    use std::collections::HashMap;

    // Группировка по canonical id
    let mut groups: HashMap<String, Vec<LibraryBook>> = HashMap::new();
    let mut without_canonical: Vec<LibraryBook> = Vec::new();
    for book in &state.books {
        if let Some(canonical) = canonical_book_id(&book.source_key) {
            groups.entry(canonical).or_default().push(book.clone());
        } else {
            without_canonical.push(book.clone());
        }
    }

    let mut renamed: Vec<(String, String)> = Vec::new(); // old -> canonical
    let mut merged_books: Vec<LibraryBook> = Vec::new();
    let mut needs_migration = false;

    for (canonical, mut books) in groups {
        if books.len() == 1 {
            let book = &books[0];
            if book.id != canonical {
                renamed.push((book.id.clone(), canonical.clone()));
                let mut migrated = book.clone();
                migrated.id = canonical.clone();
                migrated.rev = 0;
                migrated.dirty = true;
                merged_books.push(migrated);
                needs_migration = true;
            } else {
                merged_books.push(book.clone());
            }
        } else {
            // Дубликаты: несколько книг с одним source_key -> схлопываем.
            // Переименовываем только тех, чей id реально меняется: книга,
            // уже стоящая на каноническом номере, не должна тащить свои
            // карточки через перепривязку — иначе им зря сбросят dirty/rev.
            for b in &books {
                if b.id != canonical {
                    renamed.push((b.id.clone(), canonical.clone()));
                }
            }
            needs_migration = true;
            // Выбор survivor: не-удалённые предпочтительнее, затем max rev, затем max opened_at, затем max added_at
            books.sort_by(|a, b| {
                // удалённые в конец
                match (a.deleted, b.deleted) {
                    (false, true) => std::cmp::Ordering::Less,
                    (true, false) => std::cmp::Ordering::Greater,
                    _ => {
                        let ord = b.rev.cmp(&a.rev);
                        if ord != std::cmp::Ordering::Equal {
                            return ord;
                        }
                        let ord = b.progress.opened_at.cmp(&a.progress.opened_at);
                        if ord != std::cmp::Ordering::Equal {
                            return ord;
                        }
                        b.added_at.cmp(&a.added_at)
                    }
                }
            });
            let mut survivor = books.remove(0);
            // Если у survivor нет файла, но у другого есть — взять его
            if survivor.path.trim().is_empty() {
                if let Some(other) = books.iter().find(|b| !b.path.trim().is_empty()) {
                    survivor.path = other.path.clone();
                }
            }
            // Если у survivor пустое название/автор, попробовать взять из других
            if survivor.title.trim().is_empty() {
                if let Some(other) = books.iter().find(|b| !b.title.trim().is_empty()) {
                    survivor.title = other.title.clone();
                }
            }
            if survivor.author.is_none() {
                if let Some(other) = books.iter().find(|b| b.author.is_some()) {
                    survivor.author = other.author.clone();
                }
            }
            // `rev` обнуляется только при смене номера: на сервере это будет
            // новая строка. Книге, уже стоящей на каноническом номере, сброс
            // ревизии сломал бы серверную защиту от воскрешения tombstone.
            if survivor.id != canonical {
                survivor.id = canonical.clone();
                survivor.rev = 0;
            }
            survivor.dirty = true;
            // Если был удалён, но другой дубликат живой — оставить живым (выбрали живого как survivor), иначе survivor уже правильный
            merged_books.push(survivor);
        }
    }

    if !needs_migration {
        return state.clone();
    }

    // Перепривязка карточек к canonical + дедупликация по (book_id, kind, lemma)
    let mut rebound_cards: Vec<Card> = Vec::new();
    for mut card in state.cards.clone() {
        if let Some((_, canonical)) = renamed.iter().find(|(old, _)| *old == card.book_id) {
            // Меняется только владелец. Номер карточки прежний, поэтому `rev`
            // не трогаем: сервер сравнивает именно его, решая, не воскрешает
            // ли устаревшая живая копия удалённую карточку.
            card.book_id = canonical.clone();
            card.dirty = true;
        }
        rebound_cards.push(card);
    }

    // Дедупликация карточек: один и тот же lemma из одной книги (после каноникализации) не должен дублироваться разными id
    let mut card_groups: HashMap<(String, String, String), Vec<Card>> = HashMap::new();
    let mut deduped_cards: Vec<Card> = Vec::new();
    for card in rebound_cards {
        if card.lemma.is_empty() || card.deleted {
            deduped_cards.push(card);
            continue;
        }
        let key = (card.book_id.clone(), card.kind.clone(), card.lemma.clone());
        card_groups.entry(key).or_default().push(card);
    }
    for (_, mut group) in card_groups {
        if group.len() == 1 {
            deduped_cards.push(group.remove(0));
        } else {
            group.sort_by(|a, b| {
                let ord = b.rev.cmp(&a.rev);
                if ord != std::cmp::Ordering::Equal {
                    return ord;
                }
                b.due_at.cmp(&a.due_at)
            });
            deduped_cards.push(group.remove(0));
        }
    }
    deduped_cards.sort_by_key(|c| c.added_at);

    let mut deduped_books = without_canonical;
    deduped_books.extend(merged_books);
    deduped_books.sort_by_key(|b| b.added_at);

    LibraryState {
        books: deduped_books,
        cards: deduped_cards,
        ..state.clone()
    }
    .touched()
}

/// Похож ли номер на UUID: тридцать шесть знаков и четыре дефиса.
///
/// Проверка нарочно поверхностная. Настоящий разбор UUID отверг бы номера,
/// которые сервер принимает, а задача здесь одна — узнать номера, придуманные
/// до синхронизации, и они на UUID не похожи вовсе.
fn looks_like_uuid(id: &str) -> bool {
    id.chars().count() == 36 && id.chars().filter(|ch| *ch == '-').count() == 4
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;
    const UUID: &str = "3f1c2b4a-0000-4000-8000-000000000001";

    fn книга(id: &str, title: &str, added_at: i64) -> LibraryBook {
        LibraryBook {
            added_at,
            ..LibraryBook::new(id, title)
        }
    }

    fn состояние(books: Vec<LibraryBook>) -> LibraryState {
        LibraryState {
            books,
            revision: 5,
            ..Default::default()
        }
    }

    #[test]
    fn чужая_книга_приезжает_и_снимается_с_отправки() {
        let state = состояние(vec![]);
        let приехала = LibraryBook {
            dirty: true,
            rev: 9,
            ..книга(UUID, "Гэтсби", NOW)
        };

        let after = apply_server(&state, 9, &[приехала], &[], &Sent::nothing(&state), NOW);

        assert_eq!(after.books.len(), 1);
        assert!(!after.books[0].dirty, "приехавшая книга ждёт отправки");
        assert_eq!(after.cursor, 9);
        assert_eq!(after.revision, 6, "счётчик изменений не сдвинулся");
    }

    #[test]
    fn путь_к_файлу_не_затирается_ответом() {
        // Путь у каждого устройства свой, а у второго его может не быть вовсе.
        let местная = LibraryBook {
            path: "books/gatsby.epub".to_string(),
            dirty: false,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let state = состояние(vec![местная]);

        let приехала = книга(UUID, "The Great Gatsby", NOW);
        let after = apply_server(&state, 9, &[приехала], &[], &Sent::nothing(&state), NOW);

        assert_eq!(
            after.books[0].title, "The Great Gatsby",
            "название не приехало"
        );
        assert_eq!(
            after.books[0].path, "books/gatsby.epub",
            "путь к файлу затёрт ответом"
        );
    }

    #[test]
    fn местная_правка_не_затирается_чужим_ответом() {
        let местная = LibraryBook {
            dirty: true,
            title: "Правил только что".to_string(),
            ..книга(UUID, "Гэтсби", NOW)
        };
        let state = состояние(vec![местная]);

        // Ответ на запрос, в котором эту книгу не отправляли.
        let приехала = книга(UUID, "Со стороны", NOW);
        let after = apply_server(&state, 9, &[приехала], &[], &Sent::nothing(&state), NOW);

        assert_eq!(after.books[0].title, "Правил только что");
        assert!(after.books[0].dirty, "правка снялась с отправки не уехав");
    }

    #[test]
    fn собственное_эхо_снимает_запись_с_отправки() {
        let местная = LibraryBook {
            dirty: true,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let state = состояние(vec![местная]);
        let sent = Sent::of(&state);

        let приехала = LibraryBook {
            rev: 9,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let after = apply_server(&state, 9, &[приехала], &[], &sent, NOW);

        assert!(
            !after.books[0].dirty,
            "своё же эхо не сняло запись с отправки"
        );
        assert_eq!(after.books[0].rev, 9);
    }

    #[test]
    fn правка_во_время_запроса_переживает_собственное_эхо() {
        // Пока запрос шёл по сети, читатель успел поправить ту же книгу.
        // Принять после этого своё старое эхо значит потерять правку.
        let местная = LibraryBook {
            dirty: true,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let state = состояние(vec![местная]);
        let sent = Sent::of(&state);

        let mut пока_шёл_запрос = state.touched();
        пока_шёл_запрос.books[0].title = "Успел поправить".to_string();

        let приехала = LibraryBook {
            rev: 9,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let after = apply_server(&пока_шёл_запрос, 9, &[приехала], &[], &sent, NOW);

        assert_eq!(after.books[0].title, "Успел поправить", "правка потеряна");
        assert!(after.books[0].dirty, "правка снялась с отправки не уехав");
    }

    #[test]
    fn полка_приезжает_вместе_с_книгой() {
        let state = состояние(vec![]);
        let приехала = LibraryBook {
            shelf: Some("Классика".to_string()),
            ..книга(UUID, "Гэтсби", NOW)
        };

        let after = apply_server(&state, 9, &[приехала], &[], &Sent::nothing(&state), NOW);

        // Своей таблицы у полок нет, и без этого «Классика» осталась бы без
        // строки в списке полок на втором устройстве.
        assert_eq!(after.shelves.len(), 1);
        assert_eq!(after.shelves[0].name, "Классика");
        assert_eq!(after.shelves[0].created_at, NOW);
    }

    #[test]
    fn одна_полка_не_заводится_дважды() {
        let первая = LibraryBook {
            shelf: Some("Классика".to_string()),
            ..книга(UUID, "Гэтсби", NOW)
        };
        let вторая = LibraryBook {
            shelf: Some("Классика".to_string()),
            ..книга("3f1c2b4a-0000-4000-8000-000000000002", "Моби Дик", NOW + 1)
        };

        let state = состояние(vec![]);
        let after = apply_server(
            &state,
            9,
            &[первая, вторая],
            &[],
            &Sent::nothing(&state),
            NOW,
        );
        assert_eq!(
            after.shelves.len(),
            1,
            "полка завелась дважды: {:?}",
            after.shelves
        );
    }

    #[test]
    fn книги_сортируются_по_времени_добавления() {
        let state = состояние(vec![]);
        let поздняя = книга(
            "3f1c2b4a-0000-4000-8000-000000000002",
            "Поздняя",
            NOW + 1000,
        );
        let ранняя = книга(UUID, "Ранняя", NOW);

        let after = apply_server(
            &state,
            9,
            &[поздняя, ранняя],
            &[],
            &Sent::nothing(&state),
            NOW,
        );
        let названия: Vec<&str> = after.books.iter().map(|b| b.title.as_str()).collect();
        assert_eq!(названия, vec!["Ранняя", "Поздняя"]);
    }

    #[test]
    fn карточка_приезжает_и_снимается_с_отправки() {
        let state = состояние(vec![]);
        let mut приехала = Card::new("c1", "library", "library");
        приехала.dirty = true;
        приехала.rev = 9;

        let after = apply_server(&state, 9, &[], &[приехала], &Sent::nothing(&state), NOW);
        assert_eq!(after.cards.len(), 1);
        assert!(!after.cards[0].dirty);
    }

    #[test]
    fn старый_ответ_после_нового_не_откатывает_состояние() {
        // Пока запрос A шёл по сети, приехал ответ B с cursor=20 и записью
        // ревизии 20. Потом приехал запоздалый ответ A с cursor=19. Итог
        // обязан быть одинаково новым: ни курсор назад, ни запись ревизии
        // ниже двадцати.
        let state = состояние(vec![]);

        let свежий = LibraryBook {
            rev: 20,
            ..книга(UUID, "Гэтсби", NOW)
        };
        let после_b = apply_server(&state, 20, &[свежий], &[], &Sent::nothing(&state), NOW);

        let старый = LibraryBook {
            title: "Из старого ответа".to_string(),
            rev: 15,
            ..книга(UUID, "Гэтсби", NOW)
        };
        // Второе применение — другой запрос, с ним ушёл другой снимок отправки.
        let после_a = apply_server(&после_b, 19, &[старый], &[], &Sent::nothing(&после_b), NOW);

        assert_eq!(
            после_a.books[0].rev, 20,
            "старый ответ понизил ревизию записи"
        );
        assert_eq!(
            после_a.books[0].title, "Гэтсби",
            "старый ответ перезаписал новые данные"
        );
        assert_eq!(после_a.cursor, 20, "курсор откатился назад");
    }

    #[test]
    fn старый_ответ_после_нового_не_подкидывает_призрачную_запись() {
        // Позапрошлый ответ A содержал книгу ревизии 8, которой нет в свежем
        // ответе B: например, она была изменена. Кривой порядок ответов не
        // должен вставить старую копию: её ревизия уже за курсором.
        let state = состояние(vec![]);

        let после_b = apply_server(&state, 20, &[], &[], &Sent::nothing(&state), NOW);

        // Ревизия штампованная, иначе это была бы несерверная запись.
        let призрак = LibraryBook {
            rev: 8,
            ..книга("99999999-9999-4999-8999-999999999999", "Призрак", NOW)
        };
        let после_a = apply_server(&после_b, 19, &[призрак], &[], &Sent::nothing(&после_b), NOW);

        assert_eq!(после_a.books.len(), 0, "призрак из старого ответа вклеился");
        assert_eq!(после_a.cursor, 20);
    }

    #[test]
    fn порядок_ответов_не_влияет_на_новизну() {
        // Два запроса ушли с почти одинаковым состоянием; равный финальный
        // результат обязан быть одинаковым в обоих порядках.
        let state = состояние(vec![]);

        let record = |rev: i64| LibraryBook {
            rev,
            ..книга(UUID, "Гэтсби", NOW)
        };

        let a_сначала = apply_server(&state, 19, &[record(19)], &[], &Sent::nothing(&state), NOW);
        let a_сначала = apply_server(
            &a_сначала,
            20,
            &[record(20)],
            &[],
            &Sent::nothing(&a_сначала),
            NOW,
        );

        let b_сначала = apply_server(&state, 20, &[record(20)], &[], &Sent::nothing(&state), NOW);
        let b_сначала = apply_server(
            &b_сначала,
            19,
            &[record(19)],
            &[],
            &Sent::nothing(&b_сначала),
            NOW,
        );

        assert_eq!(a_сначала.books[0].rev, 20);
        assert_eq!(b_сначала.books[0].rev, 20);
        assert_eq!(a_сначала.cursor, 20);
        assert_eq!(b_сначала.cursor, 20);
    }

    #[test]
    fn на_отправку_идёт_только_изменённое() {
        let mut чистая = книга(UUID, "Отправлена", NOW);
        чистая.dirty = false;
        let грязная = книга("3f1c2b4a-0000-4000-8000-000000000002", "Ждёт", NOW);

        let state = состояние(vec![чистая, грязная]);
        let sent = Sent::of(&state);

        assert_eq!(sent.books.len(), 1);
        assert!(sent.books.contains("3f1c2b4a-0000-4000-8000-000000000002"));
        assert_eq!(sent.revision, state.revision);
    }

    #[test]
    fn книга_со_старым_номером_переезжает_вместе_с_колодой() {
        let mut карточка = Card::new("c1", "library", "library");
        карточка.book_id = "book-1".to_string();
        карточка.dirty = false;
        карточка.rev = 4;

        let state = LibraryState {
            books: vec![книга("book-1", "Гэтсби", NOW)],
            cards: vec![карточка],
            ..Default::default()
        };

        let mut свежие = std::iter::once(UUID.to_string());
        let after = migrate(&state, &mut свежие);

        assert_eq!(after.books[0].id, UUID);
        assert_eq!(
            after.books[0].rev, 0,
            "переехавшая книга помнит чужую ревизию"
        );
        assert!(after.books[0].dirty);
        // Колода обязана переехать вместе с книгой, иначе потеряет хозяина.
        assert_eq!(after.cards[0].book_id, UUID);
        assert_eq!(after.cards[0].rev, 0);
        assert!(after.cards[0].dirty);
    }

    #[test]
    fn книга_с_нормальным_номером_остаётся_нетронутой() {
        let mut книжка = книга(UUID, "Гэтсби", NOW);
        книжка.dirty = false;
        книжка.rev = 7;

        let state = LibraryState {
            books: vec![книжка],
            ..Default::default()
        };
        let mut свежие = std::iter::once("не пригодится".to_string());
        let after = migrate(&state, &mut свежие);

        assert_eq!(after.books[0].id, UUID);
        assert_eq!(after.books[0].rev, 7, "нетронутая книга потеряла ревизию");
        assert!(!after.books[0].dirty, "нетронутая книга пошла на отправку");
    }

    #[test]
    fn карточка_со_старым_номером_получает_uuid() {
        let mut карточка = Card::new("card-1", UUID, "library");
        карточка.book_id = UUID.to_string();
        карточка.dirty = false;
        карточка.rev = 9;
        let state = LibraryState {
            books: vec![книга(UUID, "Гэтсби", NOW)],
            cards: vec![карточка],
            ..Default::default()
        };
        let fresh = "3f1c2b4a-0000-4000-8000-000000000002";
        let after = migrate(&state, &mut std::iter::once(fresh.to_string()));

        assert_eq!(after.cards[0].id, fresh);
        assert_eq!(after.cards[0].book_id, UUID);
        assert_eq!(after.cards[0].rev, 0);
        assert!(after.cards[0].dirty);
    }

    #[test]
    fn миграция_uuid_идемпотентна() {
        let fresh = "3f1c2b4a-0000-4000-8000-000000000002";
        let mut карточка = Card::new(fresh, UUID, "library");
        карточка.dirty = false;
        карточка.rev = 9;
        let state = LibraryState {
            books: vec![книга(UUID, "Гэтсби", NOW)],
            cards: vec![карточка],
            ..Default::default()
        };
        let after = migrate(&state, &mut std::iter::empty());

        assert_eq!(after, state);
    }

    #[test]
    fn номер_узнаётся_по_длине_и_дефисам() {
        assert!(looks_like_uuid(UUID));
        assert!(!looks_like_uuid("book-1"));
        assert!(!looks_like_uuid(""));
        // Тридцать шесть знаков, но дефисов не столько.
        assert!(!looks_like_uuid(&"x".repeat(36)));
    }

    // --- §5 Variant A: детерминированный canonical id ---

    #[test]
    fn canonical_детерминирован_и_проходит_uuid_проверку() {
        let a = crate::library::book::canonical_book_id("abc123").unwrap();
        let b = crate::library::book::canonical_book_id("abc123").unwrap();
        assert_eq!(a, b, "один HASH обязан давать один id на всех устройствах");
        assert!(
            looks_like_uuid(&a),
            "canonical обязан пройти серверный uuidPattern: {a}"
        );
        assert_ne!(a, "abc123");
        let c = crate::library::book::canonical_book_id("другой").unwrap();
        assert_ne!(a, c);
        assert!(
            crate::library::book::canonical_book_id("").is_none(),
            "пустой отпечаток не каноникализируется"
        );
    }

    #[test]
    fn две_офлайн_книги_с_одним_hash_схлопываются_при_миграции() {
        // Телефон офлайн: id=A, source_key=HASH; десктоп офлайн: id=B, source_key=HASH
        let hash = "deadbeefcafe123";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        let mut a = книга("11111111-1111-1111-1111-111111111111", "A", NOW);
        a.source_key = hash.to_string();
        a.progress = crate::library::book::Progress {
            chapter: 1,
            within_chapter: 0.2,
            block_index: 0,
            block_offset: 0.0,
            opened_at: NOW,
        };
        a.rev = 1;
        a.dirty = true;
        let mut b = книга("22222222-2222-2222-2222-222222222222", "B", NOW + 10);
        b.source_key = hash.to_string();
        b.progress = crate::library::book::Progress {
            chapter: 3,
            within_chapter: 0.5,
            block_index: 0,
            block_offset: 0.0,
            opened_at: NOW + 100,
        };
        b.rev = 2;
        b.dirty = true;

        // Карточки привязаны к разным id, один и тот же логос на разных устройствах
        let mut card_a = Card::new("c1", "s", "lemma");
        card_a.book_id = a.id.clone();
        card_a.lemma = "hello".to_string();
        let mut card_b = Card::new("c2", "s", "lemma2");
        card_b.book_id = b.id.clone();
        card_b.lemma = "world".to_string();

        // Также дубликат леммы hello с другим card id (то же слово сохранено на обоих устройствах)
        let mut card_dup = Card::new("c3", "s", "lemma");
        card_dup.book_id = b.id.clone();
        card_dup.lemma = "hello".to_string();

        let state = LibraryState {
            books: vec![a, b],
            cards: vec![card_a, card_b, card_dup],
            ..Default::default()
        };

        let migrated = migrate_to_canonical(&state);
        // Должна остаться одна книга с canonical id
        assert_eq!(
            migrated.books.len(),
            1,
            "две офлайн книги обязаны схлопнуться в одну: {:?}",
            migrated.books
        );
        assert_eq!(migrated.books[0].id, canonical);
        assert_eq!(migrated.books[0].source_key, hash);
        assert!(migrated.books[0].dirty);
        assert_eq!(migrated.books[0].rev, 0);
        // Все карточки перепривязаны к canonical
        for card in &migrated.cards {
            assert_eq!(
                card.book_id, canonical,
                "карточка не перепривязалась: {:?}",
                card
            );
        }
        // Дубликат hello схлопнулся: hello один раз, world один раз => 2 карточки, а не 3
        let hello_count = migrated
            .cards
            .iter()
            .filter(|c| c.lemma == "hello" && !c.deleted)
            .count();
        assert_eq!(hello_count, 1, "дубликат карточки по lemma не схлопнулся");
        assert_eq!(migrated.cards.len(), 2);
    }

    #[test]
    fn add_book_каноникализируется() {
        let state = состояние(vec![]);
        let hash = "hash123";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        // Клиент прислал случайный id, но Rust должен заменить на canonical
        let mut incoming = книга("99999999-9999-9999-9999-999999999999", "Новая", NOW);
        incoming.source_key = hash.to_string();
        let after = crate::library::ops::add_book(&state, incoming);
        assert_eq!(after.books.len(), 1);
        assert_eq!(
            after.books[0].id, canonical,
            "add_book обязан заменить id на canonical"
        );
    }

    #[test]
    fn apply_server_схлопывает_дубликат_source_key_после_офлайн_сценария() {
        // Локально старая случайная книга A с HASH, сервер присылает canonical C с тем же HASH
        let hash = "same-hash-sync";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        let state = LibraryState {
            books: vec![{
                let mut b = книга("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Локальная", NOW);
                b.source_key = hash.to_string();
                b.path = "books/a.epub".to_string();
                b.rev = 1;
                b.dirty = false;
                b
            }],
            revision: 5,
            ..Default::default()
        };

        // Сервер присылает каноническую книгу с тем же hash
        let mut incoming = книга(&canonical, "Серверная", NOW);
        incoming.source_key = hash.to_string();
        incoming.rev = 5;
        let after = apply_server(&state, 5, &[incoming], &[], &Sent::nothing(&state), NOW);
        assert_eq!(
            after.books.len(),
            1,
            "после sync должна остаться одна логическая книга, а не две: {:?}",
            after.books
        );
        assert_eq!(after.books[0].id, canonical);
        assert_eq!(after.books[0].source_key, hash);
        // Путь не затирается
        assert_eq!(
            after.books[0].path, "books/a.epub",
            "path-local состояние потерялось"
        );
    }

    #[test]
    fn перепривязанная_карточка_помечается_к_отправке() {
        // Локальная старая книга A с HASH, сервер присылает каноническую C.
        // Карточка A уезжает к C — и эта перепривязка обязана уехать на сервер,
        // иначе он вечно будет отдавать её со старым book_id.
        let hash = "rebind-dirty-hash";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        let mut local = книга("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "Локальная", NOW);
        local.source_key = hash.to_string();
        let mut card = Card::new("c1", "s", "lemma");
        card.book_id = local.id.clone();
        card.lemma = "hello".to_string();
        card.rev = 7;
        card.dirty = false;
        let state = LibraryState {
            books: vec![local],
            cards: vec![card],
            revision: 5,
            ..Default::default()
        };

        let mut incoming = книга(&canonical, "Серверная", NOW);
        incoming.source_key = hash.to_string();
        incoming.rev = 5;
        let after = apply_server(&state, 5, &[incoming], &[], &Sent::nothing(&state), NOW);

        assert_eq!(after.cards.len(), 1);
        assert_eq!(after.cards[0].book_id, canonical);
        assert!(
            after.cards[0].dirty,
            "перепривязка book_id обязана уехать на сервер, иначе схлопывание повторяется вечно"
        );
        assert_eq!(
            after.cards[0].rev, 7,
            "номер карточки не менялся — сбрасывать её ревизию нельзя"
        );
    }

    #[test]
    fn каноничная_книга_в_группе_дубликатов_не_теряет_ревизию() {
        // Одна книга уже на каноническом номере (rev 9, синхронизирована),
        // рядом — старый дубликат. Схлопывание не должно обнулять ревизию
        // выжившей: сервер по ней отличает свежую копию от устаревшей.
        let hash = "dup-with-canonical";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        let mut good = книга(&canonical, "Каноничная", NOW);
        good.source_key = hash.to_string();
        good.rev = 9;
        good.path = "books/good.epub".to_string();
        let mut old = книга("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "Старая", NOW + 1);
        old.source_key = hash.to_string();
        old.rev = 2;

        let mut card = Card::new("c1", "s", "lemma");
        card.book_id = good.id.clone();
        card.lemma = "hello".to_string();
        card.rev = 4;
        card.dirty = false;

        let state = LibraryState {
            books: vec![good, old],
            cards: vec![card],
            ..Default::default()
        };
        let after = migrate_to_canonical(&state);
        assert_eq!(after.books.len(), 1);
        assert_eq!(after.books[0].id, canonical);
        assert_eq!(
            after.books[0].rev, 9,
            "ревизия каноничной книги обнулена зря"
        );
        assert_eq!(
            after.cards[0].rev, 4,
            "карточка не меняла владельца — её ревизию трогать нельзя"
        );
        assert!(
            !after.cards[0].dirty,
            "карточка не менялась, а помечена к отправке"
        );
    }

    #[test]
    fn migrate_to_canonical_идемпотентна() {
        let hash = "idem-hash";
        let canonical = crate::library::book::canonical_book_id(hash).unwrap();
        let mut book = книга(&canonical, "Уже канон", NOW);
        book.source_key = hash.to_string();
        book.rev = 5;
        book.dirty = false;
        let state = LibraryState {
            books: vec![book],
            ..Default::default()
        };
        let once = migrate_to_canonical(&state);
        let twice = migrate_to_canonical(&once);
        assert_eq!(once, twice, "повторная миграция не должна менять состояние");
    }

    #[test]
    fn migrate_to_canonical_сохраняет_книги_без_source_key() {
        let state = LibraryState {
            books: vec![книга(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "Без хеша",
                NOW,
            )],
            ..Default::default()
        };
        let after = migrate_to_canonical(&state);
        assert_eq!(after.books.len(), 1);
        assert_eq!(after.books[0].id, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assert_eq!(after, state, "книги без source_key не должны трогаться");
    }
}
