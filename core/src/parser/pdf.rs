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

#[cfg(feature = "native")]
use std::path::Path;

use crate::error::{CoreError, Result};
use crate::parser::limits;
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata};

/// Открытая книга PDF.
#[derive(Debug)]
pub struct PdfBook {
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    chapters: Vec<Chapter>,
}

impl PdfBook {
    /// Собирает книгу из готовых страниц.
    ///
    /// Так PDF приходит из браузера: `pdf-extract` не собирается под
    /// `wasm32-unknown-unknown`, а текстовый слой умеет доставать и `pdf.js`.
    /// Разбивку на страницы при этом обязан сохранить тот, кто извлекал: одна
    /// физическая страница — одна единица навигации, и склеенный текст сбил
    /// бы и номера, и оглавление, и прогресс.
    pub fn from_pages(title: Option<String>, pages: Vec<String>) -> Result<Self> {
        if pages.iter().all(|page| page.trim().is_empty()) {
            return Err(CoreError::Malformed(
                "в PDF нет текстового слоя — похоже, это скан: распознайте страницы через OCR"
                    .to_string(),
            ));
        }

        limits::check_pdf_pages(pages.len())?;
        let total_bytes: usize = pages.iter().map(|p| p.len()).sum();
        limits::check_total_text_len(total_bytes)?;
        // Повторно через PDF-специфичный алиас — та же граница, но
        // отдельный лимит на случай разной настройки.
        if total_bytes > limits::MAX_PDF_TOTAL_TEXT_BYTES {
            return Err(limits::too_large_with_detail(&format!(
                "PDF текст {total_bytes} байт превышает лимит {} байт",
                limits::MAX_PDF_TOTAL_TEXT_BYTES
            )));
        }
        for page in &pages {
            limits::check_chapter_text_len(page.len())?;
        }

        let chapters = split_pages(&pages);
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

    #[cfg(feature = "native")]
    pub fn open(path: &Path) -> Result<Self> {
        // Предварительная проверка размера исходника до тяжёлой распаковки.
        let metadata = std::fs::metadata(path)?;
        limits::check_source_size(metadata.len())?;

        let pages = pdf_extract::extract_text_by_pages(path)
            .map_err(|e| CoreError::Malformed(format!("не удалось извлечь текст из PDF: {e}")))?;

        if pages.iter().all(|page| page.trim().is_empty()) {
            // Скан без текстового слоя. Читалке он бесполезен, но пользователю
            // есть что предложить, и сообщение обязано на это указать.
            return Err(CoreError::Malformed(
                "в PDF нет текстового слоя — похоже, это скан: распознайте страницы через OCR"
                    .to_string(),
            ));
        }

        limits::check_pdf_pages(pages.len())?;
        let total_bytes: usize = pages.iter().map(|p| p.len()).sum();
        limits::check_total_text_len(total_bytes)?;
        if total_bytes > limits::MAX_PDF_TOTAL_TEXT_BYTES {
            return Err(limits::too_large_with_detail(&format!(
                "PDF текст {total_bytes} байт превышает лимит {} байт",
                limits::MAX_PDF_TOTAL_TEXT_BYTES
            )));
        }
        for page in &pages {
            limits::check_chapter_text_len(page.len())?;
        }

        let title = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string);

        let chapters = split_pages(&pages);
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
fn split_pages(pages: &[String]) -> Vec<Chapter> {
    let mut chapters = Vec::new();
    for (index, page) in pages.iter().enumerate() {
        chapters.push(Chapter {
            title: Some(format!("Страница {}", index + 1)),
            // Пустая страница остаётся в книге: если её выкинуть, номер в
            // читалке перестанет совпадать с напечатанной колонцифрой, а
            // прогресс сдвинется после каждой иллюстрации без текстового слоя.
            blocks: page_blocks(page, index + 1),
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
/// не кончилась знаком препинания.
///
/// Дефис в конце строки не выбрасывается: «well-\nknown» может быть и
/// переносом, и честным составным словом на дефисе, а «state-of-\nthe-art»
/// без дефиса превращается в кашу. Строки склеиваются без пробела, дефис
/// остаётся на месте. Мягкий перенос (U+00AD) — единственный, чьё назначение
/// однозначно: он служебный и убирается совсем.
///
/// Колонцифра вырезается только по совпадению контекста: строка стоит первой
/// или последней на странице И равна её физическому номеру. Любое другое
/// число («1984», год издания посреди страницы) — это содержимое.
fn page_blocks(page: &str, page_number: usize) -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut paragraph = String::new();

    let finish = |paragraph: &mut String, blocks: &mut Vec<Block>| {
        let text = paragraph.trim().to_string();
        paragraph.clear();
        if !text.is_empty() {
            blocks.push(Block::Paragraph(text));
        }
    };

    for line in page.lines() {
        // Мягкий перенос в начале строки не снимаем: он снимется при
        // склейке, иначе потеряется признак «приклеить без пробела».
        let line = line.trim();
        if line.is_empty() {
            finish(&mut paragraph, &mut blocks);
            continue;
        }

        if paragraph.ends_with('-') || paragraph.ends_with('\u{00ad}') {
            // Перенос или составное слово: приклеиваем без пробела; обычный
            // дефис сохраняем как часть слова, мягкий — снимаем.
            if paragraph.ends_with('\u{00ad}') {
                paragraph.pop();
            }
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

    // Колонцифра: только крайний блок и только при совпадении с номером.
    for edge in [0, blocks.len().saturating_sub(1)] {
        if blocks.len() < 2 {
            break;
        }
        if matches_page_number(blocks[edge].text(), page_number) {
            blocks.remove(edge);
            break;
        }
    }
    blocks
}

/// Крайняя строка — напечатанная колонцифра этой страницы?
fn matches_page_number(text: Option<&str>, page_number: usize) -> bool {
    let Some(text) = text else { return false };
    let trimmed = text.trim_matches(|c: char| !c.is_ascii_digit());
    if trimmed.is_empty() || !trimmed.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    trimmed
        .parse::<usize>()
        .is_ok_and(|value| value == page_number)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn строки_склеиваются_обратно_в_абзац() {
        let page = "The library smelled of dust,\nleather and old paper.";
        assert_eq!(
            page_blocks(page, 1),
            vec![Block::Paragraph(
                "The library smelled of dust, leather and old paper.".to_string()
            )]
        );
    }

    #[test]
    fn перенос_сохраняет_дефис_в_составных_словах() {
        // «well-known» и «state-of-the-art» — честные составные слова:
        // выбрасывать дефис нельзя, иначе получится «wellknown».
        let page = "It was a well-\nknown fact about state-of-\nthe-art printers.";
        assert_eq!(
            page_blocks(page, 1),
            vec![Block::Paragraph(
                "It was a well-known fact about state-of-the-art printers.".to_string()
            )]
        );
    }

    #[test]
    fn мягкий_перенос_снимается() {
        let page = "She studied the biblio\u{00ad}\ngraphy for hours.";
        assert_eq!(
            page_blocks(page, 1),
            vec![Block::Paragraph(
                "She studied the bibliography for hours.".to_string()
            )]
        );
    }

    #[test]
    fn пустая_строка_кончает_абзац() {
        let page = "First paragraph text.\n\nSecond paragraph text.";
        assert_eq!(
            page_blocks(page, 1),
            vec![
                Block::Paragraph("First paragraph text.".to_string()),
                Block::Paragraph("Second paragraph text.".to_string()),
            ]
        );
    }

    #[test]
    fn колонцифра_совпадающая_со_страницей_убирается() {
        // Последняя строка страницы 87 — «87»: это напечатанный номер.
        let page = "The door opened.\n\n87";
        assert_eq!(
            page_blocks(page, 87),
            vec![Block::Paragraph("The door opened.".to_string())]
        );
        // Первая строка тоже может быть колонцифрой.
        let page = "42\n\nThe door opened.";
        assert_eq!(
            page_blocks(page, 42),
            vec![Block::Paragraph("The door opened.".to_string())]
        );
    }

    #[test]
    fn число_не_совпавшее_с_номером_остаётся_текстом() {
        let page = "In 1984 everything changed.\n\n7";
        assert_eq!(
            page_blocks(page, 3),
            vec![
                Block::Paragraph("In 1984 everything changed.".to_string()),
                Block::Paragraph("7".to_string()),
            ]
        );
    }

    #[test]
    fn название_1984_не_вырезается() {
        let page = "1984\n\nGeorge Orwell wrote a grim novel.";
        assert_eq!(
            page_blocks(page, 2).len(),
            2,
            "«1984» в начале не той страницы — это текст"
        );
    }

    #[test]
    fn пустая_и_скан_страницы_в_смеси_сохраняются() {
        let pages = vec![
            "First page text.".to_string(),
            String::new(),
            "Text on scanned-mixed third.".to_string(),
        ];
        let chapters = split_pages(&pages);
        assert_eq!(chapters.len(), 3);
        assert!(
            chapters[1].blocks.is_empty(),
            "скан-страница не молча исчезает"
        );
    }

    #[test]
    fn каждая_страница_остаётся_отдельной() {
        let pages: Vec<String> = (1..=45).map(|n| format!("Page {n} text here.\n")).collect();
        let chapters = split_pages(&pages);

        assert_eq!(chapters.len(), 45);
        assert_eq!(chapters[0].title.as_deref(), Some("Страница 1"));
        assert_eq!(chapters[44].title.as_deref(), Some("Страница 45"));
    }

    #[test]
    fn пустая_страница_не_сдвигает_нумерацию() {
        let pages = vec![
            "First page.".to_string(),
            String::new(),
            "Third page.".to_string(),
        ];
        let chapters = split_pages(&pages);

        assert_eq!(chapters.len(), 3);
        assert!(chapters[1].blocks.is_empty());
        assert_eq!(chapters[2].title.as_deref(), Some("Страница 3"));
    }

    #[test]
    fn pdf_too_many_pages_rejected() {
        let pages = vec!["a".to_string(); crate::parser::limits::MAX_PDF_PAGES + 1];
        let err = PdfBook::from_pages(Some("Test".to_string()), pages)
            .expect_err("слишком много страниц");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn pdf_total_text_too_large_rejected() {
        // 2 страницы по 11 MiB каждая => 22 MiB > 20 MiB лимита.
        let big = "a".repeat(11 * 1024 * 1024);
        let pages = vec![big.clone(), big];
        let err = PdfBook::from_pages(Some("Test".to_string()), pages)
            .expect_err("общий текст слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn pdf_single_page_too_large_rejected() {
        let huge = "a".repeat(crate::parser::limits::MAX_CHAPTER_TEXT_BYTES + 1);
        let err = PdfBook::from_pages(Some("Test".to_string()), vec![huge])
            .expect_err("страница слишком велика");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn pdf_source_too_large_rejected_via_pages() {
        // from_pages уже проверяет total text; для native пути проверяем source size
        // через limits::check_source_size напрямую (эмулирует open).
        let size = crate::parser::limits::MAX_SOURCE_BYTES + 1;
        let err =
            crate::parser::limits::check_source_size(size).expect_err("источник слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn pdf_from_pages_error_message_matches_native() {
        let pages = vec!["a".to_string(); crate::parser::limits::MAX_PDF_PAGES + 5];
        let err = PdfBook::from_pages(None, pages).unwrap_err();
        // Сообщение должно быть одинаковым на native и web: «слишком велика».
        assert_eq!(err.describe().contains("слишком велика"), true);
        assert!(err
            .describe()
            .contains(crate::parser::limits::TOO_LARGE_MSG));
    }
}
