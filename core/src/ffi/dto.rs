//! Формат, в котором ядро отдаёт данные наружу.
//!
//! Это отдельные структуры, а не сериализованные доменные типы, и так сделано
//! намеренно. Доменные типы внутри ядра меняются свободно — их видит только
//! Rust. А то, что уходит через FFI, — это контракт с клиентом, который
//! обновляется отдельно от ядра: пользователь с прошлой версией приложения
//! обязан продолжать читать книги. Держать контракт отдельным файлом дешевле,
//! чем каждый раз вспоминать, какое поле кто-то уже разбирает на той стороне.
//!
//! Имена полей — `camelCase`, как в `proto/` и в клиенте на Kotlin.

use serde::Serialize;

use crate::lexicon::{Fact, PosSet, WordAnalysis};
use crate::parser::{Block, Chapter, ChapterInfo, Metadata};
use crate::tokenizer::{Sentence, Token, TokenKind};

/// Разбор слова для карточки.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WordDto {
    /// Слово так, как оно стоит в тексте.
    pub surface: String,
    pub lemma: String,
    /// Части речи начальной формы: `["NOUN", "VERB"]`.
    pub pos: Vec<&'static str>,
    /// Часть речи, по которой слово разобралось: у «glowed» это `VERB`, хотя
    /// лемма «glow» бывает и существительным. `null` у начальной формы.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matched_pos: Option<&'static str>,
    /// `lemma` | `regular` | `irregular` | `unknown`.
    pub form: &'static str,
    pub facts: Vec<FactDto>,
    /// Частотность по шкале Zipf: 6 — «the», 4 — обычное книжное слово.
    pub zipf: f32,
    pub cefr: &'static str,
    /// Нашлось ли слово в словаре. Клиенту так удобнее, чем сверять `form`.
    pub known: bool,
}

#[derive(Debug, Serialize)]
pub struct FactDto {
    pub label: &'static str,
    pub value: String,
}

impl From<&WordAnalysis> for WordDto {
    fn from(analysis: &WordAnalysis) -> Self {
        WordDto {
            surface: analysis.surface.clone(),
            lemma: analysis.lemma.clone(),
            pos: pos_names(analysis.pos),
            matched_pos: analysis.matched.map(pos_name),
            form: form_name(analysis.form),
            facts: analysis.facts.iter().map(FactDto::from).collect(),
            zipf: analysis.zipf,
            cefr: analysis.cefr.label(),
            known: analysis.is_known(),
        }
    }
}

impl From<&Fact> for FactDto {
    fn from(fact: &Fact) -> Self {
        FactDto {
            label: fact.label,
            value: fact.value.clone(),
        }
    }
}

/// Разобранная страница: токены для подсветки и предложения для перевода.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TextDto {
    pub tokens: Vec<TokenDto>,
    pub sentences: Vec<SentenceDto>,
}

/// Токен с позицией.
///
/// Позиции — в единицах UTF-16, то есть ровно в тех индексах, которыми
/// оперируют строки Kotlin.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenDto {
    /// `word` | `number` | `punctuation` | `space`.
    pub kind: &'static str,
    pub start: usize,
    pub end: usize,
    pub text: String,
}

impl From<&Token> for TokenDto {
    fn from(token: &Token) -> Self {
        TokenDto {
            kind: match token.kind {
                TokenKind::Word => "word",
                TokenKind::Number => "number",
                TokenKind::Punctuation => "punctuation",
                TokenKind::Space => "space",
            },
            start: token.range.start,
            end: token.range.end,
            text: token.text.clone(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SentenceDto {
    pub start: usize,
    pub end: usize,
    /// Индексы токенов этого предложения — полуинтервал `[first, last)`.
    pub first_token: usize,
    pub last_token: usize,
    pub text: String,
}

impl From<&Sentence> for SentenceDto {
    fn from(sentence: &Sentence) -> Self {
        SentenceDto {
            start: sentence.range.start,
            end: sentence.range.end,
            first_token: sentence.tokens.start,
            last_token: sentence.tokens.end,
            text: sentence.text.clone(),
        }
    }
}

/// Книга сразу после открытия: метаданные и оглавление.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BookDto {
    pub title: Option<String>,
    pub author: Option<String>,
    pub language: Option<String>,
    /// Путь к обложке внутри книги — достаётся отдельным вызовом.
    pub cover: Option<String>,
    pub chapters: Vec<ChapterInfoDto>,
}

#[derive(Debug, Serialize)]
pub struct ChapterInfoDto {
    pub title: Option<String>,
}

impl BookDto {
    pub fn new(metadata: &Metadata, contents: &[ChapterInfo]) -> Self {
        BookDto {
            title: metadata.title.clone(),
            author: metadata.author.clone(),
            language: metadata.language.clone(),
            cover: metadata.cover.clone(),
            chapters: contents
                .iter()
                .map(|c| ChapterInfoDto {
                    title: c.title.clone(),
                })
                .collect(),
        }
    }
}

/// Глава: блоки в том порядке, в каком их рисует читалка.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChapterDto {
    pub title: Option<String>,
    pub blocks: Vec<BlockDto>,
}

/// Блок главы.
///
/// Поля плоские, а не размеченное объединение: разбирать `{"kind": "...",
/// "text": "..."}` на стороне Kotlin проще, чем вложенные варианты, а лишние
/// пустые поля в JSON не появляются.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BlockDto {
    /// `heading` | `paragraph` | `quote` | `listItem` | `image` | `divider`.
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// Уровень заголовка: 1 — часть, 2 — глава.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub level: Option<u8>,
    /// Путь к иллюстрации внутри книги.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub alt: Option<String>,
}

impl From<&Chapter> for ChapterDto {
    fn from(chapter: &Chapter) -> Self {
        ChapterDto {
            title: chapter.title.clone(),
            blocks: chapter.blocks.iter().map(BlockDto::from).collect(),
        }
    }
}

impl From<&Block> for BlockDto {
    fn from(block: &Block) -> Self {
        let mut dto = BlockDto {
            kind: "divider",
            text: None,
            level: None,
            path: None,
            alt: None,
        };
        match block {
            Block::Heading { level, text } => {
                dto.kind = "heading";
                dto.level = Some(*level);
                dto.text = Some(text.clone());
            }
            Block::Paragraph(text) => {
                dto.kind = "paragraph";
                dto.text = Some(text.clone());
            }
            Block::Quote(text) => {
                dto.kind = "quote";
                dto.text = Some(text.clone());
            }
            Block::ListItem(text) => {
                dto.kind = "listItem";
                dto.text = Some(text.clone());
            }
            Block::Image { path, alt } => {
                dto.kind = "image";
                dto.path = Some(path.clone());
                dto.alt = alt.clone();
            }
            Block::Divider => {}
        }
        dto
    }
}

/// Части речи именами universal tagset — так их читает и сервер, и клиент.
fn pos_names(set: PosSet) -> Vec<&'static str> {
    set.iter().map(pos_name).collect()
}

fn pos_name(pos: crate::lexicon::Pos) -> &'static str {
    use crate::lexicon::Pos;
    match pos {
        Pos::Noun => "NOUN",
        Pos::Verb => "VERB",
        Pos::Adjective => "ADJ",
        Pos::Adverb => "ADV",
        Pos::Pronoun => "PRON",
        Pos::Determiner => "DET",
        Pos::Preposition => "ADP",
        Pos::Conjunction => "CONJ",
        Pos::Particle => "PART",
        Pos::Numeral => "NUM",
    }
}

fn form_name(form: crate::lexicon::FormKind) -> &'static str {
    use crate::lexicon::FormKind;
    match form {
        FormKind::Lemma => "lemma",
        FormKind::Regular => "regular",
        FormKind::Irregular => "irregular",
        FormKind::Unknown => "unknown",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::{analyze, Lexicon};

    #[test]
    fn разбор_слова_сериализуется_ожидаемым_контрактом() {
        let analysis = analyze(Lexicon::embedded(), "children");
        let json = serde_json::to_value(WordDto::from(&analysis)).expect("сериализация");

        assert_eq!(json["surface"], "children");
        assert_eq!(json["lemma"], "child");
        assert_eq!(json["form"], "irregular");
        assert_eq!(json["pos"][0], "NOUN");
        assert_eq!(json["known"], true);
        assert!(json["facts"][0]["label"].is_string());
    }

    #[test]
    fn форма_несёт_часть_речи_по_которой_разобралась() {
        // «glowed» — глагол, хотя лемма «glow» бывает и существительным.
        // Карточка обязана показать глагол, а не первое попавшееся значение.
        let analysis = analyze(Lexicon::embedded(), "glowed");
        let json = serde_json::to_value(WordDto::from(&analysis)).expect("сериализация");

        assert_eq!(json["lemma"], "glow");
        assert_eq!(json["matchedPos"], "VERB");
    }

    #[test]
    fn начальная_форма_не_уточняет_часть_речи() {
        let analysis = analyze(Lexicon::embedded(), "book");
        let json = serde_json::to_value(WordDto::from(&analysis)).expect("сериализация");

        assert!(json.get("matchedPos").is_none(), "уточнять тут нечего");
    }

    #[test]
    fn неизвестное_слово_помечено_явно() {
        let analysis = analyze(Lexicon::embedded(), "zzzqx");
        let json = serde_json::to_value(WordDto::from(&analysis)).expect("сериализация");

        assert_eq!(json["known"], false);
        assert_eq!(json["form"], "unknown");
        assert_eq!(json["cefr"], "C2");
    }

    #[test]
    fn блок_без_картинки_не_несёт_пустых_полей() {
        // Клиент разбирает JSON по ключам, и лишние null только мешают.
        let json = serde_json::to_value(BlockDto::from(&Block::Paragraph("Text.".to_string())))
            .expect("сериализация");

        assert_eq!(json["kind"], "paragraph");
        assert_eq!(json["text"], "Text.");
        assert!(json.get("path").is_none());
        assert!(json.get("level").is_none());
    }

    #[test]
    fn иллюстрация_несёт_путь_и_подпись() {
        let json = serde_json::to_value(BlockDto::from(&Block::Image {
            path: "OEBPS/images/lamp.jpg".to_string(),
            alt: Some("A green lamp".to_string()),
        }))
        .expect("сериализация");

        assert_eq!(json["kind"], "image");
        assert_eq!(json["path"], "OEBPS/images/lamp.jpg");
        assert_eq!(json["alt"], "A green lamp");
        assert!(json.get("text").is_none());
    }
}
