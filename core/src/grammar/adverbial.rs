//! Обстоятельственные придаточные: уступки, причины и цели.
//!
//! Это самые частые «объяснительные» слова текста: хотя, потому что, чтобы.
//! По-русски они выглядят безобидно, но в английском за каждым стоит свой
//! порядок слов и свои ограничения («in order to» требует одного подлежащего,
//! «so that» — своего глагола). Читателю полезно видеть не сам союз, а то,
//! что фраза дальше будет обосновывать или противопоставлять.
//!
//! Правила маркерные и оттого дешёвые: они находят слово-маркер и сообщают,
//! какую роль играет то, что за ним. Границы придаточного здесь не нужны —
//! читатель видит его глазами лучше любой эвристики.

use crate::tagger::Word;

use super::Finding;

/// Союзы уступки: одно слово.
///
/// «While» сюда не входит: уступку он вводит заметно реже, чем время
/// («while I was reading»), и объявить противопоставлением каждое «пока»
/// значило бы промахиваться чаще, чем попадать.
const CONCESSION_WORDS: [&str; 4] = ["although", "though", "whereas", "despite"];

/// Союзы причины: одно слово.
const REASON_WORDS: [&str; 1] = ["because"];

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for (index, word) in words.iter().enumerate() {
        // Цель: «in order to», «so as to», «so that». Пара обязана кончаться
        // инфинитивом или «that»: иначе «so as» ничего не значит.
        if index + 2 < words.len() {
            let pair = (word.lower.as_str(), words[index + 1].lower.as_str());
            let third = words[index + 2].lower.as_str();
            let purpose = matches!(pair, ("in", "order") | ("so", "as") | ("so", "that"))
                && (third == "to" || (pair == ("so", "that")));
            if purpose {
                out.push(Finding::new(
                    "purpose-clause",
                    "Придаточное цели",
                    "in order to / so as to / so that + …",
                    "Дальше объясняется, зачем совершается действие. После \
                     «in order to» и «so as to» стоит начальная форма глагола",
                    words,
                    index..index + 3,
                ));
                continue;
            }

            // Причина из двух слов: «due to», «because of», «owing to»,
            // «thanks to». За ними идёт существительное, а не глагол.
            let reason_pair = matches!(
                pair,
                ("due", "to") | ("because", "of") | ("owing", "to") | ("thanks", "to")
            );
            if reason_pair {
                out.push(Finding::new(
                    "reason-clause",
                    "Обстоятельство причины",
                    "due to / because of / owing to + …",
                    "Дальше называется причина случившегося. После этих сочетаний \
                     стоит существительное: «из-за дождя», а не «потому что \
                     дождь пошёл»",
                    words,
                    index..index + 2,
                ));
                continue;
            }
        }

        // Уступка из одного слова.
        if CONCESSION_WORDS.contains(&word.lower.as_str()) {
            out.push(Finding::new(
                "concession-clause",
                "Уступительное придаточное",
                "although / despite / whereas + …",
                "Дальше идёт противопоставление: факт признаётся, но вывод он \
                 не меняет. «Although it rained» — дождь был, и всё же…",
                words,
                index..index + 1,
            ));
            continue;
        }
        // Причина из одного слова.
        if REASON_WORDS.contains(&word.lower.as_str()) {
            out.push(Finding::new(
                "reason-clause",
                "Придаточное причины",
                "because + подлежащее + глагол",
                "Дальше объясняется причина. После «because» стоит целое \
                 предложение с глаголом, в отличие от «because of» с \
                 существительным",
                words,
                index..index + 1,
            ));
        }
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
    fn три_роли_различаются() {
        assert!(правила("Although it rained, we went out.").contains(&"concession-clause"));
        assert!(правила("The game was canceled because of the rain.").contains(&"reason-clause"));
        assert!(правила("She left early because she was tired.").contains(&"reason-clause"));
        assert!(правила("He stood up in order to see better.").contains(&"purpose-clause"));
        assert!(
            правила("We whispered so that the baby would not wake.").contains(&"purpose-clause")
        );
    }

    #[test]
    fn обычные_слова_не_разбираются() {
        // «as» слишком многолик, поэтому в маркеры не входит вовсе.
        assert!(!правила("As a child, he lived in Rome.").contains(&"reason-clause"));
    }
}
