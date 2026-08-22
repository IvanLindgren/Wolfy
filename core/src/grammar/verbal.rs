//! Неличные формы: инфинитив и герундий.
//!
//! Это те случаи, где глагол в предложении есть, а сказуемым не работает:
//! «I want **to read**», «He is good at **reading**». Русскому читателю они
//! мешают ровно потому, что в русском на их месте стоит либо неопределённая
//! форма, либо отглагольное существительное, и выбор между ними в английском
//! делается по другим правилам.
//!
//! Детектор нарочно узкий. Причастные обороты («the man **standing** there»,
//! «**broken** by the wind») сюда не входят: отличить их от продолженного
//! времени и страдательного залога можно только по строению предложения,
//! которого движок пока не строит, а ложное срабатывание хуже пропуска.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{Aux, Word};

use super::chain::chains;
use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    // Слова, уже занятые сказуемым: разбирать их второй раз незачем, а
    // «is reading» иначе попало бы и во время, и в герундий.
    let mut busy = vec![false; words.len()];
    for chain in chains(words) {
        for index in chain.words.clone() {
            if let Some(slot) = busy.get_mut(index) {
                *slot = true;
            }
        }
    }

    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        // Инфинитив: «to» плюс начальная форма глагола. Предлог «to» сюда не
        // попадает — за предлогом стоит существительное, а не глагол.
        if word.aux == Some(Aux::To) {
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

    out
}
