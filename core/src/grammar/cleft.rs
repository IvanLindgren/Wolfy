//! Расщеплённые предложения: «It was John who broke the window»,
//! «What I need is a cup of coffee».
//!
//! Английский выделяет слово не интонацией, а конструкцией: важное выносится
//! в отдельную рамку «It is … that …» или разворачивает фразу задом наперёд
//! через «What … is …». Читатель видит лишние слова и думает, что переводчик
//! корявит, — хотя это ровно тот же смысл, только с ударением.
//!
//! Детекторы узкие. Рамка обязана содержать существительное («It was **John**
//! who…») — иначе «It is important that…» выглядит как cleft, хотя это просто
//! оценка с придаточным.

use crate::lexicon::Pos;
use crate::tagger::{Aux, Word};

use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();
    out.extend(cleft_it(words));
    out.extend(cleft_what(words));
    out
}

/// Cleft с рамкой «It is/was … that/who/which …».
fn cleft_it(words: &[Word]) -> Option<Finding> {
    if words.first()?.lower != "it" {
        return None;
    }
    let be_word = words.get(1)?;
    if be_word.aux != Some(Aux::Be) {
        return None;
    }

    // Ищем соединительное слово и проверяем, что перед ним стоит именно
    // выделяемое существительное или местоимение, а не прилагательное-оценка.
    for gap in 2..=6usize {
        let connector = words.get(gap)?;
        if !matches!(connector.lower.as_str(), "that" | "who" | "whom" | "which") {
            continue;
        }
        let emphasized = words.get(gap - 1)?;
        if !emphasized.candidates.contains(Pos::Noun) && emphasized.pos != Pos::Pronoun {
            continue;
        }

        return Some(Finding::new(
            "cleft-it",
            "Расщеплённое предложение",
            "It is / It was + выделяемое + that / who …",
            "Важное слово вынесено в рамку «it is … that»: ударение падает \
             именно на него. По-русски то же делают голосом или словом \
             «именно»: разбил окно именно Джон",
            words,
            0..gap + 1,
        ));
    }
    None
}

/// Cleft с перевёрнутой рамкой: «What I need is a cup of coffee»,
/// «All she wanted was peace».
fn cleft_what(words: &[Word]) -> Option<Finding> {
    let first = words.first()?.lower.as_str();
    if !matches!(first, "what" | "all") {
        return None;
    }

    // Между «what» и связкой обязан жить целый глагол: «What I **need** is…».
    // В вопросе «What time is it?» между ними только существительное — там
    // это просто вопрос, а не выделение.
    let mut has_clause_verb = false;
    for index in 1..words.len() {
        let word = &words[index];
        let is_be = word.aux == Some(Aux::Be)
            && matches!(
                words.get(index).map(|w| w.lower.as_str()),
                Some("is") | Some("was")
            );
        if is_be && has_clause_verb {
            return Some(Finding::new(
                "cleft-what",
                "Расщеплённое предложение",
                "What / All + подлежащее + глагол + is / was …",
                "Фраза собрана вокруг главного слова: сначала сказано, чего \
                 хочется, а потом названо само оно. По-русски — «как раз то, \
                 что нужно»",
                words,
                0..index + 1,
            ));
        }
        if word.pos == Pos::Verb {
            has_clause_verb = true;
        }
    }
    None
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
    fn рамка_it_was_находится() {
        assert!(правила("It was John who broke the window.").contains(&"cleft-it"));
        assert!(правила("It is the book that changed my mind.").contains(&"cleft-it"));
        // Оценка с придаточным — не расщепление.
        assert!(!правила("It is important that he be present.").contains(&"cleft-it"));
        // Обычное «it is + прилагательное» тоже.
        assert!(!правила("It is raining.").contains(&"cleft-it"));
    }

    #[test]
    fn перевёрнутая_рамка_находится() {
        assert!(правила("What I need is a cup of coffee.").contains(&"cleft-what"));
        assert!(правила("All she wanted was peace.").contains(&"cleft-what"));
        // Вопрос с what — не расщепление.
        assert!(!правила("What time is it?").contains(&"cleft-what"));
    }
}
