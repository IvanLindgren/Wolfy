//! Разбивка фразы на блоки для конструктора.

use crate::grammar::Finding;
use crate::tokenizer::Token;
use std::ops::Range;

/// Сколько блоков — предел для конструктора.
///
/// Больше — и упражнение из книжной фразы превращается в мозаику. Такие
/// фразы просто не предлагаются в колоду.
pub const MAX_BLOCKS: usize = 9;

/// Длина цепочки сказуемого в словах, по которой ещё имеет смысл склеивать.
///
/// Разбор длиннее покрывает половину предложения (условное правило — обе), и
/// склеивать по нему значит выдать читателю одну плитку вместо задания.
///
/// Считается именно в словах. В клиенте на Kotlin этот же предел мерился в
/// индексах токенов, а между словами стоят токены-пробелы — «have been
/// reading» занимает пять индексов, а не три, и в предел не проходило ничто
/// длиннее двух слов. У разбора в ядре есть отдельный диапазон в словах, и
/// двойник на клиенте его просто не показывал.
const MAX_CHAIN: usize = 4;

/// Служебные слова, которые тянут за собой следующее.
///
/// Список закрытый и короткий: это не разбор языка, а вёрстка плиток.
/// Ошибиться здесь не страшно — блок получится длиннее или короче, но
/// ответ от этого не изменится.
const GLUE: [&str; 37] = [
    "a", "an", "the", //
    "my", "your", "his", "her", "its", "our", "their", //
    "this", "that", "these", "those", //
    "of", "in", "on", "at", "to", "for", "with", "from", "by", "into", //
    "about", "over", "under", "after", "before", "through", "between", //
    "no", "some", "any", "every", "each", "another",
];

/// Режет предложение на блоки.
///
/// Блок — это кусок, который в голове хранится целиком: «have been reading»,
/// «for a month», «this book». Рассыпать фразу по одному слову было бы проще
/// всего, но тогда упражнение перестаёт быть про язык и становится про
/// терпение: десять плиток из «I have been reading this book for a month»
/// собираются перебором, а четыре — пониманием.
///
/// Границы берутся из двух источников. Глагольные цепочки приходят от того же
/// разбора, что работает в читалке, — он уже знает, что «have been reading»
/// это одно сказуемое. Остальное склеивается служебными словами: предлог и
/// артикль не стоят сами по себе, они всегда чему-то предшествуют.
///
/// * `tokens` — разбор предложения токенами; берутся только те, по которым
///   можно тапнуть, то есть слова.
/// * `findings` — грамматические разборы этого же предложения: их границы и
///   есть границы сказуемых.
pub fn blocks(tokens: &[Token], findings: &[Finding]) -> Vec<Range<usize>> {
    let words: Vec<&Token> = tokens.iter().filter(|token| token.is_tappable()).collect();
    if words.is_empty() {
        return Vec::new();
    }

    // Цепочки сказуемого: короткие разборы, которые не растягиваются на всё
    // предложение.
    let glued: Vec<&std::ops::Range<usize>> = findings
        .iter()
        .filter(|finding| {
            let span = finding.words.end.saturating_sub(finding.words.start);
            (1..=MAX_CHAIN).contains(&span)
        })
        .map(|finding| &finding.words)
        .collect();

    let mut result: Vec<Range<usize>> = Vec::new();
    let mut glue_next = false;

    for (index, token) in words.iter().enumerate() {
        let chain = glued.iter().find(|range| range.contains(&index));
        let text = token.text.as_str();

        match chain {
            // Середина цепочки — приклеивается к её началу.
            Some(range) if index > range.start && !result.is_empty() => {
                if let Some(block) = result.last_mut() {
                    block.end = index + 1;
                }
            }
            // Начало цепочки: открывает блок и обрывает склейку служебным.
            Some(_) => {
                result.push(index..index + 1);
                glue_next = false;
            }
            // Предыдущее слово было служебным и ждёт продолжения.
            None if glue_next && !result.is_empty() => {
                if let Some(block) = result.last_mut() {
                    block.end = index + 1;
                }
                glue_next = is_glue(text);
            }
            None => {
                result.push(index..index + 1);
                glue_next = is_glue(text);
            }
        }
    }

    result
}

/// Режет предложение на текстовые блоки для тренировки.
///
/// Границы живут в [`blocks`], чтобы интерфейс разбора фразы и конструктор
/// карточек никогда не расходились в том, что считают одним куском.
pub fn split(tokens: &[Token], findings: &[Finding]) -> Vec<String> {
    let words: Vec<&Token> = tokens.iter().filter(|token| token.is_tappable()).collect();
    blocks(tokens, findings)
        .iter()
        .map(|range| {
            words[range.clone()]
                .iter()
                .map(|token| token.text.as_str())
                .collect::<Vec<_>>()
                .join(" ")
        })
        .collect()
}

fn is_glue(text: &str) -> bool {
    let lower = text.to_lowercase();
    GLUE.contains(&lower.as_str())
}

/// Годится ли фраза для конструктора.
///
/// Проверяется перед тем, как предложить сохранить её в колоду: обещать
/// тренировку, а потом не смочь её собрать — хуже, чем не обещать.
pub fn trainable(blocks: &[String]) -> bool {
    (2..=MAX_BLOCKS).contains(&blocks.len())
}

/// Сходятся ли собранное и исходное.
///
/// Сравниваются слова, а не строки: читатель собирает из плиток, между
/// которыми пробелы ставит интерфейс, а в исходной фразе есть ещё и знаки
/// препинания, которых на плитках нет.
pub fn same(assembled: &str, expected: &str) -> bool {
    normalize(assembled) == normalize(expected)
}

fn normalize(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .filter(|ch| ch.is_alphanumeric() || ch.is_whitespace() || *ch == '\'')
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tokenizer::tokenize;

    /// Разбор сказуемого с границами в индексах токенов.
    ///
    /// Настоящий приходит из грамматики; здесь важна не она, а вёрстка
    /// плиток — то, как блоки собираются из уже найденных границ.
    fn цепочка(первое: usize, последнее: usize) -> Finding {
        Finding {
            rule: "test",
            title: "test",
            formula: "",
            explanation: String::new(),
            words: первое..(последнее + 1),
            tokens: слово(первое)..(слово(последнее) + 1),
        }
    }

    /// Индексы токенов по номерам слов: между словами стоят пробелы, и
    /// «второе слово» — это четвёртый токен.
    fn слово(n: usize) -> usize {
        n * 2
    }

    #[test]
    fn сказуемое_остаётся_одним_блоком() {
        let sentence = "I have been reading this book for a month";
        let blocks = split(&tokenize(sentence), &[цепочка(1, 3)]);
        assert!(
            blocks.iter().any(|b| b == "have been reading"),
            "цепочка сказуемого рассыпалась: {blocks:?}"
        );
    }

    #[test]
    fn служебное_слово_тянет_за_собой_следующее() {
        let sentence = "I have been reading this book for a month";
        let blocks = split(&tokenize(sentence), &[цепочка(1, 3)]);
        assert!(
            blocks.iter().any(|b| b == "this book"),
            "определитель отвалился: {blocks:?}"
        );
        assert!(
            blocks.iter().any(|b| b == "for a month"),
            "предлог отвалился: {blocks:?}"
        );
    }

    #[test]
    fn блоки_складываются_обратно_в_предложение() {
        let sentence = "She left the library at dusk";
        let blocks = split(&tokenize(sentence), &[]);
        assert_eq!(blocks.join(" "), sentence);
    }

    #[test]
    fn разбор_во_всё_предложение_не_склеивает_его_в_один_блок() {
        // Условное правило покрывает обе половины фразы, и склеивать по нему —
        // значит выдать читателю одну плитку вместо задания.
        let sentence = "If I had known I would have called you";
        let blocks = split(&tokenize(sentence), &[цепочка(0, 8)]);
        assert!(
            blocks.len() > 1,
            "предложение стало одним блоком: {blocks:?}"
        );
    }

    #[test]
    fn слишком_дробная_фраза_в_колоду_не_идёт() {
        assert!(!trainable(&["a".to_string()]));
        let мозаика: Vec<String> = (0..12).map(|n| n.to_string()).collect();
        assert!(!trainable(&мозаика));
        assert!(trainable(&[
            "I".to_string(),
            "have been reading".to_string(),
            "this book".to_string(),
        ]));
    }

    #[test]
    fn сверка_не_придирается_к_знакам_и_регистру() {
        assert!(same(
            "I have been reading this book",
            "I have been reading this book."
        ));
        assert!(same("she left", "She left!"));
        assert!(same("she  left", "She left"));
        assert!(!same("she leaves", "She left"));
    }
}
