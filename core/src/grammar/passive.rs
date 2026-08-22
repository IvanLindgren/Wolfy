//! Страдательный залог: «The window was broken».
//!
//! Признак простой: служебное «be» плюс третья форма смыслового глагола. Но у
//! этого признака есть известная слабость, и молчать о ней нельзя.
//!
//! «She was tired» устроено ровно так же, как «The window was broken», а
//! значит совсем другое: не «её утомили», а «она была уставшей». Третья форма
//! в английском давно работает и прилагательным, и различить эти два чтения по
//! одной только форме невозможно — нужен смысл, которого у движка нет.
//!
//! Поэтому детектор срабатывает, но, когда причастие есть в словаре ещё и
//! прилагательным, объяснение честно говорит о втором чтении. Промолчать было
//! бы хуже: страдательный залог — одна из тех вещей, ради которых читатель и
//! открывает разбор, и терять его на каждом втором предложении нельзя.

use crate::lexicon::{Lexicon, Pos};
use crate::tagger::Word;

use super::chain::chains;
use super::Finding;

pub fn detect(words: &[Word]) -> Vec<Finding> {
    chains(words)
        .iter()
        .filter(|chain| chain.is_passive())
        .filter_map(|chain| {
            let main = chain.main()?;
            let participle = words.get(main.word)?;

            // «by» после причастия снимает всякую двусмысленность: у состояния
            // деятеля не бывает. «The window was broken by the wind» — залог,
            // и оговорка про прилагательное здесь только мешала бы.
            let by_phrase = words
                .get(main.word + 1)
                .is_some_and(|w| w.lower == "by");

            Some(Finding::new(
                "passive-voice",
                "Страдательный залог",
                "be + V3",
                if by_phrase {
                    "Подлежащее не действует само — действие совершают над ним, а «by» прямо называет, кто именно"
                        .to_string()
                } else {
                    explain(participle)
                },
                words,
                chain.words.clone(),
            ))
        })
        .collect()
}

fn explain(participle: &Word) -> String {
    let base = "Подлежащее не действует само — действие совершают над ним";

    if also_adjective(participle) {
        format!(
            "{base}. Но «{}» бывает и прилагательным, и тогда фраза говорит \
             не о действии, а о состоянии — что именно, видно по смыслу",
            participle.lower
        )
    } else {
        base.to_string()
    }
}

/// Известно ли причастие словарю ещё и как прилагательное.
fn also_adjective(word: &Word) -> bool {
    if word.candidates.contains(Pos::Adjective) {
        return true;
    }
    Lexicon::embedded()
        .entry(&word.lower)
        .is_some_and(|entry| entry.pos.contains(Pos::Adjective))
}
