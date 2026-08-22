//! Сложные синтаксические конструкции: Complex Object, Complex Subject и
//! каузатив.
//!
//! Их объединяет одно: в предложении два глагола, и русскому читателю кажется,
//! что это две фразы или ошибка. «I want you to stay» — не «я хочу тебя»,
//! а «я хочу, чтобы ты остался». По-русски тут придаточное с «чтобы», и
//! без объяснения конструкция выглядит как лишний инфинитив.
//!
//! Все три детектора узкие: они требуют точного соседства слов, потому что
//! ложный разбор здесь хуже пропуска вдвойне — читатель и так напряжён.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{Aux, Word};

use super::chain::chains;
use super::Finding;

/// Глаголы желания и ожидания для Complex Object.
const WANT_VERBS: [&str; 9] = [
    "want", "wants", "wanted", "expect", "expects", "expected", "ask", "asks", "asked",
];

/// Глаголы передачи чужого мнения для Complex Subject: «is said to be».
const OPINION_PARTICIPLES: [&str; 8] = [
    "said",
    "believed",
    "known",
    "thought",
    "expected",
    "reported",
    "assumed",
    "considered",
];

/// Глаголы кажимости: собственное наблюдение говорящего.
const SEEM_VERBS: [&str; 10] = [
    "seem", "seems", "seemed", "appear", "appears", "appeared", "happen", "happens", "happened",
    "proved",
];

/// Прилагательные вероятности: «is likely to arrive».
const LIKELY_ADJECTIVES: [&str; 4] = ["likely", "unlikely", "sure", "certain"];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let all = chains(words);

    let mut out = Vec::new();
    out.extend(complex_object(words));
    out.extend(causative(words, &all));
    out.extend(complex_subject(words));
    out
}

/// Complex Object: «I want you to stay», «She expects him to call».
///
/// Признак — инфинитив через одно-два слова после глагола желания. Прямое
/// соседство («I want to read») сюда не попадает нарочно: это обычный
/// инфинитив при одном подлежащем, и другого разбора он не требует.
fn complex_object(words: &[Word]) -> Option<Finding> {
    for (index, word) in words.iter().enumerate() {
        if !WANT_VERBS.contains(&word.lower.as_str()) {
            continue;
        }
        // Объект между глаголом и «to»: минимум одно слово, максимум три.
        for gap in 2..=4usize {
            let candidate = match words.get(index + gap) {
                Some(candidate) => candidate,
                None => break,
            };
            if candidate.aux != Some(Aux::To) {
                continue;
            }
            // Не «?»: конец предложения за этим «to» обрывает проверку, а не
            // весь поиск — глагол желания в этой фразе может быть не один.
            let Some(verb) = words.get(index + gap + 1) else {
                break;
            };
            if verb.pos == Pos::Verb && verb.has_form(VerbForm::Base) {
                return Some(Finding::new(
                    "complex-object",
                    "Сложное дополнение",
                    "want / expect + объект + to + V",
                    "Два действия с разными исполнителями: говорящий хочет, \
                     чтобы кто-то другой что-то сделал. По-русски здесь было бы \
                     придаточное с «чтобы»",
                    words,
                    index..index + gap + 2,
                ));
            }
        }
    }
    None
}

/// Каузатив: «I had my car repaired», «She got her hair cut».
///
/// Действие совершает не подлежащее, а нанятый кто-то: машину чинил мастер.
/// Признак — третья форма глагола через одно-два слова после have/get.
/// Прямое соседство исключено нарочно: «had repaired» это просто Past Perfect.
fn causative(words: &[Word], all: &[super::chain::Chain]) -> Option<Finding> {
    for (index, word) in words.iter().enumerate() {
        if !matches!(
            word.lower.as_str(),
            "have" | "has" | "had" | "get" | "gets" | "got"
        ) {
            continue;
        }
        // Чужим считается только причастие из той же глагольной цепочки, что
        // и сам триггер: «had repaired» — это перфект. А причастие в своей,
        // отдельной цепочке — как раз наш случай: теггер видит в «cut» из
        // «got her hair cut» второе сказуемое, хотя это чья-то работа.
        let trigger_chain = all.iter().find(|c| c.words.contains(&index));
        let same_chain = |check: usize| trigger_chain.is_some_and(|c| c.words.contains(&check));
        for gap in 2..=3usize {
            // Между have/get и причастием стоит объект. Если там местоимение в
            // именительном падеже, это подлежащее, а не объект: «Never have I
            // seen» — инверсия, у неё свой разбор.
            if matches!(
                words.get(index + 1).map(|w| w.lower.as_str()),
                Some("i") | Some("he") | Some("she") | Some("we") | Some("they")
            ) {
                break;
            }
            let candidate = match words.get(index + gap) {
                Some(candidate) => candidate,
                None => break,
            };
            let is_participle =
                candidate.has_form(VerbForm::Participle) && !same_chain(index + gap);
            if is_participle {
                return Some(Finding::new(
                    "causative",
                    "Каузативная конструкция",
                    "have / get + объект + V3",
                    "Действие совершил не тот, о ком идёт речь, а кто-то нанятый: \
                     машину чинил мастер, волосы стриг парикмахер. По-русски — \
                     «мне починили», а не «я починил»",
                    words,
                    index..index + gap + 1,
                ));
            }
        }
    }
    None
}

/// Complex Subject: «He is believed to be rich», «She seems to know»,
/// «They are likely to arrive on time».
///
/// Три семейства триггеров говорят одно и то же по-разному: чужое мнение,
/// собственное впечатление и вероятность. Разбор называет именно то семейство,
/// которое встретилось, вместо общей формулировки про «сложное подлежащее».
fn complex_subject(words: &[Word]) -> Option<Finding> {
    for (index, word) in words.iter().enumerate() {
        let previous = index.checked_sub(1).and_then(|i| words.get(i));

        let (trigger_start, family) = if OPINION_PARTICIPLES.contains(&word.lower.as_str()) {
            match previous {
                Some(p) if p.aux == Some(Aux::Be) => (
                    index - 1,
                    "Говорят или считают, что дело обстоит именно так: \
                     чужое мнение вынесено наружу через пассив",
                ),
                _ => continue,
            }
        } else if SEEM_VERBS.contains(&word.lower.as_str()) {
            (
                index,
                "Кажется, выглядит или оказывается так: впечатление \
                 говорящего, а не факт",
            )
        } else if LIKELY_ADJECTIVES.contains(&word.lower.as_str()) {
            match previous {
                Some(p) if p.aux == Some(Aux::Be) => (
                    index - 1,
                    "Вероятность: так скорее всего и будет, хотя гарантии нет",
                ),
                _ => continue,
            }
        } else {
            continue;
        };

        // Дальше обязана быть инфинитивная связка: «to» плюс начальная форма,
        // возможно через наречие — «seems always to know».
        for gap in 1..=2usize {
            let Some(to_word) = words.get(index + gap) else {
                break;
            };
            if to_word.aux != Some(Aux::To) {
                continue;
            }
            if let Some(verb) = words.get(index + gap + 1) {
                if verb.pos == Pos::Verb && verb.has_form(VerbForm::Base) {
                    return Some(Finding::new(
                        "complex-subject",
                        "Сложное подлежащее",
                        "подлежащее + is said / seems / is likely + to + V",
                        family,
                        words,
                        trigger_start..index + gap + 2,
                    ));
                }
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
    fn сложное_дополнение_находится() {
        assert!(правила("I want you to stay.").contains(&"complex-object"));
        assert!(правила("She expects him to call tomorrow.").contains(&"complex-object"));
        // Инфинитив с одним подлежащим — не сложное дополнение.
        assert!(!правила("I want to read the book.").contains(&"complex-object"));
    }

    #[test]
    fn каузатив_находится_и_отличается_от_перфекта() {
        assert!(правила("I had my car repaired yesterday.").contains(&"causative"));
        assert!(правила("She got her hair cut.").contains(&"causative"));
        // «had repaired» — это Past Perfect, не каузатив.
        assert!(!правила("He had repaired the car before noon.").contains(&"causative"));
    }

    #[test]
    fn сложное_подлежащее_во_всех_трёх_семействах() {
        assert!(правила("He is believed to be rich.").contains(&"complex-subject"));
        assert!(правила("She seems to know the answer.").contains(&"complex-subject"));
        assert!(правила("They are likely to arrive on time.").contains(&"complex-subject"));
    }
}
