//! Эмфаза и инверсия: «He does love you», «Never have I seen», «So do I».
//!
//! В английском порядок слов жёсткий, и когда он ломается — это всегда сигнал:
//! говорящий либо выделяет слово голосом, либо выносит на первое место самое
//! важное. Читателю этот сигнал не виден вовсе: глазами он читает обычное
//! предложение и пропускает оттенок, ради которого фраза так построена.
//!
//! Общий признак у всех правил один — служебный глагол оказался впереди
//! того места, где ему положено стоять. Различаются только причины.

use crate::lexicon::Pos;
use crate::tagger::Word;

use super::chain::{chains, Link};
use super::Finding;

/// Наречия и сочетания, с которых начинается отрицательная инверсия.
const NEGATIVE_OPENERS: [&str; 8] = [
    "never", "seldom", "rarely", "hardly", "scarcely", "little", "nor", "neither",
];

/// Наречия места и направления для инверсии после них.
const PLACE_OPENERS: [&str; 7] = ["here", "there", "down", "up", "out", "away", "back"];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let all = chains(words);

    let mut out = Vec::new();
    out.extend(emphatic_do(words, &all));
    out.extend(negative_inversion(words));
    out.extend(place_inversion(words));
    out.extend(echo_response(words));
    out
}

/// Эмфатический do: «He does love you», «She did come after all».
///
/// В утвердительном предложении вспомогательному «do» взяться неоткуда — оно
/// появляется только ради ударения. Вопрос («Does he love you?») узнаётся по
/// позиции в самом начале, а отрицание («he does not love») видно цепочке;
/// оба случая разбор оставляет другим правилам.
fn emphatic_do(words: &[Word], all: &[super::chain::Chain]) -> Option<Finding> {
    let chain = all.iter().find(|c| {
        matches!(c.head().map(|head| head.link), Some(Link::Do))
            && c.main().is_some()
            && !c.is_negative()
    })?;
    let head_word = chain.head()?.word;

    // Вопрос начинается со служебного глагола; косвенный вопрос — с вопросного
    // слова прямо перед ним. Оба случая эмфазой не являются.
    if head_word == 0 {
        return None;
    }
    if let Some(previous) = words.get(head_word - 1) {
        if matches!(
            previous.lower.as_str(),
            "what" | "where" | "when" | "why" | "who" | "whom" | "whose" | "which" | "how"
        ) {
            return None;
        }
    }

    Some(Finding::new(
        "emphatic-do",
        "Эмфатическое do",
        "подлежащее + do/does/did + V",
        "Вспомогательный глагол здесь стоит ради ударения: действие \
         действительно было или действительно есть. По-русски ударение \
         передаётся голосом, а тут — лишним словом",
        words,
        chain.words.clone(),
    ))
}

/// Отрицательная инверсия: «Never have I seen such a mess»,
/// «No sooner had I arrived than it started raining».
///
/// Отрицательное наречие в начале фразы требует перестановки: служебный
/// глагол встаёт перед подлежащим. Признак — наречие первым словом, а следом
/// цепочка, начинающаяся со служебного звена, а не со смыслового глагола.
fn negative_inversion(words: &[Word]) -> Option<Finding> {
    // Маркер занимает одно слово («never») или два («no sooner»).
    let marker_end = if NEGATIVE_OPENERS.contains(&words.first()?.lower.as_str()) {
        1
    } else {
        match (
            words.first()?.lower.as_str(),
            words.get(1).map(|w| w.lower.as_str()),
        ) {
            ("no", Some("sooner")) | ("not", Some("only")) => 2,
            _ => return None,
        }
    };

    let auxiliary = words.get(marker_end)?;
    if auxiliary.aux.is_none() || auxiliary.pos != Pos::Verb {
        return None;
    }
    // Инверсия — это когда служебный глагол обогнал подлежащее, и подлежащее
    // обязано стоять сразу за ним. В «Little is known about him» за «is»
    // идёт причастие: подлежащее там «little», порядок слов обычный, и
    // никакой инверсии нет.
    let subject = words.get(marker_end + 1)?;
    if !matches!(subject.pos, Pos::Pronoun | Pos::Noun)
        && !subject.candidates.contains(Pos::Determiner)
    {
        return None;
    }
    // Короткий отклик «Neither do I» разбирает своё правило.
    if words.len() <= 3 && words.get(2).is_some_and(|w| w.pos == Pos::Pronoun) {
        return None;
    }

    let explanation = match marker_end {
        2 => {
            "«No sooner … than» и «Not only … but also» выносят отрицание \
              вперёд и заставляют служебный глагол встать перед подлежащим"
        }
        _ => {
            "Отрицательное наречие в начале фразы меняет порядок слов: \
              служебный глагол стоит перед подлежащим, как в вопросе, но \
              вопроса здесь нет — это усиление"
        }
    };
    let end = (marker_end + 3).min(words.len());

    Some(Finding::new(
        "inversion-negative",
        "Инверсия после отрицания",
        "Never / Seldom / No sooner + служебный глагол + подлежащее",
        explanation,
        words,
        0..end,
    ))
}

/// Инверсия места и направления: «Here comes the bus», «Down fell the rain».
///
/// Подлежащее приходит в конец фразы, а глагол встаёт вторым словом.
/// Цепочка тут снова не помощник: словарь помечает «comes» существительным,
/// хотя роль третьего лица у слова есть. Поэтому признак читается прямо
/// с токенов: наречие места, личная форма глагола, определитель подлежащего.
fn place_inversion(words: &[Word]) -> Option<Finding> {
    let first = words.first()?;
    if !PLACE_OPENERS.contains(&first.lower.as_str()) {
        return None;
    }

    // Второе слово — конечная форма смыслового глагола. Связка be отсекается
    // сразу: «There was a house» и «Here is your coffee» — это конструкция
    // there is/are, а не инверсия.
    let verb = words.get(1)?;
    if verb.aux.is_some() || !verb.is_finite_verb() {
        return None;
    }
    // Подлежащее идёт следом и начинается с определителя.
    let subject = words.get(2)?;
    if !subject.candidates.contains(Pos::Determiner) {
        return None;
    }

    Some(Finding::new(
        "inversion-place",
        "Инверсия места",
        "Here / Down / Away + глагол + подлежащее",
        "Подлежащее ушло в конец фразы: «Here comes the bus» — вот и идёт \
         автобус. Порядок слов сломан ради динамики: действие разворачивается \
         прямо сейчас",
        words,
        0..3,
    ))
}

/// Короткий отклик: «So do I», «Neither can she».
///
/// Целое предложение сжато до трёх слов, и глагол стоит до подлежащего.
/// Без разбора «So do I» выглядит загадкой: что делает «do»?
fn echo_response(words: &[Word]) -> Option<Finding> {
    let first = words.first()?.lower.as_str();
    if !matches!(first, "so" | "neither" | "nor") {
        return None;
    }
    // Дальше — служебный глагол и местоимение: «So do I».
    let auxiliary = words.get(1)?;
    if auxiliary.aux.is_none() || auxiliary.pos != Pos::Verb {
        return None;
    }
    if words.get(2)?.pos != Pos::Pronoun {
        return None;
    }

    let meaning = if first == "so" {
        "«Я тоже»: то же верно и обо мне"
    } else {
        "«Я тоже нет»: то же неверно и обо мне"
    };

    Some(Finding::new(
        "inversion-echo",
        "Короткий отклик",
        "So / Neither + служебный глагол + местоимение",
        format!(
            "{meaning}. Служебный глагол повторяет время первого \
                 предложения, поэтому он стоит до подлежащего"
        ),
        words,
        0..3,
    ))
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
    fn эмфатическое_do_отличается_от_вопроса_и_отрицания() {
        assert!(правила("He does love you.").contains(&"emphatic-do"));
        assert!(правила("She did come after all.").contains(&"emphatic-do"));
        assert!(!правила("Does he love you?").contains(&"emphatic-do"));
        assert!(!правила("What does he want?").contains(&"emphatic-do"));
        assert!(!правила("She does not love you.").contains(&"emphatic-do"));
    }

    #[test]
    fn отрицательная_инверсия_находится() {
        assert!(правила("Never have I seen such a mess.").contains(&"inversion-negative"));
        assert!(правила("Seldom does he come here.").contains(&"inversion-negative"));
        assert!(правила("No sooner had I arrived than it started raining.")
            .contains(&"inversion-negative"));
        // Обычный порядок слов — не инверсия.
        assert!(!правила("I have never seen such a mess.").contains(&"inversion-negative"));
        // Повелительное после «never» — тоже.
        assert!(!правила("Never say never.").contains(&"inversion-negative"));
    }

    #[test]
    fn инверсия_места_и_конструкция_there_are() {
        assert!(правила("Here comes the bus.").contains(&"inversion-place"));
        assert!(правила("Down fell the rain.").contains(&"inversion-place"));
        // «There was a house» — there is/are, а не инверсия.
        assert!(!правила("There was a house.").contains(&"inversion-place"));
    }

    #[test]
    fn короткие_отклики_различаются() {
        assert!(правила("So do I.").contains(&"inversion-echo"));
        assert!(правила("Neither can she.").contains(&"inversion-echo"));
        // «So the king decided…» — обычное «и вот тогда».
        assert!(!правила("So the king decided to leave.").contains(&"inversion-echo"));
    }
}
