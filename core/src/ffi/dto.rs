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

use crate::grammar::{Article, Chunk, Exercise, Finding, Marker, MarkerKind, Role};
use crate::lexicon::{Fact, PosSet, WordAnalysis};
use crate::parser::{Block, Chapter, ChapterInfo, Metadata};
use crate::tagger::Word as TaggedWord;
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
    /// Часть речи, которой слово чаще всего оказывается в живом тексте:
    /// у «green» это прилагательное, хотя оно бывает и существительным.
    /// Клиент берёт её, когда разбор формы ничего не уточнил.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dominant_pos: Option<&'static str>,
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
            dominant_pos: analysis.dominant.map(pos_name),
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

/// Что грамматический движок нашёл в предложении.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FindingDto {
    /// Устойчивое имя правила: `present-perfect`. По нему клиент открывает
    /// справку и связывает разбор с упражнениями.
    pub rule: &'static str,
    pub title: &'static str,
    /// Схема формулы: «have/has + V3».
    pub formula: &'static str,
    pub explanation: String,
    /// Токены, к которым относится разбор, — полуинтервал.
    pub start: usize,
    pub end: usize,
}

impl From<&Finding> for FindingDto {
    fn from(finding: &Finding) -> Self {
        FindingDto {
            rule: finding.rule,
            title: finding.title,
            formula: finding.formula,
            explanation: finding.explanation.clone(),
            start: finding.tokens.start,
            end: finding.tokens.end,
        }
    }
}

/// Разбор предложения целиком.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GrammarDto {
    /// Часть речи каждого слова, выбранная с учётом всего предложения.
    pub parts: Vec<PartDto>,
    pub findings: Vec<FindingDto>,
    pub chunks: Vec<ChunkDto>,
    pub markers: Vec<MarkerDto>,
}

/// Слово предложения и его контекстная часть речи.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PartDto {
    /// Индекс в массиве токенов, а не порядковый номер среди слов.
    pub token: usize,
    pub pos: &'static str,
}

impl From<&TaggedWord> for PartDto {
    fn from(word: &TaggedWord) -> Self {
        PartDto {
            token: word.token,
            pos: pos_name(word.pos),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChunkDto {
    pub role: &'static str,
    pub title: &'static str,
    pub tint: &'static str,
    pub start: usize,
    pub end: usize,
    pub head: usize,
}

impl From<&Chunk> for ChunkDto {
    fn from(chunk: &Chunk) -> Self {
        ChunkDto {
            role: match chunk.role {
                Role::Subject => "subject",
                Role::Predicate => "predicate",
                Role::Object => "object",
                Role::Complement => "complement",
                Role::Adverbial => "adverbial",
                Role::Connector => "connector",
            },
            title: chunk.role.title(),
            tint: chunk.role.tint().tag(),
            start: chunk.tokens.start,
            end: chunk.tokens.end,
            head: chunk.head,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MarkerDto {
    pub token: usize,
    pub from: usize,
    pub to: usize,
    pub kind: &'static str,
    pub rule: &'static str,
    pub note: &'static str,
}

impl From<&Marker> for MarkerDto {
    fn from(marker: &Marker) -> Self {
        MarkerDto {
            token: marker.token,
            from: marker.inside.start,
            to: marker.inside.end,
            kind: match marker.kind {
                MarkerKind::Auxiliary => "auxiliary",
                MarkerKind::Ending => "ending",
                MarkerKind::Particle => "particle",
                MarkerKind::Preposition => "preposition",
            },
            rule: marker.rule,
            note: marker.note,
        }
    }
}

/// Статья справочника.
///
/// Название, формула и объяснение приходят от самих детекторов, а не из
/// отдельного текста: справочник, написанный отдельно, расходится с движком на
/// второй же правке.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArticleDto {
    pub rule: &'static str,
    /// Раздел: `tenses`, `voice`, `modals`, `verbals`, `conditionals`.
    pub topic: &'static str,
    pub topic_title: &'static str,
    pub title: &'static str,
    pub formula: &'static str,
    pub explanation: String,
    pub example: &'static str,
    pub translation: &'static str,
    /// Когда правило уместно — то, чего нет в разборе готовой фразы.
    pub usage: &'static str,
}

impl From<&Article> for ArticleDto {
    fn from(article: &Article) -> Self {
        ArticleDto {
            rule: article.rule,
            topic: article.topic.code(),
            topic_title: article.topic.title(),
            title: article.title,
            formula: article.formula,
            explanation: article.explanation.clone(),
            example: article.example,
            translation: article.translation,
            usage: article.usage,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReferenceDto {
    pub articles: Vec<ArticleDto>,
}

/// Микро-упражнение по грамматике.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExerciseDto {
    pub rule: &'static str,
    pub topic: &'static str,
    /// `form` — поставить форму, `name` — назвать правило.
    pub task: &'static str,
    /// Предложение. В задании на форму на месте конструкции стоит `___`.
    pub sentence: String,
    pub translation: &'static str,
    /// Название правила в задании на форму; в задании на узнавание пусто.
    pub question: &'static str,
    pub options: Vec<String>,
    pub answer: usize,
    pub formula: &'static str,
    pub explanation: String,
}

impl From<&Exercise> for ExerciseDto {
    fn from(exercise: &Exercise) -> Self {
        ExerciseDto {
            rule: exercise.rule,
            topic: exercise.topic.code(),
            task: exercise.task.code(),
            sentence: exercise.sentence.clone(),
            translation: exercise.translation,
            question: exercise.question,
            options: exercise.options.clone(),
            answer: exercise.answer,
            formula: exercise.formula,
            explanation: exercise.explanation.clone(),
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExercisesDto {
    pub exercises: Vec<ExerciseDto>,
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
    /// `heading` | `paragraph` | `quote` | `listItem` | `image` | `divider`
    /// | `math` | `table` | `pre`.
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
    /// Исходник формулы (MathML/TeX) для блока `math`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    /// Строки таблицы для блока `table`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rows: Option<Vec<Vec<String>>>,
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
            source: None,
            rows: None,
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
            Block::Math { source, fallback } => {
                dto.kind = "math";
                dto.source = Some(source.clone());
                dto.text = Some(fallback.clone());
            }
            Block::Table { rows } => {
                dto.kind = "table";
                dto.rows = Some(rows.clone());
            }
            // Переносы строк в предварительно отформатированном тексте
            // значимы — клиент обязан показывать их как есть.
            Block::Preformatted(text) => {
                dto.kind = "pre";
                dto.text = Some(text.clone());
            }
            Block::Divider => {}
        }
        dto
    }
}

/// Компактный токен — без дублирования текста.
///
/// Текст принадлежит блоку/главе один раз, токен несёт только смещения в
/// единицах UTF-16, совместимых с Kotlin/JS.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompactTokenDto {
    /// `word` | `number` | `punctuation` | `space`.
    pub kind: &'static str,
    pub start: usize,
    pub end: usize,
}

impl From<&Token> for CompactTokenDto {
    fn from(token: &Token) -> Self {
        CompactTokenDto {
            kind: match token.kind {
                TokenKind::Word => "word",
                TokenKind::Number => "number",
                TokenKind::Punctuation => "punctuation",
                TokenKind::Space => "space",
            },
            start: token.range.start,
            end: token.range.end,
        }
    }
}

/// Компактное предложение — без дублирования текста.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompactSentenceDto {
    pub start: usize,
    pub end: usize,
    pub first_token: usize,
    pub last_token: usize,
}

impl From<&Sentence> for CompactSentenceDto {
    fn from(sentence: &Sentence) -> Self {
        CompactSentenceDto {
            start: sentence.range.start,
            end: sentence.range.end,
            first_token: sentence.tokens.start,
            last_token: sentence.tokens.end,
        }
    }
}

/// Подготовленная глава — один тяжёлый переход.
///
/// Содержит блоки и компактные токены/предложения над `plain_text` главы.
/// Текст токенов не дублируется: клиент режет строку по смещениям.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedChapterDto {
    pub title: Option<String>,
    pub blocks: Vec<BlockDto>,
    pub tokens: Vec<CompactTokenDto>,
    pub sentences: Vec<CompactSentenceDto>,
}

/// Отрезок чтения: докуда читать за один подход.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SegmentDto {
    pub start: usize,
    pub end: usize,
    pub words: usize,
    pub sentences: usize,
    pub last: bool,
}

impl From<crate::reading::Segment> for SegmentDto {
    fn from(segment: crate::reading::Segment) -> Self {
        SegmentDto {
            start: segment.start,
            end: segment.end,
            words: segment.words,
            sentences: segment.sentences,
            last: segment.last,
        }
    }
}

/// Слово в графе предложения.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphWordDto {
    pub text: String,
    pub tag: Option<&'static str>,
}

/// Связь слов в графе.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphLinkDto {
    pub from: usize,
    pub to: usize,
    pub label: String,
}

/// Всё локальное для карточки за один вызов.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InspectDto {
    pub word: WordDto,
    pub tokens: Vec<CompactTokenDto>,
    pub sentences: Vec<CompactSentenceDto>,
    pub findings: Vec<FindingDto>,
    pub chunks: Vec<ChunkDto>,
    pub markers: Vec<MarkerDto>,
    pub parts: Vec<PartDto>,
    pub graph_words: Vec<GraphWordDto>,
    pub graph_links: Vec<GraphLinkDto>,
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
    fn начальная_форма_несёт_преобладающее_значение() {
        // Разбирать в «green» нечего — это и есть начальная форма. Но
        // показать существительное только потому, что оно первое в наборе,
        // значило бы соврать: в тексте это почти всегда цвет.
        let analysis = analyze(Lexicon::embedded(), "green");
        let json = serde_json::to_value(WordDto::from(&analysis)).expect("сериализация");

        assert!(json.get("matchedPos").is_none());
        assert_eq!(json["dominantPos"], "ADJ");
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
