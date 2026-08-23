//! Что спрашивают сегодня и в каком порядке.
//!
//! Ровно те правила, которые раньше жили в модели экрана на Kotlin: сколько
//! карточек созрело, сколько выучено, какие новые правила подмешать и что
//! попадёт в сегодняшнюю порцию. Пикселей здесь нет — а значит, и в клиенте
//! им делать нечего: иначе телефон и настольная машина однажды покажут разное
//! число «к повторению» на одной и той же колоде.

use super::card::Card;
use super::drill::{self, Deck, Drill};
use super::{chunks, scheduler};
use crate::grammar::{analyze, Exercise};
use crate::lexicon::Lexicon;
use crate::tokenizer::tokenize;
use serde::Serialize;

/// Сколько заданий в одной порции.
///
/// Порция, а не «всё, что созрело»: колода в двести карточек отпугивает, а
/// двадцать заданий — это несколько минут, которые находятся почти всегда.
pub const PORTION: usize = 20;

/// Сколько новых правил подмешивать за раз.
pub const NEW_RULES: usize = 3;

/// Состояние одной колоды на экране.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeckStatus {
    /// Устойчивое имя вида карточки: `word`, `phrase`, `rule`.
    pub kind: String,
    pub due: usize,
    pub total: usize,
    pub learned: usize,
}

/// Правило, которое сегодня спросят впервые.
///
/// Карточки у него ещё нет: пока читатель не ответил, правила нет ни в
/// колоде, ни на сервере. Заводить их все разом нельзя — правил под шесть
/// десятков, и читатель увидел бы шесть десятков «к повторению» в первый же
/// день.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FreshRule {
    pub rule: String,
    /// Что написать на карточке правила.
    pub title: String,
}

/// Сегодняшняя порция.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Queue {
    /// Ключи заданий по порядку: номера карточек, а у новых правил — имя
    /// правила, которое станет ключом будущей карточки.
    pub keys: Vec<String>,
    pub rules: Vec<FreshRule>,
}

/// Карточки одной колоды.
fn deck_cards<'a>(cards: &'a [Card], kind: &str) -> Vec<&'a Card> {
    cards
        .iter()
        .filter(|card| !card.deleted && card.kind == kind)
        .collect()
}

/// Состояние колоды.
pub fn status(cards: &[Card], kind: &str, now: i64, exercises: &[Exercise]) -> DeckStatus {
    let mine: Vec<Card> = deck_cards(cards, kind).into_iter().cloned().collect();
    let due = scheduler::due(&mine, now).len();
    let learned = scheduler::learned(&mine).len();

    if kind != Deck::Rules.kind() {
        return DeckStatus {
            kind: kind.to_string(),
            due,
            total: mine.len(),
            learned,
        };
    }

    // Грамматика — единственная колода, которая наполняется сама: правила уже
    // написаны, и ждать, пока читатель их наберёт, незачем. Но и вываливать
    // шесть десятков разом нельзя, поэтому новые подмешиваются порцией в день.
    let fresh = untouched(&mine, exercises).len();
    DeckStatus {
        kind: kind.to_string(),
        due: due + fresh.min(NEW_RULES),
        total: mine.len() + fresh,
        learned,
    }
}

/// Правила, которых ещё нет в колоде.
fn untouched(mine: &[Card], exercises: &[Exercise]) -> Vec<FreshRule> {
    let started: Vec<&str> = mine.iter().map(|card| card.lemma.as_str()).collect();
    let mut fresh: Vec<FreshRule> = Vec::new();

    for exercise in exercises {
        if started.contains(&exercise.rule) || fresh.iter().any(|item| item.rule == exercise.rule) {
            continue;
        }
        fresh.push(FreshRule {
            rule: exercise.rule.to_string(),
            title: if exercise.question.trim().is_empty() {
                exercise.rule.to_string()
            } else {
                exercise.question.to_string()
            },
        });
    }
    fresh
}

/// Набирает сегодняшнюю порцию.
pub fn queue(cards: &[Card], kind: &str, now: i64, exercises: &[Exercise]) -> Queue {
    let mine: Vec<Card> = deck_cards(cards, kind).into_iter().cloned().collect();
    let mut keys: Vec<String> = scheduler::due(&mine, now)
        .into_iter()
        .map(|card| card.id)
        .collect();

    let rules = if kind == Deck::Rules.kind() {
        let mut fresh = untouched(&mine, exercises);
        fresh.truncate(NEW_RULES);
        fresh
    } else {
        Vec::new()
    };

    keys.extend(rules.iter().map(|rule| rule.rule.clone()));
    keys.truncate(PORTION);
    Queue { keys, rules }
}

/// Собирает задание по карточке.
///
/// Способ выбирается по виду карточки, а внутри вида — по прочности. Блоки
/// фразы считаются здесь же, в момент показа, а не при сохранении: разбивка
/// зависит от разбора, разбор — от движка, а движок меняется от версии к
/// версии. Сохранённые однажды блоки через полгода разошлись бы с тем, что
/// показывает читалка на той же фразе.
pub fn drill_for(cards: &[Card], card_id: &str, lexicon: &Lexicon) -> Option<Drill> {
    let card = cards
        .iter()
        .find(|card| card.id == card_id && !card.deleted)?;
    let seed = drill::seed_of(&card.id);

    if card.kind == Deck::Phrases.kind() {
        let blocks = blocks(&card.surface, lexicon);
        let extra = strangers(cards, card, lexicon);
        return Some(drill::for_phrase(card, &blocks, &extra, seed));
    }

    let words: Vec<Card> = deck_cards(cards, Deck::Words.kind())
        .into_iter()
        .cloned()
        .collect();
    Some(drill::for_word(card, &words, seed))
}

/// Блоки фразы для конструктора.
fn blocks(sentence: &str, lexicon: &Lexicon) -> Vec<String> {
    let tokens = tokenize(sentence);
    let findings = analyze(lexicon, &tokens);
    chunks::split(&tokens, &findings)
}

/// Лишние блоки в банк слов — из чужих фраз той же колоды.
///
/// Чужой блок правдоподобен ровно потому, что он настоящий: «has read» из
/// соседнего предложения выглядит уместно рядом с «have been reading», а
/// сочетание, выдуманное приложением, — нет.
fn strangers(cards: &[Card], card: &Card, lexicon: &Lexicon) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for other in deck_cards(cards, Deck::Phrases.kind()) {
        if other.id == card.id {
            continue;
        }
        for block in blocks(&other.surface, lexicon) {
            if block.trim().is_empty() || out.contains(&block) {
                continue;
            }
            out.push(block);
            if out.len() == 3 {
                return out;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;

    fn карточка(id: &str, kind: &str, lemma: &str, due_at: i64) -> Card {
        let mut card = Card::new(id, lemma, lemma);
        card.kind = kind.to_string();
        card.due_at = due_at;
        card
    }

    fn упражнения() -> Vec<Exercise> {
        crate::grammar::exercises(Lexicon::embedded())
    }

    #[test]
    fn созревшее_и_выученное_считаются_по_своей_колоде() {
        let mut выучено = карточка("1", "word", "library", NOW - 1);
        выучено.hp = 0;
        let cards = vec![
            выучено,
            карточка("2", "word", "dusk", NOW - 1),
            карточка("3", "word", "shelf", NOW + 100_000),
            карточка("4", "phrase", "чужая", NOW - 1),
        ];

        let status = status(&cards, "word", NOW, &[]);
        assert_eq!(status.total, 3, "в колоду слов попала фраза");
        assert_eq!(status.due, 2);
        assert_eq!(status.learned, 1);
    }

    #[test]
    fn колода_правил_наполняется_сама_но_порцией() {
        let упражнения = упражнения();
        let status = status(&[], "rule", NOW, &упражнения);

        // Правил под шесть десятков, и все разом читателю не показывают.
        assert_eq!(status.due, NEW_RULES, "правила вывалились разом: {}", status.due);
        assert!(status.total > NEW_RULES, "новые правила не сосчитаны");
        assert_eq!(status.learned, 0);
    }

    #[test]
    fn начатое_правило_новым_больше_не_считается() {
        let упражнения = упражнения();
        let первое = упражнения[0].rule;
        let cards = vec![карточка("1", "rule", первое, NOW + 100_000)];

        let status = status(&cards, "rule", NOW, &упражнения);
        let чистая = self::status(&[], "rule", NOW, &упражнения);
        assert_eq!(status.total, чистая.total, "правило посчитано дважды");
    }

    #[test]
    fn очередь_не_длиннее_порции() {
        let cards: Vec<Card> = (0..50)
            .map(|n| карточка(&n.to_string(), "word", &format!("w{n}"), NOW - 1))
            .collect();

        let queue = queue(&cards, "word", NOW, &[]);
        assert_eq!(queue.keys.len(), PORTION);
        assert!(queue.rules.is_empty(), "в колоду слов подмешаны правила");
    }

    #[test]
    fn новые_правила_идут_в_очередь_ключом_имени() {
        let упражнения = упражнения();
        let queue = queue(&[], "rule", NOW, &упражнения);

        assert_eq!(queue.rules.len(), NEW_RULES);
        assert_eq!(queue.keys.len(), NEW_RULES);
        // Ключ будущей карточки — имя правила: карточки ещё нет.
        assert_eq!(queue.keys[0], queue.rules[0].rule);
        assert!(!queue.rules[0].title.is_empty());
    }

    #[test]
    fn несозревшее_в_очередь_не_идёт() {
        let cards = vec![
            карточка("1", "word", "library", NOW - 1),
            карточка("2", "word", "dusk", NOW + 100_000),
        ];
        let queue = queue(&cards, "word", NOW, &[]);
        assert_eq!(queue.keys, vec!["1"]);
    }

    #[test]
    fn задание_по_фразе_собирается_из_блоков() {
        let mut фраза = карточка("p1", "phrase", "", NOW - 1);
        фраза.surface = "I have been reading this book for a month.".to_string();
        фраза.translation = "Я читаю эту книгу уже месяц.".to_string();
        фраза.hp = 40;

        let drill = drill_for(&[фраза], "p1", Lexicon::embedded()).expect("задания нет");
        assert!(drill.pieces.len() > 1, "фраза не разбилась: {:?}", drill.pieces);
        assert!(
            drill.pieces.iter().any(|block| block.contains(' ')),
            "все блоки по одному слову: {:?}",
            drill.pieces
        );
    }

    #[test]
    fn задание_по_удалённой_карточке_не_собирается() {
        let mut удалена = карточка("1", "word", "library", NOW - 1);
        удалена.deleted = true;
        assert!(drill_for(&[удалена], "1", Lexicon::embedded()).is_none());
    }
}
