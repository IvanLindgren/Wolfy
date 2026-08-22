//! Конструкции сравнения: «as … as», «the more … the more».
//!
//! По-русски «такой же, как» звучит естественно, а вот английская рамка
//! «as … as» читается с трудом: между двумя «as» прячется прилагательное,
//! и непонятно, что с ним делать. Правило показывает рамку целиком.

use crate::lexicon::Pos;
use crate::tagger::Word;

use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();
    out.extend(as_as(words));
    out.extend(the_more(words));
    out
}

/// Равенство и отрицательное неравенство: «as old as», «not so old as».
fn as_as(words: &[Word]) -> Option<Finding> {
    for index in 0..words.len().saturating_sub(2) {
        // Первая часть рамки: «as» или «so» после отрицания.
        let first = &words[index];
        let opens = first.lower == "as"
            || (first.lower == "so"
                && index > 0
                && matches!(words[index - 1].lower.as_str(), "not"));
        if !opens {
            continue;
        }
        let middle = words.get(index + 1)?;
        // Часть речи ненадёжна: словарь помечает «tall» и существительным.
        // Достаточно того, что слово бывает прилагательным или наречием.
        let gradeable = middle.candidates.contains(Pos::Adjective)
            || middle.candidates.contains(Pos::Adverb)
            || matches!(middle.pos, Pos::Adjective | Pos::Adverb);
        if !gradeable {
            continue;
        }
        if words.get(index + 2)?.lower != "as" {
            continue;
        }

        return Some(Finding::new(
            "comparison-as-as",
            "Сравнение равенства",
            "as + прилагательное + as / not so … as",
            "Рамка из двух «as» означает «такой же по признаку»: одинаково \
             старый, столь же быстро. С отрицанием — наоборот, «не такой… как»",
            words,
            index..index + 3,
        ));
    }
    None
}

/// Пропорция: «The more you read, the more you know».
fn the_more(words: &[Word]) -> Vec<Finding> {
    let mut marks: Vec<usize> = Vec::new();

    for index in 0..words.len().saturating_sub(1) {
        let pair = (words[index].lower.as_str(), words[index + 1].lower.as_str());
        if matches!(
            pair,
            ("the", "more") | ("the", "less") | ("the", "better") | ("the", "worse")
        ) {
            marks.push(index);
        }
    }
    if marks.len() < 2 {
        return Vec::new();
    }

    vec![Finding::new(
        "comparison-the-more",
        "Двойное сравнение",
        "the + сравнительная степень … , the + сравнительная степень …",
        "Обе части растут вместе: чем больше одно, тем больше другое. \
         По-русски — «чем … , тем …»",
        words,
        marks[0]..marks[marks.len() - 1] + 2,
    )]
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
    fn рамки_сравнения_находятся() {
        assert!(правила("She is as tall as her mother.").contains(&"comparison-as-as"));
        assert!(правила("It is not so difficult as I thought.").contains(&"comparison-as-as"));
        assert!(правила("The more you read, the more you know.").contains(&"comparison-the-more"));
    }

    #[test]
    fn одиночное_слово_не_рамка() {
        // Одно «as» без пары — просто предлог или союз.
        assert!(!правила("As a child, he lived in Rome.").contains(&"comparison-as-as"));
        assert!(!правила("I like tea more than coffee.").contains(&"comparison-the-more"));
    }
}
