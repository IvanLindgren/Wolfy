//! Эквиваленты модальных глаголов: «have to», «be able to», «be supposed
//! to», «be allowed to».
//!
//! Настоящие модальные глаголы не спрягаются, а английскому понадобились их
//! формы во всех временах — поэтому язык собрал замены из обычных слов. Для
//! читателя это ловушка вдвойне: «have» здесь значит «должен», а не «имею», и
//! без разбора фраза читается как обладание.
//!
//! Признаки у всех замен одинаковые: связка или «have» в личной форме, затем
//! частица «to» с начальной формой глагола. Различается только первое слово,
//! и объяснение называет его честно.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{Aux, Word};

use super::Finding;

/// Прилагательные и причастия после be, образующие эквивалент.
const BE_FORMS: [(&str, &str); 4] = [
    ("able", "умение или возможность: то же, что «can»"),
    ("unable", "отсутствие возможности: то же, что «can't»"),
    ("supposed", "ожидание или договорённость: так положено"),
    ("allowed", "разрешение: то же, что «may»"),
];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        // «have to»: обязанность. Соседство строгое — «to» сразу следом,
        // иначе это обычное «have» со своим дополнением.
        if matches!(word.lower.as_str(), "have" | "has" | "had") {
            let to_word = words.get(index + 1);
            let verb = words.get(index + 2);
            let to_follows = to_word.is_some_and(|w| w.aux == Some(Aux::To));
            let base_verb = verb.is_some_and(|v| v.has_form(VerbForm::Base) && v.pos == Pos::Verb);
            if to_follows && base_verb {
                out.push(Finding::new(
                    "modal-equivalent",
                    "Эквивалент модального глагола",
                    "have / has / had + to + V",
                    "Обязанность или необходимость: по смыслу это «must», только \
                     спрягается как обычный глагол и работает в любых временах",
                    words,
                    index..index + 3,
                ));
                continue;
            }
        }

        // «be able / supposed / allowed to»: связка слева, инфинитив справа.
        let Some((_, meaning)) = BE_FORMS
            .iter()
            .find(|(form, _)| *form == word.lower.as_str())
        else {
            continue;
        };
        let previous = index.checked_sub(1).and_then(|i| words.get(i));
        let (Some(next), Some(verb)) = (words.get(index + 1), words.get(index + 2)) else {
            continue;
        };
        if previous.is_some_and(|p| p.aux == Some(Aux::Be))
            && next.aux == Some(Aux::To)
            && verb.has_form(VerbForm::Base)
            && verb.pos == Pos::Verb
        {
            out.push(Finding::new(
                "modal-equivalent",
                "Эквивалент модального глагола",
                "be + able / supposed / allowed + to + V",
                format!(
                    "{meaning}. Модальный глагол собран из обычных слов, \
                         поэтому умеет спрягаться во всех временах"
                ),
                words,
                index - 1..index + 3,
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
    fn эквиваленты_находятся() {
        assert!(правила("She has to work on Sundays.").contains(&"modal-equivalent"));
        assert!(правила("He had to leave early.").contains(&"modal-equivalent"));
        assert!(правила("We will be able to come.").contains(&"modal-equivalent"));
        assert!(правила("You are supposed to wait here.").contains(&"modal-equivalent"));
        assert!(правила("Visitors are allowed to take photos.").contains(&"modal-equivalent"));
    }

    #[test]
    fn обладание_и_перфект_не_эквиваленты() {
        // «have» с существительным — просто владение.
        assert!(!правила("I have two books to read.").contains(&"modal-equivalent"));
        // Перфектная цепочка — время, а не обязанность.
        assert!(!правила("She has finished the work.").contains(&"modal-equivalent"));
    }
}
