//! Неличные формы: инфинитив и герундий.
//!
//! Это те случаи, где глагол в предложении есть, а сказуемым не работает:
//! «I want **to read**», «He is good at **reading**». Русскому читателю они
//! мешают ровно потому, что в русском на их месте стоит либо неопределённая
//! форма, либо отглагольное существительное, и выбор между ними в английском
//! делается по другим правилам.
//!
//! У инфинитива, кроме простого, есть три сложные формы — перфектная
//! («to have finished»), пассивная («to be done») и продолженная
//! («to be working»): внутри них прячется целое сказуемое со своим временем
//! и залогом, и разобрать их простым правилом «to + V» значило бы потерять
//! половину смысла.
//!
//! Причастные обороты живут соседним модулем [`super::participle`]: там свои
//! признаки и своя осторожность.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{Aux, Word};

use super::chain::chains;
use super::Finding;

/// Глаголы, после которых герундий обязателен, а инфинитив меняет смысл:
/// «he stopped smoking» — бросил курить, «he stopped to smoke» — остановился,
/// чтобы закурить. Список закрытый.
const GERUND_VERBS: [&str; 24] = [
    "enjoy",
    "enjoys",
    "enjoyed",
    "avoid",
    "avoids",
    "avoided",
    "mind",
    "minds",
    "minded",
    "admit",
    "admits",
    "admitted",
    "suggest",
    "suggests",
    "suggested",
    "keep",
    "keeps",
    "kept",
    "finish",
    "finishes",
    "finished",
    "risk",
    "risks",
    "risked",
];

/// Глаголы восприятия и понуждения, после которых инфинитив обходится без
/// «to»: «made him laugh», «saw her leave». Объект между ними может занять
/// пару слов — «made my brother laugh», — поэтому окно поиска шире одного.
const BARE_TRIGGERS: [&str; 16] = [
    "make", "makes", "made", "let", "lets", "let's", "see", "sees", "saw", "hear", "hears",
    "heard", "watch", "watches", "watched", "felt",
];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    // Слова, уже занятые сказуемым: разбирать их второй раз незачем, а
    // «is reading» иначе попало бы и во время, и в герундий. Отдельно
    // запоминаются служебные позиции цепочки — не последние звенья: «has
    // finished working» обязано остаться перфектом, а не стать герундием.
    let mut busy = vec![false; words.len()];
    let mut busy_aux = vec![false; words.len()];
    for chain in chains(words) {
        let last = chain.words.end.saturating_sub(1);
        for index in chain.words.clone() {
            if let Some(slot) = busy.get_mut(index) {
                *slot = true;
            }
            if index < last {
                if let Some(slot) = busy_aux.get_mut(index) {
                    *slot = true;
                }
            }
        }
    }

    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        if word.aux == Some(Aux::To) {
            if let Some(finding) = complex_infinitive(words, index) {
                out.push(finding);
                continue;
            }

            // Простой инфинитив: «to» плюс начальная форма глагола. Предлог
            // «to» сюда не попадает — за предлогом стоит существительное,
            // а не глагол.
            let next = words.get(index + 1);
            if next.is_some_and(|n| n.has_form(VerbForm::Base) && n.pos == Pos::Verb) {
                out.push(Finding::new(
                    "infinitive",
                    "Инфинитив",
                    "to + V",
                    "Действие названо, но не приписано никому и никакому времени: \
                     так глагол работает дополнением к другому глаголу",
                    words,
                    index..index + 2,
                ));
            }
            continue;
        }

        // Герундий после глаголов обязательного герундия: «he avoided answering».
        // Соседство прямое, без предлога; иначе это уже другой случай. Служебная
        // позиция цепочки («has finished…») триггером быть не может.
        if !busy_aux[index] && GERUND_VERBS.contains(&word.lower.as_str()) {
            let next = words.get(index + 1);
            let gerund_next = next.is_some_and(|n| {
                !busy[index + 1] && n.has_form(VerbForm::Gerund) && n.aux.is_none()
            });
            if gerund_next {
                out.push(Finding::new(
                    "gerund-verb",
                    "Герундий после глагола",
                    "enjoy / avoid / suggest… + V-ing",
                    "Этот глагол требует рядом форму на «-ing». С инфинитивом \
                     смысл меняется или фраза разваливается: «avoid doing», \
                     а не «avoid to do»",
                    words,
                    index..index + 2,
                ));
                continue;
            }
        }

        if busy[index] || !word.has_form(VerbForm::Gerund) {
            continue;
        }

        // Герундий: форма на «-ing» на месте существительного. Надёжный
        // признак один — предлог слева: «at reading», «without asking».
        // После «to» его тоже видно, но там «to» предлог, а не частица, и
        // различает их как раз форма справа.
        let previous = index.checked_sub(1).and_then(|i| words.get(i));
        let after_preposition =
            previous.is_some_and(|p| p.pos == Pos::Preposition || p.aux == Some(Aux::To));
        if after_preposition {
            out.push(Finding::new(
                "gerund",
                "Герундий",
                "предлог + V-ing",
                "Глагол стоит на месте существительного. По-русски здесь было бы \
                 отглагольное существительное или неопределённая форма",
                words,
                index..index + 1,
            ));
        }
    }

    out.extend(bare_infinitive(words));
    out
}

/// Сложные формы инфинитива: перфектная, пассивная, продолженная.
///
/// Проверяются раньше простой формы: иначе «to have finished» разобралось бы
/// как «to have» плюс лишнее слово, и читатель не увидел бы ни завершённости,
/// ни того, что действие поставлено раньше другого.
fn complex_infinitive(words: &[Word], index: usize) -> Option<Finding> {
    let second = words.get(index + 1)?;

    // Перфектный инфинитив: «to have finished».
    if second.lower == "have" {
        let third = words.get(index + 2)?;
        if third.pos == Pos::Verb && third.has_form(VerbForm::Participle) {
            return Some(Finding::new(
                "perfect-infinitive",
                "Перфектный инфинитив",
                "to have + V3",
                "Действие поставлено раньше другого действия или момента: \
                 сначала оно случилось (или не случилось), потом всё остальное",
                words,
                index..index + 3,
            ));
        }
    }

    // Пассивный и продолженный инфинитив: «to be done», «to be working».
    // Различает их форма третьего слова — причастие или «-ing».
    if second.lower == "be" {
        let third = words.get(index + 2)?;
        if third.pos != Pos::Verb {
            return None;
        }
        if third.has_form(VerbForm::Participle) {
            return Some(Finding::new(
                "passive-infinitive",
                "Пассивный инфинитив",
                "to be + V3",
                "Действие названо так, будто его совершат над кем-то: \
                 «the work needs to be done» — работу должны сделать, \
                 а не она сделает",
                words,
                index..index + 3,
            ));
        }
        if third.has_form(VerbForm::Gerund) {
            return Some(Finding::new(
                "continuous-infinitive",
                "Продолженный инфинитив",
                "to be + V-ing",
                "Действие названо идущим прямо сейчас или в тот самый момент",
                words,
                index..index + 3,
            ));
        }
    }

    None
}

/// Bare Infinitive: «He made me laugh», «She saw him leave the house».
///
/// После make, let и глаголов восприятия «to» не ставится, и русскому
/// читателю это видно плохо: по-русски оба глагола просто идут подряд.
/// Признак — начальная форма глагола через одно-два слова после триггера;
/// окно нужно, чтобы пропустить короткое дополнение.
fn bare_infinitive(words: &[Word]) -> Option<Finding> {
    for (index, word) in words.iter().enumerate() {
        if !BARE_TRIGGERS.contains(&word.lower.as_str()) {
            continue;
        }
        for gap in 1..=3usize {
            let candidate = match words.get(index + gap) {
                Some(candidate) => candidate,
                None => break,
            };
            // «to» означает обычный инфинитив — это чужой паттерн, не наш.
            if candidate.aux == Some(Aux::To) {
                break;
            }
            // Начальная форма глагола — в том числе служебного: «let him be».
            let is_base = candidate.has_form(VerbForm::Base) && candidate.pos == Pos::Verb;
            if is_base {
                return Some(Finding::new(
                    "bare-infinitive",
                    "Инфинитив без «to»",
                    "make / let / see / hear + объект + V",
                    "После этих глаголов «to» не нужно: «made him laugh», \
                     а не «made him to laugh». По-русски оба глагола стоят \
                     в личной форме, поэтому разница незаметна",
                    words,
                    index..index + gap + 1,
                ));
            }
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
    fn сложные_формы_инфинитива_различаются() {
        assert!(правила("She is glad to have finished the work.").contains(&"perfect-infinitive"));
        assert!(правила("The work needs to be done today.").contains(&"passive-infinitive"));
        assert!(правила("He seems to be working hard.").contains(&"continuous-infinitive"));
    }

    #[test]
    fn простой_инфинитив_не_теряется() {
        assert!(правила("She wants to read the book.").contains(&"infinitive"));
    }

    #[test]
    fn голый_инфинитив_после_make_and_let() {
        assert!(правила("He made me laugh.").contains(&"bare-infinitive"));
        assert!(правила("She saw him leave the house.").contains(&"bare-infinitive"));
        assert!(правила("Let's go home.").contains(&"bare-infinitive"));
    }

    #[test]
    fn герундий_после_специальных_глаголов() {
        assert!(правила("He avoided answering directly.").contains(&"gerund-verb"));
        assert!(правила("She suggested going home.").contains(&"gerund-verb"));
    }

    #[test]
    fn ложных_срабатываний_нет() {
        // За «to» стоит существительное — это предлог, не частица.
        assert!(!правила("She went to the library.").contains(&"perfect-infinitive"));
        // «have» с существительным — не перфектный инфинитив.
        assert!(!правила("I want to have a rest.").contains(&"perfect-infinitive"));
        // Герундийные глаголы без герундия рядом молчат.
        assert!(!правила("They finished the work.").contains(&"gerund-verb"));
        // Триггер bare-инфинитива с существительным дальше — молчит.
        assert!(!правила("He made a promise.").contains(&"bare-infinitive"));
    }
}
