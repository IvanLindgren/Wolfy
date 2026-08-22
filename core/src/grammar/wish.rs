//! Конструкции с «wish» и «if only»: «I wish I knew», «If only I had known».
//!
//! По-русски все они переводятся одинаково — «если бы», — и различить их
//! можно только по форме глагола после «wish». Форма здесь и есть смысл:
//! прошедшее время говорит о настоящем, предпрошедшее — о прошлом, «would» —
//! о чужом поведении, на которое говорящий повлиять не может.
//!
//! Детектор ищет «wish» или «if only», затем первую глагольную цепочку после
//! них. Если цепочки нет («I wish you luck») — это просто пожелание, и
//! разбирать его наклонением значило бы наврать.

use crate::tagger::{modal_base, Word};

use super::chain::{chains, Link};
use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let Some(marker) = words.iter().enumerate().position(|(index, w)| {
        matches!(w.lower.as_str(), "wish" | "wishes" | "wished")
            || (w.lower == "if"
                && words
                    .get(index + 1)
                    .is_some_and(|next| next.lower == "only"))
    }) else {
        return Vec::new();
    };

    let all = chains(words);
    let Some(chain) = all.iter().find(|c| c.words.start > marker) else {
        return Vec::new();
    };

    let Some(head) = chain.head() else {
        return Vec::new();
    };
    let (rule, explanation) = if head.link == Link::Modal && chain_has(words, chain, "would") {
        (
            "wish-would",
            "Недовольство чужим поведением: говорящий хочет, чтобы кто-то \
             делал что-то иначе, но повлиять не может",
        )
    } else if chain.is_perfect() {
        (
            "wish-past",
            "Сожаление о прошлом: этого не случилось или случилось иначе, \
             и изменить уже ничего нельзя",
        )
    } else {
        (
            "wish-present",
            "Сожаление о настоящем: прошедшее время после «wish» говорит \
             не о прошлом, а о том, что сейчас всё наоборот",
        )
    };

    vec![Finding::new(
        rule,
        "Желание, которое не сбудется",
        match rule {
            "wish-would" => "wish + would + V",
            "wish-past" => "wish + Past Perfect",
            _ => "wish + Past Simple",
        },
        explanation,
        words,
        marker..chain.words.end,
    )]
}

fn chain_has(words: &[Word], chain: &super::chain::Chain, modal: &str) -> bool {
    chain.auxiliaries().any(|part| {
        words
            .get(part.word)
            .is_some_and(|w| modal_base(&w.lower) == modal)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::Lexicon;
    use crate::tagger::tag;
    use crate::tokenizer::tokenize;

    fn правило(sentence: &str) -> Option<String> {
        let tokens = tokenize(sentence);
        let words = tag(Lexicon::embedded(), &tokens);
        detect(&words).first().map(|f| f.rule.to_string())
    }

    #[test]
    fn три_значения_различаются_по_форме() {
        assert_eq!(
            правило("I wish I knew the answer."),
            Some("wish-present".to_string())
        );
        assert_eq!(
            правило("She wishes she had studied harder."),
            Some("wish-past".to_string())
        );
        assert_eq!(
            правило("I wish you would stop smoking."),
            Some("wish-would".to_string())
        );
    }

    #[test]
    fn если_только_работает_как_желание() {
        assert_eq!(
            правило("If only I had known earlier!"),
            Some("wish-past".to_string())
        );
    }

    #[test]
    fn простое_пожелание_не_разбирается() {
        // «wish» без глагольной цепочки после себя — обычное пожелание удачи.
        assert!(правило("We wish you a merry Christmas.").is_none());
    }
}
