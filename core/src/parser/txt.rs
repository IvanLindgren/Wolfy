//! Простой текст.
//!
//! У TXT нет ни разметки, ни оглавления, ни метаданных — есть только строки.
//! Поэтому вся работа парсера в том, чтобы угадать по ним структуру: где
//! кончается абзац, что похоже на заголовок главы и в какой кодировке всё это
//! записано.

use std::fs;
use std::path::Path;

use crate::error::Result;
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata};

/// Книга из простого текста.
pub struct TxtBook {
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    /// Главы уже разобраны: TXT редко бывает больше нескольких мегабайт, и
    /// держать его в памяти дешевле, чем перечитывать файл ради каждой главы.
    chapters: Vec<Chapter>,
}

impl TxtBook {
    pub fn open(path: &Path) -> Result<Self> {
        let bytes = fs::read(path)?;
        let text = decode(&bytes);

        let title = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string);

        let chapters = split_chapters(&text);
        let contents = chapters
            .iter()
            .map(|c| ChapterInfo {
                title: c.title.clone(),
            })
            .collect();

        Ok(TxtBook {
            metadata: Metadata {
                title,
                ..Metadata::default()
            },
            contents,
            chapters,
        })
    }
}

impl Book for TxtBook {
    fn metadata(&self) -> &Metadata {
        &self.metadata
    }

    fn contents(&self) -> &[ChapterInfo] {
        &self.contents
    }

    fn chapter(&mut self, index: usize) -> Result<Chapter> {
        self.chapters.get(index).cloned().ok_or_else(|| {
            crate::CoreError::Malformed(format!(
                "главы {index} нет: в книге их {}",
                self.chapters.len()
            ))
        })
    }

    fn resource(&mut self, path: &str) -> Result<Vec<u8>> {
        let _ = path;
        Err(crate::CoreError::Malformed(
            "в простом тексте нет иллюстраций".to_string(),
        ))
    }
}

/// Декодирует байты, определяя кодировку.
///
/// UTF-8 проверяется первым — так записано подавляющее большинство файлов.
/// Если не сошлось, книга почти наверняка в windows-1251: русские тексты из
/// старых библиотек до сих пор ходят именно в ней, и открыть их важнее, чем
/// поддержать полный список кодировок.
fn decode(bytes: &[u8]) -> String {
    // BOM снимается явно: иначе первым символом книги станет невидимый
    // «\u{feff}», и первое слово перестанет находиться в словаре.
    let bytes = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes);

    match std::str::from_utf8(bytes) {
        Ok(text) => text.to_string(),
        Err(_) => encoding_rs::WINDOWS_1251.decode(bytes).0.into_owned(),
    }
}

/// Делит текст на главы и блоки.
///
/// Абзац — это кусок между пустыми строками. Заголовок — короткая одиночная
/// строка без точки на конце, вокруг которой пусто: так набирают «CHAPTER III»
/// и «Глава третья» во всех текстовых книгах, что реально встречаются.
fn split_chapters(text: &str) -> Vec<Chapter> {
    let mut chapters: Vec<Chapter> = Vec::new();
    let mut current = Chapter::default();

    for raw in text.split("\n\n") {
        let block_text = normalize(raw);
        if block_text.is_empty() {
            continue;
        }

        if is_heading(&block_text) {
            // Заголовок начинает новую главу — но только если в предыдущей
            // уже что-то было. Иначе «CHAPTER I» сразу после названия книги
            // породило бы пустую главу.
            if !current.blocks.is_empty() {
                chapters.push(std::mem::take(&mut current));
            }
            current.title = Some(block_text.clone());
            current.blocks.push(Block::Heading {
                level: 2,
                text: block_text,
            });
            continue;
        }

        if is_divider(&block_text) {
            current.blocks.push(Block::Divider);
            continue;
        }

        current.blocks.push(Block::Paragraph(block_text));
    }

    if !current.blocks.is_empty() {
        chapters.push(current);
    }

    // Книга без единого заголовка — это одна глава, а не ноль.
    if chapters.is_empty() {
        chapters.push(Chapter::default());
    }
    chapters
}

/// Схлопывает переносы внутри абзаца в пробелы.
///
/// В текстовых книгах строки жёстко обрезаны по 70–80 символов, и без этого
/// шага каждая строка стала бы отдельным абзацем, а газетная выключка по
/// ширине потеряла бы смысл.
fn normalize(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if !out.is_empty() {
            out.push(' ');
        }
        out.push_str(line);
    }
    out
}

/// Похож ли блок на заголовок главы.
fn is_heading(text: &str) -> bool {
    let length = text.chars().count();
    if length == 0 || length > 60 {
        return false;
    }
    // Точка в конце — признак обычного предложения, а не заголовка.
    if text.ends_with(['.', ',', ';', ':']) {
        return false;
    }

    let letters: String = text.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.is_empty() {
        return false;
    }

    // Набрано капслоком — «CHAPTER III», «THE OLD LIBRARY».
    if letters.chars().all(char::is_uppercase) {
        return true;
    }

    // Начинается со слова «глава» или «chapter».
    let first = text
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .to_lowercase();
    matches!(first.as_str(), "глава" | "chapter" | "part" | "часть")
}

/// Разделитель сцен: строка из звёздочек, точек или тире.
fn is_divider(text: &str) -> bool {
    let trimmed = text.trim();
    !trimmed.is_empty()
        && trimmed
            .chars()
            .all(|c| matches!(c, '*' | '·' | '.' | '-' | '—' | '–' | ' '))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::path::PathBuf;

    /// Кладёт книгу во временный файл — парсер работает с путями, а не со
    /// строками, и проверять надо именно этот путь.
    fn книга(name: &str, content: &[u8]) -> PathBuf {
        let path = std::env::temp_dir().join(name);
        let mut file = fs::File::create(&path).expect("временный файл");
        file.write_all(content).expect("запись");
        path
    }

    #[test]
    fn абзацы_разделяются_пустой_строкой() {
        let path = книга(
            "wolfy_txt_paragraphs.txt",
            b"The library smelled of dust.\n\nEvelyn pushed the door.\n",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let chapter = book.chapter(0).expect("первая глава");
        assert_eq!(
            chapter.blocks,
            vec![
                Block::Paragraph("The library smelled of dust.".to_string()),
                Block::Paragraph("Evelyn pushed the door.".to_string()),
            ]
        );
    }

    #[test]
    fn жёсткие_переносы_внутри_абзаца_схлопываются() {
        // Так набрана любая книга из текстовой библиотеки: строки обрезаны по
        // ширине терминала, и абзац размазан по нескольким строкам.
        let path = книга(
            "wolfy_txt_wrapped.txt",
            b"The library smelled of dust,\nleather and old paper.\n",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(
            book.chapter(0).expect("глава").blocks,
            vec![Block::Paragraph(
                "The library smelled of dust, leather and old paper.".to_string()
            )]
        );
    }

    #[test]
    fn заголовки_начинают_новую_главу() {
        let path = книга(
            "wolfy_txt_chapters.txt",
            "CHAPTER I\n\nThe door opened.\n\nCHAPTER II\n\nEvelyn stepped in.\n".as_bytes(),
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(book.contents().len(), 2);
        assert_eq!(book.contents()[0].title.as_deref(), Some("CHAPTER I"));
        assert_eq!(book.contents()[1].title.as_deref(), Some("CHAPTER II"));
        assert!(book
            .chapter(1)
            .expect("вторая глава")
            .plain_text()
            .contains("Evelyn"));
    }

    #[test]
    fn обычное_предложение_не_принимается_за_заголовок() {
        // Короткая строка с точкой — это предложение, а не глава.
        assert!(!is_heading("The door opened."));
        assert!(!is_heading("Она вошла."));
        assert!(is_heading("CHAPTER III"));
        assert!(is_heading("Глава третья"));
    }

    #[test]
    fn книга_без_заголовков_остаётся_одной_главой() {
        let path = книга("wolfy_txt_plain.txt", b"Just one paragraph.\n");
        let book = TxtBook::open(&path).expect("книга открывается");
        assert_eq!(book.contents().len(), 1);
    }

    #[test]
    fn кодировка_windows_1251_читается() {
        // «Глава» в windows-1251.
        let path = книга(
            "wolfy_txt_cp1251.txt",
            &[0xC3, 0xEB, 0xE0, 0xE2, 0xE0, 0x0A],
        );
        let book = TxtBook::open(&path).expect("книга открывается");
        assert_eq!(book.contents()[0].title.as_deref(), Some("Глава"));
    }

    #[test]
    fn метка_порядка_байтов_не_попадает_в_текст() {
        let path = книга("wolfy_txt_bom.txt", "\u{feff}The door opened.".as_bytes());
        let mut book = TxtBook::open(&path).expect("книга открывается");
        assert_eq!(
            book.chapter(0).expect("глава").plain_text(),
            "The door opened."
        );
    }

    #[test]
    fn несуществующая_глава_даёт_ошибку_а_не_панику() {
        let path = книга("wolfy_txt_range.txt", b"One paragraph.\n");
        let mut book = TxtBook::open(&path).expect("книга открывается");
        assert!(book.chapter(99).is_err());
    }
}
