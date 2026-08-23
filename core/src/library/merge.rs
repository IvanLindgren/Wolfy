//! Слияние библиотеки с ответом сервера.
//!
//! Самая тонкая часть библиотеки и главная причина, по которой она обязана
//! жить в ядре: два устройства одного читателя должны разрешать столкновения
//! одинаково. Две реализации этих правил рано или поздно разойдутся, и
//! разойдутся тихо — прочитанная глава просто исчезнет.

use super::book::{LibraryBook, LibraryState, Shelf};
use crate::srs::Card;
use std::collections::HashSet;

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
            None => merged_books.push(LibraryBook {
                path: String::new(),
                dirty: false,
                ..incoming.clone()
            }),
        }
    }

    let mut merged_cards = state.cards.clone();
    for incoming in cards {
        let local = merged_cards.iter().position(|card| card.id == incoming.id);
        match local {
            Some(at) => {
                if merged_cards[at].dirty && !(quiet && sent.cards.contains(&incoming.id)) {
                    continue;
                }
                merged_cards[at] = Card {
                    dirty: false,
                    ..incoming.clone()
                };
            }
            None => merged_cards.push(Card {
                dirty: false,
                ..incoming.clone()
            }),
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
        cursor,
        ..state.clone()
    }
    .touched()
}

/// Приводит прочитанное состояние к нынешнему виду.
///
/// Пока нужна одна поправка: до синхронизации номера книг придумывались как
/// попало, а на сервере под них колонка uuid. Книга со старым номером
/// получает новый — вместе со своими карточками, иначе колода потеряет
/// хозяина.
///
/// Новые номера приходят снаружи: своего источника случайности у ядра нет, и
/// заводить его ради одной миграции незачем. Ожидается по одному номеру на
/// каждую книгу с непригодным номером; если их не хватит, такая книга
/// остаётся как есть и попробует переехать в следующий раз.
pub fn migrate(state: &LibraryState, fresh_ids: &mut impl Iterator<Item = String>) -> LibraryState {
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

    if renamed.is_empty() {
        return state.clone();
    }

    let cards: Vec<Card> = state
        .cards
        .iter()
        .map(|card| {
            let owner = renamed
                .iter()
                .find(|(old, _)| *old == card.book_id)
                .map(|(_, fresh)| fresh.clone());
            match owner {
                Some(owner) => Card {
                    book_id: owner,
                    rev: 0,
                    dirty: true,
                    ..card.clone()
                },
                None => card.clone(),
            }
        })
        .collect();

    LibraryState {
        books,
        cards,
        ..state.clone()
    }
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
    fn номер_узнаётся_по_длине_и_дефисам() {
        assert!(looks_like_uuid(UUID));
        assert!(!looks_like_uuid("book-1"));
        assert!(!looks_like_uuid(""));
        // Тридцать шесть знаков, но дефисов не столько.
        assert!(!looks_like_uuid(&"x".repeat(36)));
    }
}
