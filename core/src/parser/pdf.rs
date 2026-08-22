//! PDF.
//!
//! PDF описывает не текст, а расположение символов на листе, поэтому «абзац» в
//! нём приходится восстанавливать. Читалке этого достаточно: ей нужны слова с
//! позициями и границы предложений, а не исходная вёрстка типографии.
//!
//! Потоковость здесь другая, чем в EPUB. Разобрать структуру документа, не
//! прочитав файл, нельзя, поэтому текст извлекается один раз при открытии, а
//! дальше главы отдаются из памяти. Для книги в несколько мегабайт это
//! приемлемо; тяжёлые сканы всё равно идут через OCR, а не сюда.

use std::path::Path;

use crate::error::{CoreError, Result};
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata};

/// Сколько страниц кладём в одну главу.
///
/// Разбивка условная: PDF не размечает главы, а листать книгу одним куском в
/// сотни страниц читалке тяжело. Двадцать страниц — примерно печатный лист,
/// удобный шаг и для прогресса чтения, и для фоновой загрузки.
const PAGES_PER_CHAPTER: usize = 20;

/// Открытая книга PDF.
pub struct PdfBook {
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    chapters: Vec<Chapter>,
}

impl PdfBook {
    pub fn open(path: &Path) -> Result<Self> {
        let text = pdf_extract::extract_text(path)
            .map_err(|e| CoreError::Malformed(format!("не удалось извлечь текст из PDF: {e}")))?;

        if text.trim().is_empty() {
            // Скан без текстового слоя. Читалке он бесполезен, но пользователю
            // есть что предложить, и сообщение обязано на это указать.
            return Err(CoreError::Malformed(
                "в PDF нет текстового слоя — похоже, это скан: распознайте страницы через OCR"
                    .to_string(),
            ));
        }

        let title = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string);

        let chapters = split_pages(&text);
        let contents = chapters
            .iter()
            .map(|c| ChapterInfo {
                title: c.title.clone(),
            })
            .collect();

        Ok(PdfBook {
            metadata: Metadata {
                title,
                ..Metadata::default()
            },
            contents,
            chapters,
        })
    }
}

impl Book for PdfBook {
    fn metadata(&self) -> &Metadata {
        &self.metadata
    }

    fn contents(&self) -> &[ChapterInfo] {
        &self.contents
    }

    fn chapter(&mut self, index: usize) -> Result<Chapter> {
        self.chapters.get(index).cloned().ok_or_else(|| {
            CoreError::Malformed(format!(
                "главы {index} нет: в книге их {}",
                self.chapters.len()
            ))
        })
    }

    fn resource(&mut self, path: &str) -> Result<Vec<u8>> {
        let _ = path;
        Err(CoreError::Malformed(
            "иллюстрации из PDF пока не извлекаются".to_string(),
        ))
    }
}

/// Режет извлечённый текст на главы по страницам.
fn split_pages(text: &str) -> Vec<Chapter> {
    // `pdf-extract` разделяет страницы переводом формата.
    let pages: Vec<&str> = text.split('\u{c}').collect();

    let mut chapters = Vec::new();
    for (number, group) in pages.chunks(PAGES_PER_CHAPTER).enumerate() {
        let mut blocks = Vec::new();
        for page in group {
            blocks.extend(page_blocks(page));
        }
        if blocks.is_empty() {
            continue;
        }
        let first = number * PAGES_PER_CHAPTER + 1;
        let last = first + group.len() - 1;
        chapters.push(Chapter {
            title: Some(format!("Страницы {first}–{last}")),
            blocks,
        });
    }

    if chapters.is_empty() {
        chapters.push(Chapter::default());
    }
    chapters
}

/// Собирает абзацы одной страницы.
///
/// В извлечённом тексте строки обрезаны по ширине листа, поэтому абзац
/// приходится склеивать обратно: новая строка продолжает предыдущую, если та
/// не кончилась знаком препинания. Перенос слова через дефис в конце строки
/// снимается — иначе «biblio-» и «graphy» стали бы двумя словами и ни одно из
/// них не нашлось бы в словаре.
fn page_blocks(page: &str) -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut paragraph = String::new();

    let finish = |paragraph: &mut String, blocks: &mut Vec<Block>| {
        let text = paragraph.trim().to_string();
        paragraph.clear();
        if !text.is_empty() && !is_page_number(&text) {
            blocks.push(Block::Paragraph(text));
        }
    };

    for line in page.lines() {
        let line = line.trim();
        if line.is_empty() {
            finish(&mut paragraph, &mut blocks);
            continue;
        }

        if let Some(head) = paragraph.strip_suffix('-') {
            // Перенос: приклеиваем без пробела.
            paragraph = head.to_string();
            paragraph.push_str(line);
        } else {
            if !paragraph.is_empty() {
                paragraph.push(' ');
            }
            paragraph.push_str(line);
        }

        // Строка, кончившаяся точкой и заметно короче типичной, — это конец
        // абзаца, а не перенос.
        if line.ends_with(['.', '!', '?', '»', '"']) && line.chars().count() < 50 {
            finish(&mut paragraph, &mut blocks);
        }
    }

    finish(&mut paragraph, &mut blocks);
    blocks
}

/// Колонцифра: строка из одного числа. В текст книги она попадать не должна.
fn is_page_number(text: &str) -> bool {
    let trimmed = text.trim_matches(|c: char| !c.is_alphanumeric());
    !trimmed.is_empty() && trimmed.chars().all(|c| c.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn строки_склеиваются_обратно_в_абзац() {
        let page = "The library smelled of dust,\nleather and old paper.";
        assert_eq!(
            page_blocks(page),
            vec![Block::Paragraph(
                "The library smelled of dust, leather and old paper.".to_string()
            )]
        );
    }

    #[test]
    fn перенос_через_дефис_снимается() {
        // Иначе «biblio» и «graphy» пошли бы в словарь двумя словами, и ни
        // одно из них там не нашлось бы.
        let page = "She studied the biblio-\ngraphy for hours.";
        assert_eq!(
            page_blocks(page),
            vec![Block::Paragraph(
                "She studied the bibliography for hours.".to_string()
            )]
        );
    }

    #[test]
    fn пустая_строка_кончает_абзац() {
        let page = "First paragraph text.\n\nSecond paragraph text.";
        assert_eq!(
            page_blocks(page),
            vec![
                Block::Paragraph("First paragraph text.".to_string()),
                Block::Paragraph("Second paragraph text.".to_string()),
            ]
        );
    }

    #[test]
    fn колонцифра_не_попадает_в_текст() {
        let page = "The door opened.\n\n87";
        assert_eq!(
            page_blocks(page),
            vec![Block::Paragraph("The door opened.".to_string())]
        );
    }

    #[test]
    fn страницы_группируются_в_главы() {
        let pages: Vec<String> = (1..=45).map(|n| format!("Page {n} text here.\n")).collect();
        let chapters = split_pages(&pages.join("\u{c}"));

        assert_eq!(chapters.len(), 3);
        assert_eq!(chapters[0].title.as_deref(), Some("Страницы 1–20"));
        assert_eq!(chapters[2].title.as_deref(), Some("Страницы 41–45"));
    }
}
