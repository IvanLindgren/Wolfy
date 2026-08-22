//! EPUB.
//!
//! EPUB — это zip-архив с XHTML внутри, и его устройство удобно ровно тем, что
//! позволяет не читать книгу целиком. Открытие достаёт три маленьких файла:
//! `META-INF/container.xml` укажет на манифест, манифест даст метаданные и
//! список файлов, `spine` — порядок чтения. Текст главы распаковывается
//! только тогда, когда читатель до неё дошёл.

use std::fs::File;
use std::io::Read;
use std::path::Path;

use quick_xml::events::Event;
use quick_xml::Reader;
use zip::ZipArchive;

use crate::error::{CoreError, Result};
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata};

/// Открытая книга EPUB.
pub struct EpubBook {
    archive: ZipArchive<File>,
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    /// Пути файлов глав в порядке чтения — параллельно `contents`.
    spine: Vec<String>,
}

impl EpubBook {
    pub fn open(path: &Path) -> Result<Self> {
        let file = File::open(path)?;
        let mut archive = ZipArchive::new(file)
            .map_err(|e| CoreError::Malformed(format!("не открывается архив EPUB: {e}")))?;

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
        let blocks = parse_xhtml(&xhtml, &parent_dir(&href))?;

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
        let mut bytes = Vec::new();
        entry.read_to_end(&mut bytes)?;
        Ok(bytes)
    }
}

/// Читает файл архива в строку.
fn read_entry(archive: &mut ZipArchive<File>, path: &str) -> Result<String> {
    let mut entry = archive
        .by_name(path)
        .map_err(|_| CoreError::Malformed(format!("в книге нет файла «{path}»")))?;
    let mut text = String::new();
    entry
        .read_to_string(&mut text)
        .map_err(|e| CoreError::Malformed(format!("файл «{path}» не читается как текст: {e}")))?;
    Ok(text)
}

/// Находит манифест книги через `META-INF/container.xml`.
///
/// Путь к манифесту не фиксирован стандартом, и книги действительно кладут его
/// куда попало — то в `OEBPS/content.opf`, то в корень. Поэтому спрашиваем
/// container.xml, а не угадываем.
fn find_opf(archive: &mut ZipArchive<File>) -> Result<String> {
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
}
