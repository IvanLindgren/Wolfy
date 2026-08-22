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

use crate::tagger::{Clause, Word};

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
    let Some(marker) = words
        .iter()
        .position(|w| w.clause == Some(Clause::Condition))
    else {
        return Vec::new();
    };

    let chains = chains(words);
    // Граница придаточного — первая запятая после «if». Если её нет,
    // придаточное стоит вторым, и границей служит само «if».
    let boundary = words
        .iter()
        .enumerate()
        .skip(marker)
        .find(|(_, w)| w.breaks)
        .map(|(i, _)| i + 1)
        .unwrap_or(words.len());

    let inside = chains
        .iter()
        .find(|c| c.words.start > marker && c.words.start < boundary);
    // Главное предложение — ближайшее к придаточному, а не первое в фразе.
    // В «He said he would have come if he had known» первым идёт «said», и
    // взять его значило бы сверить времена двух разных предложений.
    let outside = chains
        .iter()
        .rev()
        .find(|c| c.words.end <= marker)
        .or_else(|| chains.iter().find(|c| c.words.start >= boundary));

    let (Some(inside), Some(outside)) = (inside, outside) else {
        return Vec::new();
    };
    let (Some(condition), Some(result)) = (condition(inside), result(words, outside)) else {
        return Vec::new();
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
        // Пара времён, которой ни один тип не соответствует. Это чаще всего
        // не условие вовсе, а «if» в значении «ли», и разбирать там нечего.
        _ => return Vec::new(),
    };

    let start = marker.min(outside.words.start);
    let end = outside.words.end.max(inside.words.end);

    vec![Finding::new(
        rule,
        title,
        formula,
        explanation,
        words,
        start..end,
    )]
}

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

fn result(words: &[Word], chain: &Chain) -> Option<Result_> {
    let head = chain.head()?;

    if head.link == Link::Modal {
        let modal = words.get(head.word)?;
        return match modal.lower.as_str() {
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
