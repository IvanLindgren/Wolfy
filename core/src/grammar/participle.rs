//! Причастия и причастные обороты: «the man standing there», «Having finished
//! work, he went home», «broken glass».
//!
//! Это самая скользкая часть неличных форм, и по одной причине: форма на
//! «-ing» и третья форма заняты ещё и временами с залогом. «is reading» —
//! время, «was broken» — залог, а «the man standing there» — уже определение.
//! Различить их можно только по тому, занято ли слово сказуемым, и по тому,
//! что стоит слева.
//!
//! Поэтому здесь работает та же карта занятости, что у герундия: всё, что
//! вошло в глагольную цепочку, причастием не разбирается. А среди свободных
//! слов признак один — позиция: после существительного это определение,
//! после запятой или в начале фразы перед запятой — оборот.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{Aux, Word};

use super::chain::chains;
use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut busy = vec![false; words.len()];
    for chain in chains(words) {
        for index in chain.words.clone() {
            if let Some(slot) = busy.get_mut(index) {
                *slot = true;
            }
        }
    }

    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        // Перфектное причастие: «Having finished work, he went home».
        //
        // Слова «having» и следующего за ним причастия цепочка обычно
        // забирает себе как служебное «have» с его третьей формой — но ни
        // время, ни залог там не получаются, и разбор молчал бы вовсе.
        // Поэтому этот случай проверяется до карты занятости.
        if word.lower == "having" {
            if let Some(next) = words.get(index + 1) {
                if next.pos == Pos::Verb && next.has_form(VerbForm::Participle) {
                    out.push(Finding::new(
                        "perfect-participle",
                        "Перфектное причастие",
                        "having + V3",
                        "Одно действие закончилось раньше другого: сначала \
                         закончил работу, потом пошёл домой. По-русски здесь \
                         было бы «закончив работу»",
                        words,
                        index..index + 2,
                    ));
                    continue;
                }
            }
        }

        if busy[index] {
            continue;
        }

        // Причастие настоящего времени: форма на «-ing», не занятая сказуемым
        // и не стоящая после предлога — последнее уже разобрано герундием.
        // Словарь помечает такие слова и существительными («walking»), поэтому
        // для оборота часть речи мало — там решает структура с запятой.
        if word.has_form(VerbForm::Gerund) && !previous_is_preposition(words, index) {
            let previous = index.checked_sub(1).and_then(|i| words.get(i));

            // Оборот: либо стоит сразу после запятой, либо открывает фразу и
            // кончается запятой где-то дальше — «Walking down the street, …».
            // Запятую ищем до первого занятого слова: у «Reading is useful»
            // перед сказуемым её нет, и это просто герундий-подлежащее.
            // Слева от запятой должен быть кто-то, кто делает действие, —
            // после вводного наречия («Suddenly, …») начинается не оборот.
            let starts_after_comma = previous.is_some_and(|p| {
                matches!(p.pos, Pos::Noun | Pos::Pronoun | Pos::Verb | Pos::Numeral) && p.breaks
            });
            let opens_with_comma = index == 0 && comma_before_predicate(words, &busy, index);
            // Определение: приросло к существительному слева — «the man
            // standing there». Это тот же оборот, только без запятых.
            let attributes_noun =
                previous.is_some_and(|p| matches!(p.pos, Pos::Noun | Pos::Pronoun));

            if starts_after_comma || opens_with_comma {
                out.push(Finding::new(
                    "participle-clause",
                    "Причастный оборот",
                    "V-ing, …",
                    "Действие идёт параллельно главному или объясняет его: \
                     «walking down the street» — шёл и одновременно гулял. \
                     По-русски это деепричастный оборот",
                    words,
                    index..index + 1,
                ));
                continue;
            }
            if attributes_noun {
                out.push(Finding::new(
                    "present-participle",
                    "Причастие настоящего времени",
                    "существительное + V-ing",
                    "Определяет существительное слева: «the man standing there» — \
                     человек, который стоит там. Действие идёт сейчас или \
                     постоянно",
                    words,
                    index..index + 1,
                ));
                continue;
            }
        }

        // Причастие прошедшего времени на месте определения: «broken glass»,
        // «a letter written by hand». Перед ним определитель или прилагательное,
        // либо за ним называет деятель через «by».
        if word.has_form(VerbForm::Participle) && matches!(word.pos, Pos::Verb | Pos::Adjective) {
            let previous = index.checked_sub(1).and_then(|i| words.get(i));
            let next = words.get(index + 1);

            let after_determiner = previous
                .is_some_and(|p| matches!(p.pos, Pos::Determiner | Pos::Adjective | Pos::Noun));
            let with_agent = next.is_some_and(|n| n.lower == "by");

            if after_determiner || with_agent {
                out.push(Finding::new(
                    "past-participle",
                    "Причастие прошедшего времени",
                    "V3 + существительное / V3 + by",
                    "Страдательное значение на месте определения: «broken glass» — \
                     стекло, которое разбили. Иногда это уже прилагательное, \
                     и различает их только смысл",
                    words,
                    index..index + 1,
                ));
            }
        }
    }

    out
}

fn previous_is_preposition(words: &[Word], index: usize) -> bool {
    index
        .checked_sub(1)
        .and_then(|i| words.get(i))
        .is_some_and(|p| p.pos == Pos::Preposition || p.aux == Some(Aux::To))
}

/// Есть ли запятая между словом и ближайшим сказуемым.
///
/// Вводный оборот кончается запятой: «Walking down the street, she met…».
/// Если до занятого сказуемым слова запятой нет — это не оборот, а подлежащее
/// или часть сказуемого, и разбирать его здесь нельзя.
fn comma_before_predicate(words: &[Word], busy: &[bool], start: usize) -> bool {
    for index in start + 1..words.len() {
        if words[index].breaks {
            return true;
        }
        if busy[index] {
            return false;
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use crate::lexicon::Lexicon;
    use crate::tagger::tag;
    use crate::tokenizer::tokenize;

    fn правила(sentence: &str) -> Vec<&'static str> {
        let tokens = tokenize(sentence);
        let words = tag(Lexicon::embedded(), &tokens);
        super::super::analyze_words(&words)
            .into_iter()
            .map(|f| f.rule)
            .collect()
    }

    #[test]
    fn перфектное_причастие_находится() {
        assert!(правила("Having finished work, he went home.").contains(&"perfect-participle"));
        assert!(правила("Having been warned, she left early.").contains(&"perfect-participle"));
    }

    #[test]
    fn оборот_и_определение_различаются() {
        assert!(правила("Walking down the street, she met an old friend.")
            .contains(&"participle-clause"));
        assert!(правила("She sat by the window, reading a book.").contains(&"participle-clause"));
        assert!(правила("The man standing there is my brother.").contains(&"present-participle"));
    }

    #[test]
    fn причастие_прошедшего_времени_на_месте_определения() {
        assert!(правила("The broken glass lay on the floor.").contains(&"past-participle"));
        assert!(правила("A letter written by hand arrived today.").contains(&"past-participle"));
    }

    #[test]
    fn времена_и_залог_не_разбираются_причастием() {
        // Всё, что занято сказуемым, сюда не попадает: ложное срабатывание
        // хуже пропуска.
        assert!(!правила("She was reading a book.").contains(&"participle-clause"));
        assert!(!правила("The window was broken by the wind.").contains(&"past-participle"));
        assert!(!правила("Reading is useful.").contains(&"participle-clause"));
        assert!(!правила("He made a promise and left.").contains(&"bare-infinitive"));
    }
}
