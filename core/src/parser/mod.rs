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

pub mod limits;
mod epub;
mod pdf;
mod txt;

use std::fs::File;
use std::io::{Cursor, Read, Seek, SeekFrom};
use std::path::Path;

use crate::error::{CoreError, Result};

/// Откуда парсер читает байты книги.
///
/// Двух реализаций трейта не заводим, а держим одно перечисление: `ZipArchive`
/// параметризуется читателем, и обобщение по нему протащило бы параметр типа
/// через `EpubBook`, `Book` и весь `ffi/`. Вариантов ровно два и больше не
/// будет — файл на диске у настольного клиента и буфер в памяти у браузера,
/// где файловой системы нет вовсе.
#[derive(Debug)]
pub enum Source {
    File(File),
    Memory(Cursor<Vec<u8>>),
}

impl Source {
    pub fn open(path: &Path) -> Result<Source> {
        Ok(Source::File(File::open(path)?))
    }

    pub fn bytes(bytes: Vec<u8>) -> Source {
        Source::Memory(Cursor::new(bytes))
    }

    /// Размер источника, если его можно узнать без чтения.
    ///
    /// Для файла — метаданные, для памяти — длина буфера. Используется
    /// для предварительной проверки `MAX_SOURCE_BYTES` до любых аллокаций.
    pub fn size(&self) -> Result<u64> {
        match self {
            Source::File(file) => Ok(file.metadata()?.len()),
            Source::Memory(cursor) => Ok(cursor.get_ref().len() as u64),
        }
    }

    /// Проверяет, что источник укладывается в `MAX_SOURCE_BYTES`.
    pub fn check_size(&self) -> Result<()> {
        let size = self.size()?;
        crate::parser::limits::check_source_size(size)
    }
}

impl Read for Source {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        match self {
            Source::File(file) => file.read(buffer),
            Source::Memory(cursor) => cursor.read(buffer),
        }
    }
}

impl Seek for Source {
    fn seek(&mut self, to: SeekFrom) -> std::io::Result<u64> {
        match self {
            Source::File(file) => file.seek(to),
            Source::Memory(cursor) => cursor.seek(to),
        }
    }
}

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
    /// Формула или другое математическое содержимое.
    ///
    /// Богатого рендера формул у читалки нет, но терять их нельзя: храним
    /// исходник (MathML/TeX) и читабельный запасной текст — из alttext,
    /// annotation или собранный из содержимого.
    Math { source: String, fallback: String },
    /// Таблица строками и ячейками.
    ///
    /// Структура сохраняется осознанно минимальной: её достаточно, чтобы
    /// показать таблицу как таблицу, а не потерять в потоке текста.
    Table { rows: Vec<Vec<String>> },
    /// Предварительно отформатированный текст: стихи, код, адреса — всё то,
    /// где переносы строк и пробелы значимы и не схлопываются.
    Preformatted(String),
}

impl Block {
    /// Текст блока, если он у него есть.
    pub fn text(&self) -> Option<&str> {
        match self {
            Block::Heading { text, .. }
            | Block::Paragraph(text)
            | Block::Quote(text)
            | Block::ListItem(text)
            | Block::Preformatted(text) => Some(text),
            // У формулы читателю показывается запасной текст.
            Block::Math { fallback, .. } => Some(fallback),
            Block::Image { .. } | Block::Divider | Block::Table { .. } => None,
        }
    }

    /// Строки таблицы одной колонкой текста — для токенизатора и поиска.
    fn table_text(rows: &[Vec<String>]) -> String {
        let mut out = String::new();
        for row in rows {
            for cell in row {
                let cell = cell.trim();
                if cell.is_empty() {
                    continue;
                }
                if !out.is_empty() {
                    out.push(' ');
                }
                out.push_str(cell);
            }
        }
        out
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
            // Таблицы в общем потоке текста: их содержимое должно попадать в
            // токенизатор, иначе слова из ячеек перестанут находиться.
            if let Block::Table { rows } = block {
                let text = Block::table_text(rows);
                if !text.is_empty() {
                    if !out.is_empty() {
                        out.push_str("\n\n");
                    }
                    out.push_str(&text);
                }
                continue;
            }
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
        #[cfg(feature = "native")]
        "pdf" => Ok(Box::new(pdf::PdfBook::open(path)?)),
        // Извлечение текста из PDF не собирается под wasm. Браузер достаёт
        // текст сам (pdf.js) и подаёт его страницами в [`from_pages`]:
        // страница остаётся единицей навигации, включая пустые, иначе номера
        // и оглавление уедут после первой же иллюстрации.
        #[cfg(not(feature = "native"))]
        "pdf" => Err(CoreError::Malformed(
            "текст PDF извлекается на стороне клиента".to_string(),
        )),
        "" => Err(CoreError::Malformed(
            "у файла нет расширения — формат не определить".to_string(),
        )),
        other => Err(CoreError::Malformed(format!(
            "формат «{other}» пока не поддерживается"
        ))),
    }
}

/// Открывает книгу, лежащую в памяти.
///
/// Расширение приходит отдельным аргументом, потому что имени файла у байтов
/// нет: браузер отдаёт содержимое, а не путь. По той же причине сюда же
/// приходит название — из имени файла его достаёт тот, кто это имя видел.
pub fn open_bytes(extension: &str, title: Option<String>, bytes: Vec<u8>) -> Result<Box<dyn Book>> {
    // Общая проверка размера источника до разбора конкретного формата:
    // та же, что для файла на диске, но для буфера в памяти (браузер).
    crate::parser::limits::check_source_size(bytes.len() as u64)?;
    match extension.to_lowercase().as_str() {
        "epub" => Ok(Box::new(epub::EpubBook::from_bytes(bytes)?)),
        "txt" => Ok(Box::new(txt::TxtBook::from_bytes(&bytes, title)?)),
        "pdf" => Err(CoreError::Malformed(
            "текст PDF извлекается на стороне клиента и подаётся страницами".to_string(),
        )),
        other => Err(CoreError::Malformed(format!(
            "формат «{other}» пока не поддерживается"
        ))),
    }
}

/// Собирает книгу из уже извлечённых страниц.
///
/// Так приезжает PDF из браузера и распознанная по фото бумажная страница.
/// Пустые страницы сохраняются: одна физическая страница — одна единица
/// навигации, и выброшенная иллюстрация сдвинула бы все номера после себя.
pub fn from_pages(title: Option<String>, pages: Vec<String>) -> Result<Box<dyn Book>> {
    Ok(Box::new(pdf::PdfBook::from_pages(title, pages)?))
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
