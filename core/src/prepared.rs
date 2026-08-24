//! Подготовленная глава — один тяжёлый переход вместо десятков.
//!
//! Текст блока принадлежит только блоку, токены ссылаются на него смещениями
//! в единицах UTF-16. Так на главу в шестьдесят абзацев уходит один вызов
//! через границу, а не шестьдесят.

use crate::ffi::dto::{BlockDto, CompactSentenceDto, CompactTokenDto, PreparedChapterDto};
use crate::parser::Chapter;
use crate::tokenizer::{split, tokenize};

/// Собирает подготовленную главу из обычной.
///
/// Токенизация делается по `chapter.plain_text()` — ровно той строке, которую
/// получает `Chapter::plain_text()` в ядре и которую клиент соберёт как
/// `blocks.map(text).join("\n\n")`. Смещения токенов и предложений — в единицах
/// UTF-16, чтобы Kotlin и JS могли резать строки без пересчёта.
pub fn prepare(chapter: &Chapter) -> PreparedChapterDto {
    let text = chapter.plain_text();
    let tokens = tokenize(&text);
    let sentences = split(&tokens);
    PreparedChapterDto {
        title: chapter.title.clone(),
        blocks: chapter.blocks.iter().map(BlockDto::from).collect(),
        tokens: tokens.iter().map(CompactTokenDto::from).collect(),
        sentences: sentences.iter().map(CompactSentenceDto::from).collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::{Block, Chapter};

    #[test]
    fn подготовленная_глава_не_дублирует_текст_токена() {
        let chapter = Chapter {
            title: Some("Title".to_string()),
            blocks: vec![
                Block::Paragraph("Hello world.".to_string()),
                Block::Paragraph("Second block.".to_string()),
            ],
        };
        let prepared = prepare(&chapter);
        // Текст принадлежит блокам, токены несут только смещения.
        assert_eq!(prepared.blocks.len(), 2);
        assert!(!prepared.tokens.is_empty());
        // У компактного токена нет поля text — проверяем через сериализацию.
        let json = serde_json::to_value(&prepared).expect("serial");
        for tok in json["tokens"].as_array().expect("tokens") {
            assert!(tok.get("text").is_none(), "токен не должен дублировать текст: {tok}");
            assert!(tok["kind"].is_string());
            assert!(tok["start"].is_number());
            assert!(tok["end"].is_number());
        }
    }

    #[test]
    fn подготовленная_глава_пустая_не_падает() {
        let chapter = Chapter {
            title: None,
            blocks: vec![],
        };
        let prepared = prepare(&chapter);
        assert!(prepared.tokens.is_empty());
        assert!(prepared.sentences.is_empty());
    }

    #[test]
    fn подготовленная_глава_сохраняет_заголовок() {
        let chapter = Chapter {
            title: Some("My Title".to_string()),
            blocks: vec![Block::Paragraph("Hi.".to_string())],
        };
        assert_eq!(prepare(&chapter).title.as_deref(), Some("My Title"));
    }

    #[test]
    fn смещения_совпадают_с_plain_text_utf16() {
        let chapter = Chapter {
            title: None,
            blocks: vec![
                Block::Paragraph("Hello 😀 world.".to_string()),
                Block::Paragraph("Next.".to_string()),
            ],
        };
        let text = chapter.plain_text();
        let prepared = prepare(&chapter);
        // Каждый токен должен вырезаться из plain_text по своим смещениям.
        let utf16: Vec<u16> = text.encode_utf16().collect();
        for tok in &prepared.tokens {
            assert!(tok.start <= tok.end);
            assert!(tok.end <= utf16.len(), "выход за границы для {tok:?}: len {}", utf16.len());
            let slice = String::from_utf16(&utf16[tok.start..tok.end]).expect("valid utf16 slice");
            assert!(!slice.is_empty() || tok.kind == "space");
        }
        // Сравним с прямой токенизацией — должны совпасть один в один.
        let direct = crate::tokenizer::tokenize(&text);
        assert_eq!(direct.len(), prepared.tokens.len());
        for (d, c) in direct.iter().zip(prepared.tokens.iter()) {
            assert_eq!(d.range.start, c.start);
            assert_eq!(d.range.end, c.end);
            assert_eq!(
                match d.kind {
                    crate::tokenizer::TokenKind::Word => "word",
                    crate::tokenizer::TokenKind::Number => "number",
                    crate::tokenizer::TokenKind::Punctuation => "punctuation",
                    crate::tokenizer::TokenKind::Space => "space",
                },
                c.kind
            );
        }
    }

    #[test]
    fn emoji_занимает_две_единицы_utf16() {
        let chapter = Chapter {
            title: None,
            blocks: vec![Block::Paragraph("Hi 😀 there".to_string())],
        };
        let prepared = prepare(&chapter);
        let text = chapter.plain_text();
        let utf16_len = text.encode_utf16().count();
        // Последний токен кончается ровно в длине UTF-16.
        let last = prepared.tokens.last().expect("tokens");
        assert_eq!(last.end, utf16_len);
        // Найдём токен с эмодзи — он должен быть punctuation? нет, эмодзи — не буква, не цифра, не пробел -> punctuation.
        // Важно что смещение до и после эмодзи отличается на 2.
        let smile = prepared
            .tokens
            .iter()
            .find(|t| {
                let slice = &text.encode_utf16().collect::<Vec<u16>>()[t.start..t.end];
                String::from_utf16(slice).is_ok_and(|s| s.contains('😀'))
            })
            .expect("эмодзи токен");
        assert_eq!(smile.end - smile.start, 2, "эмодзи должен занимать 2 единицы UTF-16");
    }

    #[test]
    fn предложения_совпадают_с_tokенизацией_главы() {
        let chapter = Chapter {
            title: None,
            blocks: vec![
                Block::Paragraph("The door opened. Evelyn stepped in.".to_string()),
                Block::Paragraph("Was it cold?".to_string()),
            ],
        };
        let prepared = prepare(&chapter);
        assert_eq!(prepared.sentences.len(), 3);
        // firstToken/lastToken должны указывать на токены.
        for s in &prepared.sentences {
            assert!(s.first_token <= s.last_token);
            assert!(s.last_token <= prepared.tokens.len());
            assert!(s.start < s.end);
        }
    }

    #[test]
    fn non_bmp_символы_не_ломают_смещения() {
        // U+1D11E MUSICAL SYMBOL G CLEF — вне BMP, 2 единицы UTF-16
        let chapter = Chapter {
            title: None,
            blocks: vec![Block::Paragraph("Note 𝄞 end.".to_string())],
        };
        let prepared = prepare(&chapter);
        let text = chapter.plain_text();
        let utf16: Vec<u16> = text.encode_utf16().collect();
        let utf16_len = utf16.len();
        let last = prepared.tokens.last().unwrap();
        assert_eq!(last.end, utf16_len);
        // Все смещения идут подряд без разрывов
        let mut expected = 0;
        for t in &prepared.tokens {
            assert_eq!(t.start, expected, "разрыв перед {t:?}");
            expected = t.end;
        }
        assert_eq!(expected, utf16_len);
    }
}
