//! Разбор книг: EPUB, TXT, PDF.
//!
//! Главное требование к этому слою — читатель видит первую страницу сразу.
//! Поэтому книга не разбирается целиком: открытие читает только оглавление и
//! метаданные, а текст главы достаётся по требованию, когда до неё дошли.
//! Клиент пользуется этим так: показывает первую главу, а остальные разбирает
//! в фоне и складывает в свою базу.
//!
//! Формат внутри книги приводится к одному виду — списку блоков ([`Block`]).
//! Читалке всё равно, откуда пришёл абзац; ей важно, абзац это, заголовок или
//! цитата, потому что рисуются они по-разному.

mod epub;
mod pdf;
mod txt;

use std::path::Path;

use crate::error::{CoreError, Result};

/// Кусок книги, который читалка рисует как единое целое.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Block {
    /// Заголовок с уровнем вложенности: 1 — часть, 2 — глава, дальше — разделы.
    Heading { level: u8, text: String },
    /// Обычный абзац.
    Paragraph(String),
    /// Цитата или эпиграф — в газетной вёрстке у них своя врезка.
    Quote(String),
    /// Пункт списка.
    ListItem(String),
    /// Иллюстрация. Хранится путь внутри книги, сами байты достаются отдельно,
    /// чтобы открытие главы не тащило картинки в память.
    Image { path: String, alt: Option<String> },
    /// Разделитель сцен: звёздочки, пустая строка с отбивкой.
    Divider,
}

impl Block {
    /// Текст блока, если он у него есть.
    pub fn text(&self) -> Option<&str> {
        match self {
            Block::Heading { text, .. }
            | Block::Paragraph(text)
            | Block::Quote(text)
            | Block::ListItem(text) => Some(text),
            Block::Image { .. } | Block::Divider => None,
        }
    }
}

/// Глава книги.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Chapter {
    pub title: Option<String>,
    pub blocks: Vec<Block>,
}

impl Chapter {
    /// Весь текст главы одной строкой — то, что уходит в токенизатор.
    ///
    /// Блоки разделяются пустой строкой: так границы абзацев остаются видимы
    /// детектору предложений и он не склеивает конец одного абзаца с началом
    /// следующего.
    pub fn plain_text(&self) -> String {
        let mut out = String::new();
        for block in &self.blocks {
            if let Some(text) = block.text() {
                if !out.is_empty() {
                    out.push_str("\n\n");
                }
                out.push_str(text);
            }
        }
        out
    }
}

/// Что известно о книге до чтения текста.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Metadata {
    pub title: Option<String>,
    pub author: Option<String>,
    pub language: Option<String>,
    /// Путь к обложке внутри книги, если она есть.
    pub cover: Option<String>,
}

/// Оглавление: то, что показывается в списке глав.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChapterInfo {
    pub title: Option<String>,
}

/// Открытая книга, из которой главы достаются по одной.
///
/// Трейт, а не перечисление, потому что реализаций уже три и каждая держит
/// своё состояние: EPUB — открытый архив, PDF — разобранный документ, TXT —
/// границы глав в файле.
pub trait Book: Send {
    fn metadata(&self) -> &Metadata;

    /// Оглавление целиком — оно маленькое и нужно сразу.
    fn contents(&self) -> &[ChapterInfo];

    /// Читает одну главу. Это единственная тяжёлая операция, и вызывается она
    /// тогда, когда читатель до главы дошёл.
    fn chapter(&mut self, index: usize) -> Result<Chapter>;

    /// Байты картинки по пути из [`Block::Image`].
    fn resource(&mut self, path: &str) -> Result<Vec<u8>>;
}

/// Открывает книгу, определяя формат по расширению.
///
/// По расширению, а не по содержимому: пользователь приносит файл из своей
/// библиотеки, где расширения расставлены правильно, а угадывание по
/// сигнатуре стоило бы лишнего чтения диска на каждом открытии.
pub fn open(path: &Path) -> Result<Box<dyn Book>> {
    let extension = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_lowercase)
        .unwrap_or_default();

    match extension.as_str() {
        "epub" => Ok(Box::new(epub::EpubBook::open(path)?)),
        "txt" => Ok(Box::new(txt::TxtBook::open(path)?)),
        "pdf" => Ok(Box::new(pdf::PdfBook::open(path)?)),
        "" => Err(CoreError::Malformed(
            "у файла нет расширения — формат не определить".to_string(),
        )),
        other => Err(CoreError::Malformed(format!(
            "формат «{other}» пока не поддерживается"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn текст_главы_разделяет_абзацы_пустой_строкой() {
        let chapter = Chapter {
            title: Some("The Old Library".to_string()),
            blocks: vec![
                Block::Paragraph("The library smelled of dust.".to_string()),
                Block::Divider,
                Block::Paragraph("Evelyn pushed the door.".to_string()),
            ],
        };
        assert_eq!(
            chapter.plain_text(),
            "The library smelled of dust.\n\nEvelyn pushed the door."
        );
    }

    #[test]
    fn неизвестный_формат_отвергается_понятной_ошибкой() {
        let Err(err) = open(Path::new("book.djvu")) else {
            panic!("формат djvu поддерживаться не должен");
        };
        assert!(err.describe().contains("djvu"), "{}", err.describe());
    }

    #[test]
    fn файл_без_расширения_отвергается() {
        let Err(err) = open(Path::new("book")) else {
            panic!("без расширения формат не определить");
        };
        assert!(err.describe().contains("расширения"), "{}", err.describe());
    }
}
