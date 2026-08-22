//! Границы предложений.
//!
//! Карточка переводит слово в контексте, и контекст — это ровно то
//! предложение, в котором слово стоит. Ошибиться границей здесь дорого:
//! обрежешь рано — DeepL получит огрызок и переведёт его мимо смысла;
//! склеишь два предложения — перевод станет длинным и невнятным.
//!
//! Наивное «точка кончает предложение» не работает ни на одной настоящей
//! странице: там есть «Mr. Darcy», инициалы «J. R. R. Tolkien», «3.14» и
//! многоточия. Поэтому точка считается концом, только если ни одна из
//! известных ловушек не сработала.

use super::{Token, TokenKind};
use std::ops::Range;

/// Предложение внутри текста.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sentence {
    /// Смещения в единицах UTF-16, как и у токенов.
    pub range: Range<usize>,
    /// Индексы токенов из переданного среза — полуинтервал.
    pub tokens: Range<usize>,
    pub text: String,
}

/// Сокращения, после которых точка почти никогда не кончает предложение.
///
/// Список короткий намеренно: это те случаи, что реально встречаются в
/// художественной прозе. Раздувать его до справочника незачем — ошибка на
/// редком сокращении стоит одного неудобного перевода, а не поломки чтения.
const ABBREVIATIONS: [&str; 22] = [
    "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "inc", "ltd", "co", "vol",
    "no", "fig", "ch", "ed", "pp", "am", "pm", "approx",
];

/// Собирает предложения из уже разобранных токенов.
pub fn split(tokens: &[Token]) -> Vec<Sentence> {
    let mut sentences = Vec::new();
    let mut start = 0;

    for index in 0..tokens.len() {
        if !is_terminator(tokens, index) {
            continue;
        }
        // Закрывающие кавычки и скобки принадлежат тому предложению, которое
        // они закрывают: «...paper.» — точка внутри реплики, а не начало
        // следующей фразы.
        let mut end = index + 1;
        while end < tokens.len() && is_closing(&tokens[end]) {
            end += 1;
        }
        if let Some(sentence) = build(tokens, start..end) {
            sentences.push(sentence);
        }
        start = end;
    }

    if let Some(sentence) = build(tokens, start..tokens.len()) {
        sentences.push(sentence);
    }

    sentences
}

/// Кончает ли токен предложение.
fn is_terminator(tokens: &[Token], index: usize) -> bool {
    let token = &tokens[index];
    match token.kind {
        // Конец абзаца кончает предложение независимо от знаков препинания.
        // Без этого заголовок главы прилипал бы к первой фразе — и уезжал бы
        // в контекст перевода вместе с ней.
        TokenKind::Space => token.text.matches('\n').count() >= 2,
        TokenKind::Punctuation => match token.text.as_str() {
            "!" | "?" | "\u{2026}" => true,
            "." => is_sentence_period(tokens, index),
            _ => false,
        },
        _ => false,
    }
}

/// Точка кончает предложение — или это сокращение, инициал, многоточие.
fn is_sentence_period(tokens: &[Token], index: usize) -> bool {
    // Многоточие из трёх точек: концом считается последняя, иначе получилось
    // бы три пустых предложения подряд.
    if tokens.get(index + 1).is_some_and(|next| next.text == ".") {
        return false;
    }

    if let Some(previous) = previous_word(tokens, index) {
        let lower = previous.to_lowercase();
        if ABBREVIATIONS.contains(&lower.as_str()) {
            return false;
        }
        // Инициал: одна заглавная буква с точкой — «J. R. R. Tolkien».
        if previous.chars().count() == 1 && previous.chars().next().is_some_and(char::is_uppercase)
        {
            return false;
        }
    }

    // Дальше должен начинаться текст. Конец абзаца — тоже конец предложения.
    let Some(next) = next_meaningful(tokens, index) else {
        return true;
    };
    match next.kind {
        // Строчная буква после точки — почти наверняка сокращение, которого
        // нет в списке: «...the U.S. government».
        TokenKind::Word => next.text.chars().next().is_none_or(|c| !c.is_lowercase()),
        _ => true,
    }
}

/// Слово прямо перед знаком, без пробела между ними.
fn previous_word(tokens: &[Token], index: usize) -> Option<&str> {
    let previous = tokens.get(index.checked_sub(1)?)?;
    (previous.kind == TokenKind::Word).then_some(previous.text.as_str())
}

/// Первый непробельный токен после знака.
fn next_meaningful(tokens: &[Token], index: usize) -> Option<&Token> {
    tokens[index + 1..]
        .iter()
        .find(|t| t.kind != TokenKind::Space && !is_closing(t))
}

/// Закрывающая кавычка или скобка.
fn is_closing(token: &Token) -> bool {
    token.kind == TokenKind::Punctuation
        && matches!(
            token.text.as_str(),
            "\"" | "'" | "\u{201d}" | "\u{2019}" | "\u{bb}" | ")" | "]" | "}"
        )
}

/// Собирает предложение из диапазона токенов, отбрасывая пустые и пробельные.
fn build(tokens: &[Token], range: Range<usize>) -> Option<Sentence> {
    let slice = tokens.get(range.clone())?;
    // Ведущие пробелы принадлежат предыдущему предложению не больше, чем
    // следующему; отдаём их следующему, но из границ убираем — иначе текст
    // предложения начинался бы с переводов строк.
    let first = slice.iter().position(|t| t.kind != TokenKind::Space)?;
    let last = slice.iter().rposition(|t| t.kind != TokenKind::Space)?;

    let tokens_range = (range.start + first)..(range.start + last + 1);
    let text: String = slice[first..=last]
        .iter()
        .map(|t| t.text.as_str())
        .collect();

    Some(Sentence {
        range: slice[first].range.start..slice[last].range.end,
        tokens: tokens_range,
        text,
    })
}

#[cfg(test)]
mod tests {
    use super::super::tokenize;
    use super::*;

    fn предложения(text: &str) -> Vec<String> {
        split(&tokenize(text)).into_iter().map(|s| s.text).collect()
    }

    #[test]
    fn обычные_предложения_разделяются() {
        assert_eq!(
            предложения("The door opened. Evelyn stepped in. Was it cold?"),
            vec!["The door opened.", "Evelyn stepped in.", "Was it cold?"]
        );
    }

    #[test]
    fn сокращения_не_режут_предложение() {
        assert_eq!(
            предложения("Mr. Darcy bowed to Mrs. Bennet."),
            vec!["Mr. Darcy bowed to Mrs. Bennet."]
        );
    }

    #[test]
    fn инициалы_не_режут_предложение() {
        assert_eq!(
            предложения("She read J. R. R. Tolkien all night."),
            vec!["She read J. R. R. Tolkien all night."]
        );
    }

    #[test]
    fn число_с_точкой_не_режет_предложение() {
        assert_eq!(
            предложения("The value was 3.14 exactly."),
            vec!["The value was 3.14 exactly."]
        );
    }

    #[test]
    fn многоточие_кончает_предложение_один_раз() {
        assert_eq!(
            предложения("She waited... Nothing came."),
            vec!["She waited...", "Nothing came."]
        );
    }

    #[test]
    fn закрывающая_кавычка_остаётся_в_своём_предложении() {
        assert_eq!(
            предложения("«Hello.» She smiled."),
            vec!["«Hello.»", "She smiled."]
        );
    }

    #[test]
    fn последнее_предложение_без_точки_не_теряется() {
        assert_eq!(
            предложения("The door opened. And then"),
            vec!["The door opened.", "And then"]
        );
    }

    #[test]
    fn границы_указывают_на_исходный_текст() {
        let text = "The door opened. Evelyn stepped in.";
        let tokens = tokenize(text);
        let sentences = split(&tokens);

        for sentence in &sentences {
            let restored: String = text
                .encode_utf16()
                .skip(sentence.range.start)
                .take(sentence.range.len())
                .collect::<Vec<u16>>()
                .iter()
                .filter_map(|u| char::from_u32(*u as u32))
                .collect();
            assert_eq!(restored, sentence.text);
        }
    }

    #[test]
    fn перевод_строки_разделяет_абзацы_но_не_ломает_предложение() {
        assert_eq!(
            предложения("The door opened.\n\nEvelyn stepped in."),
            vec!["The door opened.", "Evelyn stepped in."]
        );
    }

    #[test]
    fn заголовок_без_точки_не_прилипает_к_первой_фразе() {
        // Так глава приходит из парсера: заголовок и абзацы разделены пустой
        // строкой. Склейся они — заголовок уехал бы в контекст перевода.
        assert_eq!(
            предложения("The Catalogue\n\nMr. Ashton counted the children twice."),
            vec!["The Catalogue", "Mr. Ashton counted the children twice."]
        );
    }

    #[test]
    fn одиночный_перенос_строки_предложение_не_режет() {
        // Внутри абзаца перенос — это вёрстка, а не граница мысли.
        assert_eq!(
            предложения("The library smelled of dust,\nleather and old paper."),
            vec!["The library smelled of dust,\nleather and old paper."]
        );
    }

    #[test]
    fn пустой_текст_не_даёт_предложений() {
        assert!(предложения("").is_empty());
        assert!(предложения("   \n  ").is_empty());
    }
}
