//! Разбивка текста на предложения и кликабельные токены.
//!
//! По результату этой разбивки читатель тыкает пальцем, поэтому у токенизатора
//! два жёстких требования. Первое — ничего не терять: сумма всех токенов
//! обязана давать исходный текст символ в символ, иначе подсветка уедет от
//! слова. Второе — знать границы предложений: карточка переводит слово в
//! контексте, и контекст берётся именно отсюда.
//!
//! ## О смещениях
//!
//! Позиции считаются в единицах UTF-16, а не в байтах UTF-8, как принято в
//! Rust. Причина одна: единственный потребитель — клиент на Kotlin, где строки
//! и есть UTF-16. Отдавай ядро байтовые смещения, клиенту пришлось бы
//! пересчитывать их на каждую отрисовку кадра.

mod sentences;

pub use sentences::{split, Sentence};

use std::ops::Range;

/// Что за кусок текста перед нами.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenKind {
    /// Слово: по нему можно тапнуть и получить карточку.
    Word,
    /// Число: «1925», «3.14».
    Number,
    /// Знак препинания.
    Punctuation,
    /// Пробелы и переводы строк.
    Space,
}

/// Кусок текста с позицией.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Token {
    pub kind: TokenKind,
    /// Смещения в единицах UTF-16 от начала переданного текста.
    pub range: Range<usize>,
    /// Текст токена. Копия нужна редко, поэтому хранится срез исходной строки.
    pub text: String,
}

impl Token {
    /// Можно ли по токену тапнуть ради карточки.
    pub fn is_tappable(&self) -> bool {
        self.kind == TokenKind::Word
    }
}

/// Разбивает текст на токены, ничего не теряя.
///
/// Слово — это буквы вместе с внутренними апострофами и дефисами: «don't» и
/// «well-known» остаются целыми, потому что читатель тыкает в них как в одно
/// слово. А вот тире между словами («слово — слово») словом не становится:
/// от дефиса оно отличается тем, что окружено пробелами.
pub fn tokenize(text: &str) -> Vec<Token> {
    let mut tokens = Vec::new();
    let chars: Vec<char> = text.chars().collect();
    let mut index = 0;
    // Позиция в UTF-16 идёт своим счётчиком: у эмодзи и редких символов один
    // `char` занимает две единицы, и подсветка на клиенте съедет, если считать
    // символы.
    let mut offset = 0;

    while index < chars.len() {
        let start_index = index;
        let start_offset = offset;
        let current = chars[index];

        let kind = if current.is_whitespace() {
            while index < chars.len() && chars[index].is_whitespace() {
                offset += chars[index].len_utf16();
                index += 1;
            }
            TokenKind::Space
        } else if current.is_numeric() {
            while index < chars.len()
                && (chars[index].is_numeric() || is_number_inner(&chars, index))
            {
                offset += chars[index].len_utf16();
                index += 1;
            }
            TokenKind::Number
        } else if current.is_alphabetic() {
            while index < chars.len()
                && (chars[index].is_alphanumeric() || is_word_inner(&chars, index))
            {
                offset += chars[index].len_utf16();
                index += 1;
            }
            TokenKind::Word
        } else {
            offset += current.len_utf16();
            index += 1;
            TokenKind::Punctuation
        };

        tokens.push(Token {
            kind,
            range: start_offset..offset,
            text: chars[start_index..index].iter().collect(),
        });
    }

    tokens
}

/// Апостроф или дефис внутри слова: «don't», «well-known».
///
/// Проверка смотрит вперёд и назад: знак остаётся частью слова, только если с
/// обеих сторон от него буквы. Иначе «слово — слово» и «конец,» распались бы
/// не там, где нужно.
fn is_word_inner(chars: &[char], index: usize) -> bool {
    let c = chars[index];
    if !matches!(c, '\'' | '\u{2019}' | '-') {
        return false;
    }
    let before = index > 0 && chars[index - 1].is_alphabetic();
    let after = chars
        .get(index + 1)
        .is_some_and(|next| next.is_alphabetic());
    before && after
}

/// Десятичная точка и разделитель разрядов внутри числа: «3.14», «1,000».
fn is_number_inner(chars: &[char], index: usize) -> bool {
    let c = chars[index];
    if !matches!(c, '.' | ',') {
        return false;
    }
    let before = index > 0 && chars[index - 1].is_numeric();
    let after = chars.get(index + 1).is_some_and(|next| next.is_numeric());
    before && after
}

#[cfg(test)]
mod tests {
    use super::*;

    fn слова(text: &str) -> Vec<String> {
        tokenize(text)
            .into_iter()
            .filter(|t| t.kind == TokenKind::Word)
            .map(|t| t.text)
            .collect()
    }

    #[test]
    fn текст_собирается_обратно_без_потерь() {
        // Главное свойство токенизатора: подсветка на клиенте строится по
        // смещениям, и любая потеря символа сдвинет её на всё оставшееся
        // предложение.
        let text = "The library smelled of dust, leather — and old paper.\n\nEvelyn pushed.";
        let restored: String = tokenize(text).iter().map(|t| t.text.as_str()).collect();
        assert_eq!(restored, text);
    }

    #[test]
    fn смещения_идут_подряд_и_совпадают_с_utf16() {
        let text = "Мама «read» — 42.";
        let tokens = tokenize(text);

        let mut expected = 0;
        for token in &tokens {
            assert_eq!(token.range.start, expected, "разрыв перед «{}»", token.text);
            expected = token.range.end;
        }
        assert_eq!(expected, text.encode_utf16().count());
    }

    #[test]
    fn слова_с_апострофом_и_дефисом_не_распадаются() {
        assert_eq!(слова("don't"), vec!["don't"]);
        assert_eq!(слова("well-known"), vec!["well-known"]);
        // Типографский апостроф встречается в книгах чаще прямого.
        assert_eq!(слова("don\u{2019}t"), vec!["don\u{2019}t"]);
    }

    #[test]
    fn тире_между_словами_не_приклеивается() {
        assert_eq!(слова("dust — leather"), vec!["dust", "leather"]);
        assert_eq!(слова("dust - leather"), vec!["dust", "leather"]);
    }

    #[test]
    fn знаки_препинания_отделяются_от_слов() {
        assert_eq!(слова("«Hello,» she said."), vec!["Hello", "she", "said"]);
    }

    #[test]
    fn числа_остаются_целыми() {
        let tokens = tokenize("3.14 and 1,000 books");
        let numbers: Vec<_> = tokens
            .iter()
            .filter(|t| t.kind == TokenKind::Number)
            .map(|t| t.text.as_str())
            .collect();
        assert_eq!(numbers, vec!["3.14", "1,000"]);
    }

    #[test]
    fn пустой_текст_даёт_пустой_разбор() {
        assert!(tokenize("").is_empty());
    }
}
