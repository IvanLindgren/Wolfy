//! Относительные придаточные: «the book that changed my life»,
//! «my brother, who lives in Rome, …».
//!
//! Придаточное цепляется к существительному через who, whom, whose, which или
//! that — и на письме различается только запятой. Без запятой это определение,
//! без которого смысл существительного неполон («какая именно книга?»); с
//! запятой — попутная подробность, которую можно выбросить. В переводе разница
//! огромна, а читатель её не видит вовсе.
//!
//! Слово «that» многолико: оно бывает и союзом («said that he came»), и
//! указательным местоимением («that book»). Поэтому правило требует, чтобы
//! слева стояло существительное-антецедент, а справа начиналось сказуемое.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::Word;

use super::Finding;

/// Слова, соединяющие придаточное с существительным.
const RELATIVES: [&str; 5] = ["who", "whom", "whose", "which", "that"];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        if !RELATIVES.contains(&word.lower.as_str()) {
            continue;
        }
        // Антецедент слева обязан быть существительным (или местоимением):
        // без него «that» — союз при глаголе речи либо указательное местоимение.
        let antecedent = match index.checked_sub(1).and_then(|i| words.get(i)) {
            Some(previous) if matches!(previous.pos, Pos::Noun | Pos::Pronoun) => previous,
            _ => continue,
        };
        // За относительным словом начинается придаточное: сразу же или через
        // подлежащее («that I read»), у whose — ещё и через имя («whose book
        // won»). Цепочка здесь не помощник: словарь помечает «changed»
        // прилагательным, хотя роль прошедшего времени у слова есть.
        let verb_index = (1..=3usize).find_map(|gap| {
            let candidate = words.get(index + gap)?;
            // Между относительным словом и глаголом стоит подлежащее —
            // местоимение или существительное, но никак не второй глагол.
            let clean_gap = (index + 1..index + gap)
                .all(|between| matches!(words[between].pos, Pos::Pronoun | Pos::Noun));
            // Чистые существительные («book») несут только неоднозначную
            // начальную форму, а глагол в придаточном обязан иметь личную
            // роль — прошедшее или третье лицо. Это различает «won» и «book»,
            // даже когда словарь помечает оба существительными.
            let personal =
                candidate.has_form(VerbForm::Past) || candidate.has_form(VerbForm::ThirdPerson);
            let finite = candidate.is_finite_verb() && (personal || candidate.pos != Pos::Noun);
            (clean_gap && finite).then_some(gap)
        });
        let Some(verb_index) = verb_index else {
            continue;
        };

        // Запятая перед относительным словом разделяет два типа придаточных.
        let nondefining = index > 0 && words[index - 1].breaks;
        let (rule, title, formula, explanation): (
            &'static str,
            &'static str,
            &'static str,
            String,
        ) = if nondefining {
            (
                "relative-nondefining",
                "Пояснительное придаточное",
                "…, who / which / that + глагол …",
                "Запятая здесь важна: придаточное — попутная подробность об \
                     уже понятном существительном, и его можно выбросить без \
                     потери смысла"
                    .to_string(),
            )
        } else {
            (
                "relative-defining",
                "Определительное придаточное",
                "существительное + who / which / that + глагол",
                format!(
                    "Придаточное определяет слово «{}»: без него непонятно, \
                         о ком или о чём речь. Запятой нет — выбросить нельзя",
                    antecedent.lower
                ),
            )
        };

        out.push(Finding::new(
            rule,
            title,
            formula,
            explanation,
            words,
            index..index + verb_index + 1,
        ));
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
    fn определительное_придаточное_находится() {
        assert!(правила("The book that changed my life was cheap.").contains(&"relative-defining"));
        assert!(
            правила("The man who I saw yesterday is my neighbor.").contains(&"relative-defining")
        );
        assert!(
            правила("The author whose book won the prize is young.").contains(&"relative-defining")
        );
    }

    #[test]
    fn незаменимое_отличается_запятой() {
        assert!(правила("My brother, who lives in Rome, is a doctor.")
            .contains(&"relative-nondefining"));
    }

    #[test]
    fn союз_и_указательное_местоимение_не_разбираются() {
        // «that» после глагола речи — союз, не относительное слово.
        assert!(!правила("He said that he came late.").contains(&"relative-defining"));
        // «that» в начале фразы — указательное местоимение.
        assert!(!правила("That book was good.").contains(&"relative-defining"));
    }
}
