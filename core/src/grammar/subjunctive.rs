//! Сослагательное наклонение: «It is important that he be present».
//!
//! Это тот случай, где английский ставит начальную форму глагола там, где по
//! всем остальным правилам положено «is» или «goes». Читатель, привыкший к
//! «he is», видит «he be» и решает, что в тексте опечатка, — поэтому разбор
//! здесь обязан успокоить: так и задумано.
//!
//! Детектор узкий и сознательно: наклонение узнаётся только после слов
//! требования или предложения («important», «suggested») с союзом «that».
//! В остальных местах «be» рядом с местоимением значит что угодно — от
//! повелительного до будущего, и гадать не стоит.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::Word;

use super::Finding;

/// Слова требования, совета или предложения, после которых наклонение
/// обязано быть. Список закрытый: он не растёт вместе с текстом.
const TRIGGERS: [&str; 24] = [
    "important",
    "essential",
    "vital",
    "necessary",
    "crucial",
    "imperative",
    "suggested",
    "suggest",
    "suggests",
    "recommended",
    "recommend",
    "recommends",
    "demanded",
    "demand",
    "demands",
    "required",
    "require",
    "requires",
    "insisted",
    "insist",
    "insists",
    "proposed",
    "propose",
    "proposes",
];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        if !TRIGGERS.contains(&word.lower.as_str()) {
            continue;
        }
        // Схема: триггер + that + подлежащее + начальная форма. Подлежащее
        // занимает слово («that she go») или два («that the bill pass») —
        // длиннее в этой конструкции оно не бывает.
        let Some(that) = words.get(index + 1) else {
            continue;
        };
        if that.lower != "that" {
            continue;
        }
        let verb_at = (3..=4usize).find(|&offset| {
            let subject = (index + 2..index + offset).all(|position| {
                words.get(position).is_some_and(|w| {
                    matches!(
                        w.pos,
                        Pos::Pronoun | Pos::Noun | Pos::Numeral | Pos::Determiner | Pos::Adjective
                    )
                })
            });
            // Начальная форма и есть признак наклонения. Формы третьего лица
            // («he arrives») её не имеют — и правильно: это уже изъявительное
            // наклонение, разбирать его этим правилом значило бы наврать.
            let verb = words.get(index + offset).is_some_and(|v| {
                v.has_form(VerbForm::Base)
                    && !v.has_form(VerbForm::ThirdPerson)
                    && (v.pos == Pos::Verb || v.aux.is_some())
            });
            subject && verb
        });
        let Some(verb_at) = verb_at else {
            continue;
        };

        out.push(Finding::new(
            "subjunctive-mood",
            "Сослагательное наклонение",
            "важно / предложено + that + подлежащее + V",
            "После слов требования и предложения глагол стоит в начальной \
             форме: «that he be present» — правильно, это не опечатка и не \
             ошибка в тексте",
            words,
            index..index + verb_at + 1,
        ));
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::Lexicon;
    use crate::tagger::tag;
    use crate::tokenizer::tokenize;

    fn сработало(sentence: &str) -> bool {
        let tokens = tokenize(sentence);
        let words = tag(Lexicon::embedded(), &tokens);
        detect(&words).iter().any(|f| f.rule == "subjunctive-mood")
    }

    #[test]
    fn наклонение_находится() {
        assert!(сработало("It is important that he be present."));
        assert!(сработало("I suggest that she go alone."));
        assert!(сработало("The doctor insisted that she take the medicine."));
    }

    #[test]
    fn изъявительное_наклонение_не_разбирается() {
        // Третье лицо с окончанием -s — уже не сослагательное наклонение.
        assert!(!сработало("It is important that he arrives on time."));
        // Без «that» правила нет.
        assert!(!сработало("It is important to arrive on time."));
    }
}
