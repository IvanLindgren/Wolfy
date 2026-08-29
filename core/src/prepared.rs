//! Подготовленная глава — один тяжёлый переход вместо десятков.
//!
//! Текст блока принадлежит только блоку, токены ссылаются на него смещениями
//! в единицах UTF-16. Так на главу в шестьдесят абзацев уходит один вызов
//! через границу, а не шестьдесят.

use crate::ffi::dto::{
    BlockDto, CompactChainDto, CompactSentenceDto, CompactTokenDto, PreparedChapterDto,
};
use crate::grammar::chains;
use crate::lexicon::Lexicon;
use crate::parser::Chapter;
use crate::tagger::tag;
use crate::tokenizer::{split, tokenize, Sentence, Token};

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
        chains: verb_chains(&tokens, &sentences),
    }
}

/// Глагольные цепочки главы в смещениях UTF-16.
///
/// Считается здесь, вместе с токенами, а не по касанию: разметка стоит дороже
/// самого поиска, и платить за неё на каждый тап значило бы платить за одно и
/// то же по многу раз. Глава готовится один раз, и цепочки едут вместе с ней.
///
/// Разбор идёт по предложениям, а не по главе целиком. `chains` шагает по
/// словам подряд и границ предложения не знает: на общем списке сказуемое
/// одной фразы склеилось бы со следующей через точку.
fn verb_chains(tokens: &[Token], sentences: &[Sentence]) -> Vec<CompactChainDto> {
    let lexicon = Lexicon::embedded();
    let mut out = Vec::new();
    for sentence in sentences {
        let Some(slice) = tokens.get(sentence.tokens.clone()) else {
            continue;
        };
        let words = tag(lexicon, slice);
        let offset = sentence.tokens.start;
        for chain in chains(&words) {
            // Цепочка из одного слова расширять нечего: тап по ней и так
            // открывает карточку этого слова.
            if chain.words.len() < 2 {
                continue;
            }
            let Some(first) = words.get(chain.words.start) else {
                continue;
            };
            let Some(last) = chain.words.end.checked_sub(1).and_then(|i| words.get(i)) else {
                continue;
            };
            let (Some(head), Some(tail)) = (
                tokens.get(offset + first.token),
                tokens.get(offset + last.token),
            ) else {
                continue;
            };
            let end = tail.range.end;
            let main_start = chain
                .main()
                .and_then(|part| words.get(part.word))
                .and_then(|word| tokens.get(offset + word.token))
                .map(|token| token.range.start)
                .unwrap_or(end);
            out.push(CompactChainDto {
                start: head.range.start,
                end,
                main_start,
            });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parser::{Block, Chapter};

    /// Тап по служебному глаголу обязан захватывать всю цепочку.
    ///
    /// «is» в словаре бесполезен: спрашивают не про связку, а про форму. При
    /// этом «walking» искать в словаре как раз осмысленно, поэтому цепочка
    /// несёт начало смыслового глагола — по нему клиент и отличает касание,
    /// которое надо расширить, от касания, которое надо оставить как есть.
    #[test]
    fn цепочка_сказуемого_доезжает_до_клиента() {
        let chapter = Chapter {
            title: None,
            blocks: vec![Block::Paragraph(
                "She is walking. He has a book.".to_string(),
            )],
        };
        let prepared = prepare(&chapter);
        let text = "She is walking. He has a book.";

        let chain = prepared
            .chains
            .iter()
            .find(|c| text[c.start..c.end].starts_with("is"))
            .expect("цепочка «is walking» не найдена");
        assert_eq!(&text[chain.start..chain.end], "is walking");
        assert_eq!(&text[chain.main_start..chain.end], "walking");

        // «has a book» — сказуемое из одного слова: расширять нечего, и
        // цепочка сюда попадать не должна, иначе тап по «has» утащил бы за
        // собой то, что цепочкой не является.
        assert!(
            prepared
                .chains
                .iter()
                .all(|c| !text[c.start..c.end].starts_with("has")),
            "цепочка из одного слова не должна ехать клиенту: {:?}",
            prepared
                .chains
                .iter()
                .map(|c| &text[c.start..c.end])
                .collect::<Vec<_>>()
        );
    }

    /// Цепочка не имеет права перешагнуть точку.
    #[test]
    fn цепочка_не_склеивает_соседние_предложения() {
        let chapter = Chapter {
            title: None,
            blocks: vec![Block::Paragraph("He was late. Reading helps.".to_string())],
        };
        let prepared = prepare(&chapter);
        let text = "He was late. Reading helps.";
        for chain in &prepared.chains {
            assert!(
                !text[chain.start..chain.end].contains('.'),
                "цепочка перешагнула границу предложения: {:?}",
                &text[chain.start..chain.end]
            );
        }
    }

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
            assert!(
                tok.get("text").is_none(),
                "токен не должен дублировать текст: {tok}"
            );
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
            assert!(
                tok.end <= utf16.len(),
                "выход за границы для {tok:?}: len {}",
                utf16.len()
            );
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
        assert_eq!(
            smile.end - smile.start,
            2,
            "эмодзи должен занимать 2 единицы UTF-16"
        );
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
