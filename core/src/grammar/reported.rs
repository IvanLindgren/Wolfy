//! Косвенная речь и согласование времён: «He said he had finished».
//!
//! В английском при передаче чужих слов времена сдвигаются на шаг назад:
//! «I am tired» превращается в «he said he **was** tired», а «I have done it»
//! — в «he said he **had** done it». Русский этим не занимается, поэтому
//! читатель видит в тексте «странные» прошедшие времена и решает, что действие
//! произошло давно. Здесь как раз тот случай, когда разбор обязан вмешаться.
//!
//! Детектор ищет два надёжных признака: глагол передачи речи в прошедшем
//! рядом с перфектом или «would» (сдвиг времени) — либо рядом с вопросным
//! словом (косвенный вопрос без инверсии).

use crate::tagger::{modal_base, Word};

use super::chain::{chains, Link};
use super::Finding;

/// Глаголы передачи речи в прошедшем времени.
const REPORTING_VERBS: [&str; 14] = [
    "said",
    "told",
    "asked",
    "wondered",
    "explained",
    "added",
    "replied",
    "answered",
    "admitted",
    "complained",
    "promised",
    "warned",
    "advised",
    "announced",
];

/// Вопросные слова косвенного вопроса.
const WH_WORDS: [&str; 8] = [
    "where", "when", "why", "how", "what", "who", "whether", "if",
];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let all = chains(words);

    for (index, word) in words.iter().enumerate() {
        if !REPORTING_VERBS.contains(&word.lower.as_str()) {
            continue;
        }

        // Косвенный вопрос: вопросное слово в пределах двух позиций после
        // глагола речи. Инверсии там нет, и читателю это видно плохо.
        let indirect_question = (1..=2usize).any(|offset| {
            words
                .get(index + offset)
                .is_some_and(|w| WH_WORDS.contains(&w.lower.as_str()))
        });
        if indirect_question {
            return vec![Finding::new(
                "reported-speech",
                "Косвенная речь",
                "сказал / спросил + вопросное слово + подлежащее + V",
                "Это чужие слова, переданные своими. Порядок слов здесь прямой, \
                 как в обычном предложении: «asked where I lived», а не \
                 «asked where did I live»",
                words,
                index..(index + 4).min(words.len()),
            )];
        }

        // Сдвиг времён: после глагола речи стоит перфект («had finished») или
        // «would/could/might» («would come»). Прошедшее время здесь значит
        // «на шаг раньше чужих слов», а не просто «давно».
        let shifted = all
            .iter()
            .find(|c| c.words.start > index && c.words.start <= index + 5 && backshift(words, c));
        if let Some(chain) = shifted {
            return vec![Finding::new(
                "reported-speech",
                "Согласование времён",
                "said / told + подлежащее + had V3 / would + V",
                "Это чужие слова, переданные позже. Времена ушли на шаг назад: \
                 чужое «have done» стало «had done», чужое «will» стало \
                 «would». Действие вовсе не обязано быть древним",
                words,
                index..chain.words.end,
            )];
        }
    }

    Vec::new()
}

/// Признак сдвига: перфект от прошедшего «had» или модальный «would».
fn backshift(words: &[Word], chain: &super::chain::Chain) -> bool {
    let Some(head) = chain.head() else {
        return false;
    };
    if head.link == Link::Modal {
        return matches!(
            words.get(head.word).map(|w| modal_base(&w.lower)),
            Some("would") | Some("could") | Some("might")
        );
    }
    chain.is_perfect() && head.form == crate::tagger::AuxForm::Past
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
    fn сдвиг_времён_находится() {
        assert!(правила("He said he had finished the work.").contains(&"reported-speech"));
        assert!(правила("She said she would come tomorrow.").contains(&"reported-speech"));
    }

    #[test]
    fn косвенный_вопрос_без_инверсии_находится() {
        assert!(правила("He asked where I lived.").contains(&"reported-speech"));
        assert!(правила("She wondered if he was ready.").contains(&"reported-speech"));
    }

    #[test]
    fn прямая_речь_и_обычные_слова_молчат() {
        // Глагол речи без придаточного — не косвенная речь.
        assert!(!правила("He said hello and left.").contains(&"reported-speech"));
        assert!(!правила("She asked a question.").contains(&"reported-speech"));
    }
}
