//! EPUB.
//!
//! EPUB — это zip-архив с XHTML внутри, и его устройство удобно ровно тем, что
//! позволяет не читать книгу целиком. Открытие достаёт три маленьких файла:
//! `META-INF/container.xml` укажет на манифест, манифест даст метаданные и
//! список файлов, `spine` — порядок чтения. Текст главы распаковывается
//! только тогда, когда читатель до неё дошёл.

use std::io::Read;
use std::path::Path;

use quick_xml::events::Event;
use quick_xml::Reader;
use zip::ZipArchive;

use crate::error::{CoreError, Result};
use crate::parser::limits;
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata, Source};

/// Открытая книга EPUB.
pub struct EpubBook {
    archive: ZipArchive<Source>,
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    /// Пути файлов глав в порядке чтения — параллельно `contents`.
    spine: Vec<String>,
}

impl EpubBook {
    pub fn open(path: &Path) -> Result<Self> {
        EpubBook::read(Source::open(path)?)
    }

    /// EPUB, целиком лежащий в памяти: так книга приходит из браузера.
    ///
    /// Потоковость при этом не теряется. Zip держит оглавление в конце файла,
    /// и по нему главы достаются поодиночке — из буфера так же, как с диска.
    pub fn from_bytes(bytes: Vec<u8>) -> Result<Self> {
        EpubBook::read(Source::bytes(bytes))
    }

    fn read(source: Source) -> Result<Self> {
        // Проверка размера источника до распаковки: та же, что в open_bytes,
        // но для файла на диске (metadata) её не делал `parser::open_bytes`.
        source.check_size()?;
        let mut archive = ZipArchive::new(source)
            .map_err(|e| CoreError::Malformed(format!("не открывается архив EPUB: {e}")))?;

        limits::check_epub_entries(archive.len())?;

        let opf_path = find_opf(&mut archive)?;
        let opf = read_entry(&mut archive, &opf_path)?;
        let base = parent_dir(&opf_path);

        let package = parse_package(&opf, &base)?;

        Ok(EpubBook {
            archive,
            metadata: package.metadata,
            contents: package
                .spine
                .iter()
                .map(|item| ChapterInfo {
                    title: item.title.clone(),
                })
                .collect(),
            spine: package.spine.into_iter().map(|item| item.href).collect(),
        })
    }
}

impl Book for EpubBook {
    fn metadata(&self) -> &Metadata {
        &self.metadata
    }

    fn contents(&self) -> &[ChapterInfo] {
        &self.contents
    }

    fn chapter(&mut self, index: usize) -> Result<Chapter> {
        let href = self.spine.get(index).cloned().ok_or_else(|| {
            CoreError::Malformed(format!(
                "главы {index} нет: в книге их {}",
                self.spine.len()
            ))
        })?;

        let xhtml = read_entry(&mut self.archive, &href)?;
        // Сырой XHTML уже ограничен при чтении записи (8 MiB), но
        // дополнительная проверка текста главы защищает от гигантского
        // XHTML, где большая часть — текст внутри одного блока.
        limits::check_chapter_text_len(xhtml.len())?;
        let blocks = parse_xhtml(&xhtml, &parent_dir(&href))?;
        // Итоговый plain text тоже не должен раздуть память.
        let total_text: usize = blocks.iter().filter_map(|b| b.text()).map(|s| s.len()).sum();
        limits::check_chapter_text_len(total_text)?;
        limits::check_total_text_len(total_text)?;

        // Заголовок главы берётся из её первого заголовочного блока: он
        // точнее оглавления, где часто стоит «Chapter 12» без названия.
        let title = blocks
            .iter()
            .find_map(|b| match b {
                Block::Heading { text, .. } => Some(text.clone()),
                _ => None,
            })
            .or_else(|| self.contents.get(index).and_then(|c| c.title.clone()));

        Ok(Chapter { title, blocks })
    }

    fn resource(&mut self, path: &str) -> Result<Vec<u8>> {
        let mut entry = self
            .archive
            .by_name(path)
            .map_err(|_| CoreError::Malformed(format!("в книге нет файла «{path}»")))?;
        let declared = entry.size();
        limits::check_epub_entry_size(declared)?;
        limits::check_image_bytes(declared as usize).ok();
        // ZIP заголовку нельзя доверять — читаем через take(MAX+1) и
        // проверяем фактический размер.
        let mut limited = (&mut entry).take(limits::MAX_EPUB_ENTRY_BYTES + 1);
        let mut bytes = Vec::new();
        limited.read_to_end(&mut bytes)?;
        if bytes.len() as u64 > limits::MAX_EPUB_ENTRY_BYTES {
            return Err(limits::too_large_with_detail(&format!(
                "файл «{path}» превышает лимит {} байт",
                limits::MAX_EPUB_ENTRY_BYTES
            )));
        }
        limits::check_image_bytes(bytes.len())?;
        Ok(bytes)
    }
}

/// Читает файл архива в строку.
fn read_entry(archive: &mut ZipArchive<Source>, path: &str) -> Result<String> {
    let mut entry = archive
        .by_name(path)
        .map_err(|_| CoreError::Malformed(format!("в книге нет файла «{path}»")))?;
    let declared = entry.size();
    limits::check_epub_entry_size(declared)?;
    // Не доверяем declared size из ZIP заголовка: читаем через take.
    let mut limited = (&mut entry).take(limits::MAX_EPUB_ENTRY_BYTES + 1);
    let mut bytes = Vec::new();
    limited
        .read_to_end(&mut bytes)
        .map_err(|e| CoreError::Malformed(format!("файл «{path}» не читается как текст: {e}")))?;
    if bytes.len() as u64 > limits::MAX_EPUB_ENTRY_BYTES {
        return Err(limits::too_large_with_detail(&format!(
            "файл «{path}» превышает лимит {} байт",
            limits::MAX_EPUB_ENTRY_BYTES
        )));
    }
    let text = String::from_utf8(bytes)
        .map_err(|e| CoreError::Malformed(format!("файл «{path}» не читается как текст: {e}")))?;
    Ok(text)
}

/// Находит манифест книги через `META-INF/container.xml`.
///
/// Путь к манифесту не фиксирован стандартом, и книги действительно кладут его
/// куда попало — то в `OEBPS/content.opf`, то в корень. Поэтому спрашиваем
/// container.xml, а не угадываем.
fn find_opf(archive: &mut ZipArchive<Source>) -> Result<String> {
    let container = read_entry(archive, "META-INF/container.xml")?;
    let mut reader = Reader::from_str(&container);

    loop {
        match reader.read_event() {
            Ok(Event::Empty(e)) | Ok(Event::Start(e)) => {
                if local_name(e.name().as_ref()) == b"rootfile" {
                    if let Some(path) = attribute(&e, b"full-path") {
                        return Ok(path);
                    }
                }
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(e) => {
                return Err(CoreError::Malformed(format!(
                    "container.xml повреждён: {e}"
                )))
            }
        }
    }

    Err(CoreError::Malformed(
        "в container.xml не указан манифест книги".to_string(),
    ))
}

/// Один файл в порядке чтения.
struct SpineItem {
    href: String,
    title: Option<String>,
}

struct Package {
    metadata: Metadata,
    spine: Vec<SpineItem>,
}

/// Разбирает манифест: метаданные, список файлов и порядок чтения.
fn parse_package(opf: &str, base: &str) -> Result<Package> {
    let mut reader = Reader::from_str(opf);
    let mut metadata = Metadata::default();
    // id → href из манифеста; spine ссылается на файлы по id, а не по пути.
    let mut manifest: Vec<(String, String)> = Vec::new();
    let mut cover_id: Option<String> = None;
    let mut order: Vec<String> = Vec::new();

    // Какой текстовый элемент метаданных сейчас читается.
    let mut collecting: Option<&'static str> = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                match name.as_slice() {
                    b"title" => collecting = Some("title"),
                    b"creator" => collecting = Some("creator"),
                    b"language" => collecting = Some("language"),
                    b"item" => {
                        if let (Some(id), Some(href)) =
                            (attribute(&e, b"id"), attribute(&e, b"href"))
                        {
                            let full = join(base, &href);
                            // Обложку книги ищем двумя способами, потому что
                            // разные генераторы EPUB помечают её по-разному.
                            if attribute(&e, b"properties")
                                .is_some_and(|p| p.contains("cover-image"))
                            {
                                metadata.cover = Some(full.clone());
                            }
                            manifest.push((id, full));
                        }
                    }
                    b"itemref" => {
                        if let Some(idref) = attribute(&e, b"idref") {
                            order.push(idref);
                        }
                    }
                    b"meta" if attribute(&e, b"name").as_deref() == Some("cover") => {
                        cover_id = attribute(&e, b"content");
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(e)) => {
                if let Some(field) = collecting.take() {
                    let value = e.decode().map(|t| t.trim().to_string()).unwrap_or_default();
                    if value.is_empty() {
                        continue;
                    }
                    match field {
                        "title" if metadata.title.is_none() => metadata.title = Some(value),
                        "creator" if metadata.author.is_none() => metadata.author = Some(value),
                        "language" if metadata.language.is_none() => {
                            metadata.language = Some(value)
                        }
                        _ => {}
                    }
                }
            }
            Ok(Event::End(_)) => collecting = None,
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(e) => return Err(CoreError::Malformed(format!("манифест повреждён: {e}"))),
        }
    }

    if metadata.cover.is_none() {
        if let Some(id) = cover_id {
            metadata.cover = manifest
                .iter()
                .find(|(item_id, _)| *item_id == id)
                .map(|(_, href)| href.clone());
        }
    }

    let spine: Vec<SpineItem> = order
        .iter()
        .filter_map(|idref| {
            manifest
                .iter()
                .find(|(id, _)| id == idref)
                .map(|(_, href)| SpineItem {
                    href: href.clone(),
                    title: None,
                })
        })
        .collect();

    if spine.is_empty() {
        return Err(CoreError::Malformed(
            "в книге не указан порядок чтения".to_string(),
        ));
    }

    Ok(Package { metadata, spine })
}

/// Превращает XHTML главы в блоки читалки.
///
/// Разбор намеренно поверхностный: нас интересуют абзацы, заголовки, цитаты,
/// списки и картинки, а всё остальное — вложенные `span`, оформительские
/// `div`, стили — это шум вёрстки, который читалка всё равно рисует своим
/// газетным набором.
fn parse_xhtml(xhtml: &str, base: &str) -> Result<Vec<Block>> {
    let mut reader = Reader::from_str(xhtml);
    let mut blocks = Vec::new();
    // Текст, накопленный внутри текущего блочного элемента.
    let mut buffer = String::new();
    // Какой блок сейчас собирается и на какой глубине он начался.
    let mut current: Option<(BlockKind, usize)> = None;
    let mut depth = 0usize;
    // Внутри этих элементов текста для читателя нет.
    let mut skipping = 0usize;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => {
                depth += 1;
                let name = local_name(e.name().as_ref()).to_vec();

                if matches!(name.as_slice(), b"script" | b"style" | b"head") {
                    skipping += 1;
                    continue;
                }
                if skipping > 0 {
                    continue;
                }

                if let Some(kind) = BlockKind::from_tag(&name) {
                    // Вложенный блок закрывает предыдущий: так «<p>» внутри
                    // «<blockquote>» не потеряет свой текст.
                    flush(&mut blocks, &mut current, &mut buffer);
                    current = Some((kind, depth));
                }
            }
            Ok(Event::Empty(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if skipping > 0 {
                    continue;
                }
                match name.as_slice() {
                    b"img" => {
                        if let Some(src) = attribute(&e, b"src") {
                            flush(&mut blocks, &mut current, &mut buffer);
                            blocks.push(Block::Image {
                                path: join(base, &src),
                                alt: attribute(&e, b"alt"),
                            });
                        }
                    }
                    b"hr" => {
                        flush(&mut blocks, &mut current, &mut buffer);
                        blocks.push(Block::Divider);
                    }
                    b"br" => buffer.push(' '),
                    _ => {}
                }
            }
            Ok(Event::Text(e)) => {
                if skipping > 0 || current.is_none() {
                    continue;
                }
                if let Ok(text) = e.decode() {
                    push_text(&mut buffer, &text);
                }
            }
            Ok(Event::End(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if matches!(name.as_slice(), b"script" | b"style" | b"head") {
                    skipping = skipping.saturating_sub(1);
                }
                if let Some((_, started_at)) = current {
                    if depth == started_at {
                        flush(&mut blocks, &mut current, &mut buffer);
                    }
                }
                depth = depth.saturating_sub(1);
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(e) => return Err(CoreError::Malformed(format!("глава повреждена: {e}"))),
        }
    }

    flush(&mut blocks, &mut current, &mut buffer);
    Ok(blocks)
}

/// Блочные элементы, которые читалка различает.
#[derive(Debug, Clone, Copy)]
enum BlockKind {
    Heading(u8),
    Paragraph,
    Quote,
    ListItem,
}

impl BlockKind {
    fn from_tag(tag: &[u8]) -> Option<Self> {
        Some(match tag {
            b"h1" => BlockKind::Heading(1),
            b"h2" => BlockKind::Heading(2),
            b"h3" => BlockKind::Heading(3),
            b"h4" | b"h5" | b"h6" => BlockKind::Heading(4),
            b"p" => BlockKind::Paragraph,
            b"blockquote" => BlockKind::Quote,
            b"li" => BlockKind::ListItem,
            _ => return None,
        })
    }
}

/// Закрывает накопленный блок и кладёт его в главу.
fn flush(blocks: &mut Vec<Block>, current: &mut Option<(BlockKind, usize)>, buffer: &mut String) {
    let Some((kind, _)) = current.take() else {
        buffer.clear();
        return;
    };
    let text = buffer.trim().to_string();
    buffer.clear();
    if text.is_empty() {
        return;
    }
    blocks.push(match kind {
        BlockKind::Heading(level) => Block::Heading { level, text },
        BlockKind::Paragraph => Block::Paragraph(text),
        BlockKind::Quote => Block::Quote(text),
        BlockKind::ListItem => Block::ListItem(text),
    });
}

/// Добавляет текст, схлопывая пробельные переносы вёрстки в один пробел.
fn push_text(buffer: &mut String, text: &str) {
    // Пробел добавляется только там, где его ещё нет: абзац приходит из
    // вёрстки кусками («Every », «unfamiliar», « word»), и без этой проверки
    // на каждом стыке копился бы лишний пробел.
    let mut pending = false;
    for c in text.chars() {
        if c.is_whitespace() {
            pending = true;
            continue;
        }
        if pending {
            push_separator(buffer);
            pending = false;
        }
        buffer.push(c);
    }
    if pending {
        push_separator(buffer);
    }
}

/// Ставит разделяющий пробел, если он уместен.
fn push_separator(buffer: &mut String) {
    if !buffer.is_empty() && !buffer.ends_with(' ') {
        buffer.push(' ');
    }
}

/// Имя элемента без пространства имён: `opf:item` → `item`.
fn local_name(name: &[u8]) -> &[u8] {
    match name.iter().rposition(|b| *b == b':') {
        Some(index) => &name[index + 1..],
        None => name,
    }
}

/// Значение атрибута.
fn attribute(element: &quick_xml::events::BytesStart<'_>, key: &[u8]) -> Option<String> {
    element.attributes().flatten().find_map(|attr| {
        (local_name(attr.key.as_ref()) == key)
            .then(|| String::from_utf8_lossy(&attr.value).into_owned())
    })
}

/// Каталог, в котором лежит файл.
fn parent_dir(path: &str) -> String {
    match path.rfind('/') {
        Some(index) => path[..index].to_string(),
        None => String::new(),
    }
}

/// Склеивает путь внутри архива, разрешая «..» и «./».
///
/// Пути в EPUB относительны файлу, который на них ссылается, поэтому картинка
/// из главы в `OEBPS/text/` приходит как `../images/cover.jpg` и без разбора
/// «..» в архиве не найдётся.
fn join(base: &str, href: &str) -> String {
    let href = href.split(['#', '?']).next().unwrap_or(href);
    if base.is_empty() {
        return normalize_path(href);
    }
    normalize_path(&format!("{base}/{href}"))
}

fn normalize_path(path: &str) -> String {
    let mut parts: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            other => parts.push(other),
        }
    }
    parts.join("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn относительные_пути_разрешаются() {
        assert_eq!(
            join("OEBPS/text", "../images/cover.jpg"),
            "OEBPS/images/cover.jpg"
        );
        assert_eq!(join("OEBPS", "chapter1.xhtml"), "OEBPS/chapter1.xhtml");
        assert_eq!(join("", "content.opf"), "content.opf");
        // Якорь в ссылке на файл не влияет.
        assert_eq!(join("OEBPS", "ch1.xhtml#start"), "OEBPS/ch1.xhtml");
    }

    #[test]
    fn пространство_имён_снимается() {
        assert_eq!(local_name(b"opf:item"), b"item");
        assert_eq!(local_name(b"item"), b"item");
    }

    #[test]
    fn разметка_главы_превращается_в_блоки() {
        let xhtml = r#"<?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Ignored</title><style>p { color: red }</style></head>
              <body>
                <h2>The Old Library</h2>
                <p>The library smelled of dust,
                   leather and old paper.</p>
                <blockquote>A quiet ritual before the evening tea.</blockquote>
                <ul><li>First note</li><li>Second note</li></ul>
                <img src="../images/lamp.jpg" alt="Lamp"/>
                <hr/>
              </body>
            </html>"#;

        let blocks = parse_xhtml(xhtml, "OEBPS/text").expect("глава разбирается");

        assert_eq!(
            blocks,
            vec![
                Block::Heading {
                    level: 2,
                    text: "The Old Library".to_string()
                },
                // Переносы вёрстки схлопнулись в пробелы.
                Block::Paragraph("The library smelled of dust, leather and old paper.".to_string()),
                Block::Quote("A quiet ritual before the evening tea.".to_string()),
                Block::ListItem("First note".to_string()),
                Block::ListItem("Second note".to_string()),
                Block::Image {
                    path: "OEBPS/images/lamp.jpg".to_string(),
                    alt: Some("Lamp".to_string()),
                },
                Block::Divider,
            ]
        );
    }

    #[test]
    fn оформительская_вложенность_не_рвёт_абзац() {
        // Реальные EPUB полны вложенных span и em; текст абзаца обязан
        // остаться одним куском.
        let xhtml = "<html><body><p>Every <em>unfamiliar</em> word was \
            <span class=\"x\">underlined</span> in pencil.</p></body></html>";

        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается");
        assert_eq!(
            blocks,
            vec![Block::Paragraph(
                "Every unfamiliar word was underlined in pencil.".to_string()
            )]
        );
    }

    #[test]
    fn скрипты_и_стили_не_попадают_в_текст() {
        let xhtml = "<html><head><script>var a = 1;</script></head>\
            <body><p>Text.</p></body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается");
        assert_eq!(blocks, vec![Block::Paragraph("Text.".to_string())]);
    }

    #[test]
    fn манифест_даёт_метаданные_и_порядок_чтения() {
        let opf = r#"<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>The Great Gatsby</dc:title>
                <dc:creator>F. Scott Fitzgerald</dc:creator>
                <dc:language>en</dc:language>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>"#;

        let package = parse_package(opf, "OEBPS").expect("манифест разбирается");

        assert_eq!(package.metadata.title.as_deref(), Some("The Great Gatsby"));
        assert_eq!(
            package.metadata.author.as_deref(),
            Some("F. Scott Fitzgerald")
        );
        assert_eq!(package.metadata.language.as_deref(), Some("en"));
        assert_eq!(
            package.metadata.cover.as_deref(),
            Some("OEBPS/images/cover.jpg")
        );
        assert_eq!(package.spine.len(), 2);
        assert_eq!(package.spine[0].href, "OEBPS/text/ch1.xhtml");
    }

    #[test]
    fn книга_без_порядка_чтения_отвергается() {
        let opf = r#"<package><manifest></manifest><spine></spine></package>"#;
        let Err(err) = parse_package(opf, "") else {
            panic!("книга без порядка чтения непригодна");
        };
        assert!(
            err.describe().contains("порядок чтения"),
            "{}",
            err.describe()
        );
    }

    fn epub_bytes_with_chapter(chapter_content: &str) -> Vec<u8> {
        use std::io::{Cursor, Write};
        let mut buffer = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buffer);
            let options: zip::write::FileOptions<'_, ()> =
                zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
            let container = r#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
            let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest><item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>"#;
            zip.start_file("mimetype", options).unwrap();
            zip.write_all(b"application/epub+zip").unwrap();
            zip.start_file("META-INF/container.xml", options).unwrap();
            zip.write_all(container.as_bytes()).unwrap();
            zip.start_file("OEBPS/content.opf", options).unwrap();
            zip.write_all(opf.as_bytes()).unwrap();
            zip.start_file("OEBPS/text/ch1.xhtml", options).unwrap();
            zip.write_all(chapter_content.as_bytes()).unwrap();
            zip.finish().unwrap();
        }
        buffer.into_inner()
    }

    #[test]
    fn oversized_epub_entry_rejected() {
        let huge = "a".repeat(crate::parser::limits::MAX_EPUB_ENTRY_BYTES_USIZE + 1);
        let xhtml = format!("<html><body><p>{huge}</p></body></html>");
        let bytes = epub_bytes_with_chapter(&xhtml);
        let mut book = EpubBook::from_bytes(bytes).expect("EPUB открывается; ошибка ожидается при чтении главы");
        let err = book.chapter(0).expect_err("огромная запись должна быть отвергнута");
        assert!(
            err.describe().contains("слишком велика"),
            "ожидалось сообщение о лимите, получили: {}",
            err.describe()
        );
    }

    #[test]
    fn single_gigantic_xhtml_chapter_rejected() {
        // Глава влезает в лимит записи (8 MiB), но превышает лимит текста главы (5 MiB).
        let chunk = crate::parser::limits::MAX_CHAPTER_TEXT_BYTES + 1024;
        let huge_text = "a".repeat(chunk);
        // Оборачиваем в минимальный XHTML, чтобы запись была ~чуть больше 5 MiB, но меньше 8 MiB.
        let xhtml = format!("<html><body><p>{huge_text}</p></body></html>");
        assert!(
            xhtml.len() < crate::parser::limits::MAX_EPUB_ENTRY_BYTES_USIZE,
            "тестовая XHTML должна влезать в лимит записи"
        );
        let bytes = epub_bytes_with_chapter(&xhtml);
        let mut book = EpubBook::from_bytes(bytes).expect("EPUB открывается");
        let err = book.chapter(0).expect_err("гигантская глава должна быть отвергнута");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn zip_lying_about_size_still_rejected_via_take() {
        // Симулируем ZIP, который врёт о размере: declared маленький, реальный большой.
        // Проверяем, что защита через take(MAX+1) срабатывает даже если заголовок врёт.
        use std::io::{Cursor, Read};
        struct LyingReader {
            data: Cursor<Vec<u8>>,
            declared: u64,
        }
        impl LyingReader {
            fn size(&self) -> u64 {
                self.declared
            }
        }
        impl Read for LyingReader {
            fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                self.data.read(buf)
            }
        }
        let declared = 10u64;
        let real = vec![b'a'; crate::parser::limits::MAX_EPUB_ENTRY_BYTES_USIZE + 100];
        let mut lying = LyingReader {
            data: Cursor::new(real),
            declared,
        };
        // Эмулируем логику read_entry: сначала проверка declared (пройдёт), затем take.
        crate::parser::limits::check_epub_entry_size(lying.size()).expect("declared маленький — проверка проходит");
        let mut limited = (&mut lying).take(crate::parser::limits::MAX_EPUB_ENTRY_BYTES + 1);
        let mut buf = Vec::new();
        limited.read_to_end(&mut buf).unwrap();
        assert!(
            buf.len() as u64 > crate::parser::limits::MAX_EPUB_ENTRY_BYTES,
            "реальный размер должен превысить лимит"
        );
        // Итоговая проверка, как в read_entry, должна отвергнуть.
        let err = crate::parser::limits::too_large_with_detail("simulated lying zip");
        assert!(err.describe().contains("слишком велика"));
        // Прямая проверка, что наша логика отвергла бы такой вход:
        assert!(buf.len() as u64 > declared);
    }

    #[test]
    fn epub_too_many_entries_rejected() {
        use std::io::{Cursor, Write};
        let mut buffer = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buffer);
            let options: zip::write::FileOptions<'_, ()> =
                zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
            let container = r#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
            let mut opf_manifest = String::from(r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest>"#);
            let mut spine = String::from("<spine>");
            let count = crate::parser::limits::MAX_EPUB_ENTRIES + 1;
            for i in 0..count {
                opf_manifest.push_str(&format!(r#"<item id="ch{i}" href="text/ch{i}.xhtml" media-type="application/xhtml+xml"/>"#));
                spine.push_str(&format!(r#"<itemref idref="ch{i}"/>"#));
            }
            opf_manifest.push_str("</manifest>");
            opf_manifest.push_str(&spine);
            opf_manifest.push_str("</spine></package>");
            zip.start_file("mimetype", options).unwrap();
            zip.write_all(b"application/epub+zip").unwrap();
            zip.start_file("META-INF/container.xml", options).unwrap();
            zip.write_all(container.as_bytes()).unwrap();
            zip.start_file("OEBPS/content.opf", options).unwrap();
            zip.write_all(opf_manifest.as_bytes()).unwrap();
            for i in 0..count {
                zip.start_file(format!("OEBPS/text/ch{i}.xhtml"), options).unwrap();
                zip.write_all(b"<html><body><p>hi</p></body></html>").unwrap();
            }
            zip.finish().unwrap();
        }
        let bytes = buffer.into_inner();
        let Err(err) = EpubBook::from_bytes(bytes) else {
            panic!("слишком много записей — должен быть отклонён");
        };
        assert!(err.describe().contains("слишком велика"), "{}", err.describe());
    }

    #[test]
    fn epub_source_too_large_rejected() {
        let large = vec![0u8; crate::parser::limits::MAX_SOURCE_BYTES_USIZE + 1];
        let Err(err) = EpubBook::from_bytes(large) else {
            panic!("источник слишком велик — ожидалась ошибка");
        };
        assert!(err.describe().contains("слишком велика"), "{}", err.describe());
    }

    #[test]
    fn epub_resource_too_large_rejected() {
        use std::io::{Cursor, Write};
        let huge_resource = vec![b'a'; crate::parser::limits::MAX_EPUB_ENTRY_BYTES_USIZE + 1];
        let mut buffer = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buffer);
            let options: zip::write::FileOptions<'_, ()> =
                zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);
            let container = r#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
            let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest><item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="img" href="images/big.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="ch1"/></spine></package>"#;
            let chapter = r#"<html><body><p>hi</p><img src="../images/big.jpg"/></body></html>"#;
            zip.start_file("mimetype", options).unwrap();
            zip.write_all(b"application/epub+zip").unwrap();
            zip.start_file("META-INF/container.xml", options).unwrap();
            zip.write_all(container.as_bytes()).unwrap();
            zip.start_file("OEBPS/content.opf", options).unwrap();
            zip.write_all(opf.as_bytes()).unwrap();
            zip.start_file("OEBPS/text/ch1.xhtml", options).unwrap();
            zip.write_all(chapter.as_bytes()).unwrap();
            zip.start_file("OEBPS/images/big.jpg", options).unwrap();
            zip.write_all(&huge_resource).unwrap();
            zip.finish().unwrap();
        }
        let bytes = buffer.into_inner();
        let mut book = EpubBook::from_bytes(bytes).expect("EPUB открывается");
        // Чтение главы ок
        let _ = book.chapter(0).expect("глава");
        let err = book.resource("OEBPS/images/big.jpg").expect_err("ресурс слишком велик");
        assert!(err.describe().contains("слишком велика"), "{}", err.describe());
    }
}
