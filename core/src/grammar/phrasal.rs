//! Фразовые глаголы: «give up», «look forward to», «run out of».
//!
//! Смысл фразового глагола не собирается из частей: «give up» — это «сдаться»,
//! а не «дать вверх». Русскому читателю нужно именно это объяснение, иначе он
//! переведёт каждое слово отдельно и получит бессмыслицу.
//!
//! Список закрытый и ручной: в него входят самые частые сочетания книжного
//! английского. Каждое помечено типом — разделяемый глагол допускает
//! местоимение внутри («turn it on»), неразделяемый нет, трёхкомпонентный
//! держит предлог при себе.

use crate::lexicon::Pos;
use crate::tagger::Word;

use super::Finding;

/// Фразовые глаголы: последовательность слов и тип.
const PHRASALS: &[(&[&str], &str)] = &[
    (&["get", "up"], "разделяемый"),
    (&["wake", "up"], "разделяемый"),
    (&["pick", "up"], "разделяемый"),
    (&["give", "up"], "разделяемый"),
    (&["give", "in"], "неразделяемый"),
    (&["put", "on"], "разделяемый"),
    (&["put", "off"], "разделяемый"),
    (&["take", "off"], "разделяемый"),
    (&["take", "on"], "разделяемый"),
    (&["turn", "on"], "разделяемый"),
    (&["turn", "off"], "разделяемый"),
    (&["show", "up"], "разделяемый"),
    (&["turn", "up"], "разделяемый"),
    (&["bring", "up"], "разделяемый"),
    (&["call", "off"], "разделяемый"),
    (&["carry", "on"], "разделяемый"),
    (&["carry", "out"], "разделяемый"),
    (&["find", "out"], "разделяемый"),
    (&["point", "out"], "разделяемый"),
    (&["work", "out"], "разделяемый"),
    (&["sort", "out"], "разделяемый"),
    (&["set", "up"], "разделяемый"),
    (&["make", "up"], "разделяемый"),
    (&["break", "down"], "разделяемый"),
    (&["come", "across"], "неразделяемый"),
    (&["come", "back"], "разделяемый"),
    (&["go", "on"], "неразделяемый"),
    (&["hold", "on"], "неразделяемый"),
    (&["drop", "off"], "разделяемый"),
    (&["look", "for"], "неразделяемый"),
    (&["look", "after"], "неразделяемый"),
    (&["look", "forward", "to"], "трёхкомпонентный"),
    (&["run", "out", "of"], "трёхкомпонентный"),
    (&["get", "rid", "of"], "трёхкомпонентный"),
];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        // Слово обязано хотя бы уметь быть глаголом — и не стоять при этом
        // внутри именной группы. «A break down the road» — существительное с
        // предлогом, и «сдаться» там взяться неоткуда; артикль слева и есть
        // тот признак, по которому это видно. Спрашивать прямо про
        // [`Pos::Verb`] нельзя: в «the car broke down» теггер помечает
        // «broke» прилагательным — есть и такое значение, — а фразовый глагол
        // там всё-таки настоящий.
        if word.verb.is_empty() {
            continue;
        }
        let in_noun_phrase = index
            .checked_sub(1)
            .and_then(|i| words.get(i))
            .is_some_and(|p| p.pos == Pos::Determiner);
        if in_noun_phrase {
            continue;
        }
        for (phrase, kind) in PHRASALS {
            // Первое слово — глагол в любой форме: «gave up» и «give up»
            // одно и то же. Сверяем по начальной форме роли глагола.
            if word.lower != phrase[0]
                && !word.verb.iter().any(|role| role.base.as_ref() == phrase[0])
            {
                continue;
            }
            let matches = phrase.iter().enumerate().all(|(offset, part)| {
                words.get(index + offset).is_some_and(|w| {
                    offset == 0
                        || w.lower == *part
                        || w.verb.iter().any(|role| role.base.as_ref() == *part)
                })
            });
            if !matches {
                continue;
            }

            let hint = match *kind {
                "разделяемый" => {
                    "Местоимение-объект встаёт внутрь: «turn it on», а не \
                     «turn on it»"
                }
                "трёхкомпонентный" => {
                    "Предлог здесь обязателен: он часть самого сочетания"
                }
                _ => "Разрывать его объектом нельзя: объект идёт после",
            };

            out.push(Finding::new(
                "phrasal-verb",
                "Фразовый глагол",
                "глагол + предлог (+ объект)",
                format!(
                    "«{}»: смысл собирается из глагола и предлога вместе, а не \
                     из отдельных слов. Это {kind} вариант. {hint}",
                    phrase.join(" ")
                ),
                words,
                index..index + phrase.len(),
            ));
        }
    }

    out
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
    fn фразовые_глаголы_находятся() {
        assert!(правила("She gave up smoking last year.").contains(&"phrasal-verb"));
        assert!(правила("He is looking forward to the trip.").contains(&"phrasal-verb"));
        assert!(правила("We ran out of milk.").contains(&"phrasal-verb"));
    }

    #[test]
    fn одиночный_глагол_не_фразовый() {
        // «give» без своего предлога — обычный глагол.
        assert!(!правила("They give money to charity every month.").contains(&"phrasal-verb"));
    }
}
