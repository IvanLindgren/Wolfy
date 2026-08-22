//! Условные предложения: «If it rains, we stay home».
//!
//! Их четыре типа, и русскому читателю они даются тяжело по одной причине: в
//! русском все нереальные условия выглядят одинаково («если бы»), а в
//! английском форма глагола говорит, насколько условие невозможно. «If I had
//! money» — не про прошлое, а про то, что денег нет сейчас.
//!
//! Тип определяется парой времён: одно в придаточном, другое в главном.
//! Поэтому детектор ищет не одну цепочку, а две, и молчит, если второй нет, —
//! «if» без главного предложения бывает в обрывке фразы, и гадать там не о чем.
//!
//! Отдельно разбирается условие без «if»: «Had we known earlier». «If» здесь
//! опущено, а вспомогательный глагол встал перед подлежащим — по-русски так
//! не говорят вовсе, поэтому читателю это нуждается в объяснении больше всего.

use crate::lexicon::Pos;
use crate::tagger::{modal_base, Clause, Word};

use super::chain::{chains, Chain, Link};
use super::Finding;

/// Время придаточного — то, что различает типы.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Condition {
    /// «if it rains» — настоящее.
    Present,
    /// «if it rained» — прошедшее, но говорит о настоящем.
    Past,
    /// «if it had rained» — предпрошедшее.
    PastPerfect,
}

/// Чем отвечает главное предложение.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Result_ {
    /// «we stay» — настоящее.
    Present,
    /// «we will stay» — будущее.
    Will,
    /// «we would stay».
    Would,
    /// «we would have stayed».
    WouldHave,
}

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = pair(words).into_iter().collect::<Vec<_>>();
    out.extend(inverted(words));
    out
}

/// Обычное условие с маркером: «if», «unless», «in case», «as long as».
fn pair(words: &[Word]) -> Option<Finding> {
    let marker = condition_marker(words)?;

    let all = chains(words);
    // Граница придаточного — первая запятая после «if». Если её нет,
    // придаточное стоит вторым, и границей служит само «if».
    let boundary = words
        .iter()
        .enumerate()
        .skip(marker)
        .find(|(_, w)| w.breaks)
        .map(|(i, _)| i + 1)
        .unwrap_or(words.len());

    let inside = all
        .iter()
        .find(|c| c.words.start > marker && c.words.start < boundary);
    // Главное предложение — ближайшее к придаточному, а не первое в фразе.
    // В «He said he would have come if he had known» первым идёт «said», и
    // взять его значило бы сверить времена двух разных предложений.
    let outside = all
        .iter()
        .rev()
        .find(|c| c.words.end <= marker)
        .or_else(|| all.iter().find(|c| c.words.start >= boundary));

    let (Some(inside), Some(outside)) = (inside, outside) else {
        return None;
    };
    let (Some(condition), Some(result)) = (condition(inside), result(words, outside)) else {
        return None;
    };

    let (rule, title, formula, explanation) = match (condition, result) {
        (Condition::Present, Result_::Present) => (
            "conditional-zero",
            "Нулевое условие",
            "if + Present, Present",
            "Общее правило: так бывает всегда, когда выполняется условие",
        ),
        (Condition::Present, Result_::Will) => (
            "conditional-first",
            "Первое условие",
            "if + Present, will + V",
            "Условие настоящее и вполне может исполниться",
        ),
        (Condition::Past, Result_::Would) => (
            "conditional-second",
            "Второе условие",
            "if + Past, would + V",
            "Прошедшее время здесь не о прошлом: оно говорит, что сейчас это не так",
        ),
        (Condition::PastPerfect, Result_::WouldHave) => (
            "conditional-third",
            "Третье условие",
            "if + Past Perfect, would have + V3",
            "Сожаление о прошлом: этого не случилось, и изменить уже нечего",
        ),
        (Condition::PastPerfect, Result_::Would) => (
            "conditional-mixed",
            "Смешанное условие",
            "if + Past Perfect, would + V",
            "Условие о прошлом, а следствие — о настоящем: тогда не случилось, \
             и потому сейчас всё так",
        ),
        (Condition::Past, Result_::WouldHave) => (
            "conditional-mixed",
            "Смешанное условие",
            "if + Past Simple, would have + V3",
            "Части смешаны наоборот: условие всё ещё в силе, а следствие уже \
             случилось и его не изменить",
        ),
        // Пара времён, которой ни один тип не соответствует. Это чаще всего
        // не условие вовсе, а «if» в значении «ли», и разбирать там нечего.
        _ => return None,
    };

    let start = marker.min(outside.words.start);
    let end = outside.words.end.max(inside.words.end);

    Some(Finding::new(
        rule,
        title,
        formula,
        explanation,
        words,
        start..end,
    ))
}

/// Инвертированное условие: «Had we known earlier, we would have acted».
///
/// Признак строится из трёх частей, и каждая нужна: первое слово —
/// вспомогательный глагол, за ним сразу подлежащее, а дальше в главном
/// предложении стоит «would», «could» или «might». Без третьей проверки
/// «Had lunch, we went home» (обрывок записи) выглядело бы как условие.
fn inverted(words: &[Word]) -> Option<Finding> {
    let first = words.first()?;
    if !matches!(first.lower.as_str(), "should" | "were" | "had") {
        return None;
    }
    // После вспомогательного сразу стоит подлежащее — иначе это не инверсия,
    // а обычное начало предложения: «Had a book in hand, he left» — уже оно.
    let subject = words.get(1)?;
    if !matches!(subject.pos, Pos::Pronoun | Pos::Noun) {
        return None;
    }

    let all = chains(words);
    let result = all.iter().rev().find(|chain| match chain.head() {
        Some(head) if head.link == Link::Modal => matches!(
            words.get(head.word).map(|w| modal_base(&w.lower)),
            Some("would") | Some("could") | Some("might")
        ),
        _ => false,
    });

    // Главное предложение бывает и императивом без всякого «would»:
    // «Should you see him, tell him to call». Признак — начальная форма
    // глагола сразу после границы придаточного.
    let imperative = words.iter().enumerate().find_map(|(index, word)| {
        (index > 1 && word.breaks)
            .then_some(index + 1)
            .and_then(|next| {
                words.get(next).filter(|w| {
                    w.pos == Pos::Verb
                        && w.has_form(crate::lexicon::VerbForm::Base)
                        && w.aux.is_none()
                })
            })
    });

    let end = match (result, imperative) {
        (Some(chain), _) => chain.words.end,
        (None, Some(_)) => words.len().min(9),
        // Без следствия это не условие вовсе: «Had lunch, we went home».
        (None, None) => return None,
    };

    Some(Finding::new(
        "conditional-inversion",
        "Условие без «if»",
        "Should / Were / Had + подлежащее",
        "«If» опущено, а вспомогательный глагол встал перед подлежащим: \
         «had we known» значит «if we had known». По-русски так не говорят, \
         поэтому фраза выглядит непривычно",
        words,
        0..end,
    ))
}

/// Время придаточного по цепочке.
fn condition(chain: &Chain) -> Option<Condition> {
    let head = chain.head()?;
    if head.link == Link::Modal {
        return None;
    }
    Some(match (head.form, chain.is_perfect()) {
        (crate::tagger::AuxForm::Present, false) => Condition::Present,
        (crate::tagger::AuxForm::Past, true) => Condition::PastPerfect,
        (crate::tagger::AuxForm::Past, false) => Condition::Past,
        _ => return None,
    })
}

/// Чем отвечает главное предложение.
fn result(words: &[Word], chain: &Chain) -> Option<Result_> {
    let head = chain.head()?;

    if head.link == Link::Modal {
        let modal = words.get(head.word)?;
        return match modal_base(&modal.lower) {
            "will" | "shall" => Some(Result_::Will),
            "would" | "could" | "might" | "should" if chain.is_perfect() => {
                Some(Result_::WouldHave)
            }
            "would" | "could" | "might" | "should" => Some(Result_::Would),
            _ => None,
        };
    }

    (head.form == crate::tagger::AuxForm::Present && !chain.is_perfect())
        .then_some(Result_::Present)
}

/// Ищет слово или сочетание, вводящее условие.
///
/// Кроме «if» и «unless» условие вводят сочетания из двух слов — «in case»,
/// «as long as», «on condition that». Одно слово из пары ничего не значит:
/// «long» и «case» сами по себе условия не образуют, поэтому ищется именно
/// пара.
fn condition_marker(words: &[Word]) -> Option<usize> {
    if let Some(position) = words
        .iter()
        .position(|w| w.clause == Some(Clause::Condition))
    {
        return Some(position);
    }
    words.windows(2).position(|window| {
        matches!(
            (window[0].lower.as_str(), window[1].lower.as_str()),
            ("in", "case") | ("as", "long") | ("so", "long") | ("on", "condition")
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::Lexicon;
    use crate::tagger::tag;
    use crate::tokenizer::tokenize;

    fn разборы(sentence: &str) -> Vec<Finding> {
        let tokens = tokenize(sentence);
        let words = tag(Lexicon::embedded(), &tokens);
        super::super::analyze_words(&words)
    }

    #[test]
    fn смешанное_условие_работает_в_обе_стороны() {
        assert!(разборы("If she had asked, I would be there now.")
            .iter()
            .any(|f| f.rule == "conditional-mixed"));
        assert!(разборы("If I knew him better, I would have warned him.")
            .iter()
            .any(|f| f.rule == "conditional-mixed"));
    }

    #[test]
    fn альтернативы_иф_вводят_условие() {
        assert!(разборы("In case it rains, we will stay home.")
            .iter()
            .any(|f| f.rule == "conditional-first"));
        assert!(разборы("As long as it rains, we stay home.")
            .iter()
            .any(|f| f.rule == "conditional-zero"));
    }

    #[test]
    fn инвертированное_условие_находится() {
        assert!(разборы("Had we known earlier, we would have acted.")
            .iter()
            .any(|f| f.rule == "conditional-inversion"));
        assert!(разборы("Were she here, she would help us.")
            .iter()
            .any(|f| f.rule == "conditional-inversion"));
        // Обрывок без главного предложения не условие.
        assert!(!разборы("Had dinner, we went home.")
            .iter()
            .any(|f| f.rule == "conditional-inversion"));
    }
}
