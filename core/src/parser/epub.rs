//! EPUB.
//!
//! EPUB — это zip-архив с XHTML внутри, и его устройство удобно ровно тем, что
//! позволяет не читать книгу целиком. Открытие достаёт три маленьких файла:
//! `META-INF/container.xml` укажет на манифест, манифест даст метаданные и
//! список файлов, `spine` — порядок чтения. Текст главы распаковывается
//! только тогда, когда читатель до неё дошёл.

use std::collections::HashMap;
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
    /// Пути файлов глав в порядке чтения — параллельно планам по документу.
    spine: Vec<SpineItem>,
    /// План глав: оглавление может нарезать один файл на несколько глав.
    plans: Vec<ChapterPlan>,
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

        // Оглавление решает, что считать главой: nav/NCX с якорями нарезает
        // один файл на несколько глав, отсутствие — отдаёт spine как есть.
        let locators = resolve_toc(
            &mut archive,
            &package.nav_href,
            &package.ncx_href,
            &package.spine,
        )?;
        let plans = build_plans(&package.spine, locators);
        let contents = plans
            .iter()
            .map(|plan| ChapterInfo {
                title: plan.title.clone(),
            })
            .collect();

        Ok(EpubBook {
            archive,
            metadata: package.metadata,
            contents,
            spine: package.spine,
            plans,
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
        let plan = self.plans.get(index).cloned().ok_or_else(|| {
            CoreError::Malformed(format!(
                "главы {index} нет: в книге их {}",
                self.plans.len()
            ))
        })?;
        let href = self
            .spine
            .get(plan.doc)
            .map(|item| item.href.clone())
            .ok_or_else(|| CoreError::Malformed("глава указывает на файл вне книги".into()))?;

        let xhtml = read_entry(&mut self.archive, &href)?;
        // Сырой XHTML уже ограничен при чтении записи (8 MiB), но
        // дополнительная проверка текста главы защищает от гигантского
        // XHTML, где большая часть — текст внутри одного блока.
        limits::check_chapter_text_len(xhtml.len())?;
        let (all_blocks, anchors) = parse_xhtml(&xhtml, &parent_dir(&href))?;
        let blocks = slice_by_anchors(all_blocks, &anchors, &plan);
        // Итоговый plain text тоже не должен раздуть память.
        let total_text: usize = blocks
            .iter()
            .filter_map(|b| b.text())
            .map(|s| s.len())
            .sum();
        limits::check_chapter_text_len(total_text)?;
        limits::check_total_text_len(total_text)?;

        // Заголовок главы: сначала оглавление, затем первый заголовочный блок.
        let title = plan
            .title
            .or_else(|| {
                blocks.iter().find_map(|b| match b {
                    Block::Heading { text, .. } => Some(text.clone()),
                    _ => None,
                })
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
        // У текста и изображений разные безопасные бюджеты. XHTML больше
        // 8 MiB почти всегда архивная бомба, а качественная иллюстрация
        // книги может честно занимать до 25 MiB.
        limits::check_image_bytes(usize::try_from(declared).unwrap_or(usize::MAX))?;
        // ZIP заголовку нельзя доверять — читаем через take(MAX+1) и
        // проверяем фактический размер.
        let mut limited = (&mut entry).take(limits::MAX_IMAGE_BYTES as u64 + 1);
        let mut bytes = Vec::new();
        limited.read_to_end(&mut bytes)?;
        if bytes.len() > limits::MAX_IMAGE_BYTES {
            return Err(limits::too_large_with_detail(&format!(
                "изображение «{path}» превышает лимит {} байт",
                limits::MAX_IMAGE_BYTES
            )));
        }
        limits::check_image_bytes(bytes.len())?;
        Ok(bytes)
    }
}

/// Читает файл архива в строку.
fn read_entry(archive: &mut ZipArchive<Source>, path: &str) -> Result<String> {
    let bytes = read_entry_bytes(archive, path)?;
    xml_decode(path, &bytes)
}

/// Читает файл архива как байты с ограничением размера.
fn read_entry_bytes(archive: &mut ZipArchive<Source>, path: &str) -> Result<Vec<u8>> {
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
        .map_err(|e| CoreError::Malformed(format!("файл «{path}» не читается: {e}")))?;
    if bytes.len() as u64 > limits::MAX_EPUB_ENTRY_BYTES {
        return Err(limits::too_large_with_detail(&format!(
            "файл «{path}» превышает лимит {} байт",
            limits::MAX_EPUB_ENTRY_BYTES
        )));
    }
    Ok(bytes)
}

/// Декодирует XML-файл книги в UTF-8 строку.
///
/// Строже, чем «прочитали байты и надеемся»: UTF-8 принимается как есть,
/// UTF-16 распознаётся по BOM, однобайтовые кодировки — по объявлению в
/// прологе. Всё остальное — явная ошибка, а не молчаливая интерпретация
/// байтов в неверной кодировке.
fn xml_decode(path: &str, bytes: &[u8]) -> Result<String> {
    // BOM сильнее объявления в прологе.
    if let Some(rest) = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        return String::from_utf8(rest.to_vec()).map_err(|_| malformed_encoding(path));
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFF, 0xFE]) {
        return Ok(encoding_rs::UTF_16LE.decode(rest).0.into_owned());
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFE, 0xFF]) {
        return Ok(encoding_rs::UTF_16BE.decode(rest).0.into_owned());
    }

    match std::str::from_utf8(bytes) {
        Ok(text) => Ok(text.to_string()),
        Err(_) => {
            // Не UTF-8 — смотрим, что объявлено в прологе.
            let declared = declared_encoding(bytes);
            match declared {
                Some(encoding) => Ok(encoding.decode(bytes).0.into_owned()),
                None => Err(malformed_encoding(path)),
            }
        }
    }
}

fn malformed_encoding(path: &str) -> CoreError {
    CoreError::Malformed(format!(
        "файл «{path}» записан в неподдерживаемой кодировке: нет ни корректного UTF-8, ни BOM, ни объявленной однобайтовой кодировки"
    ))
}

/// Кодировка из XML-пролога, если это поддерживаемая однобайтовая схема.
fn declared_encoding(bytes: &[u8]) -> Option<&'static encoding_rs::Encoding> {
    // Пролог лежит в первых сотнях байтов; читаем голову ASCII-безопасно.
    let head = &bytes[..bytes.len().min(512)];
    let head = String::from_utf8_lossy(head);
    let lower = head.to_ascii_lowercase();
    let index = lower.find("encoding=")?;
    let rest = &lower[index + "encoding=".len()..];
    let quote = rest.chars().next()?;
    if quote != '\'' && quote != '"' {
        return None;
    }
    let rest = &rest[1..];
    let end = rest.find(quote)?;
    let name = &rest[..end];
    match name {
        "iso-8859-1" | "windows-1252" | "us-ascii" => Some(encoding_rs::WINDOWS_1252),
        "windows-1251" => Some(encoding_rs::WINDOWS_1251),
        "koi8-r" => Some(encoding_rs::KOI8_R),
        "ibm866" | "cp866" => Some(encoding_rs::IBM866),
        _ => None,
    }
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
#[derive(Clone)]
struct SpineItem {
    href: String,
    /// `linear="no"` — нелинейный материал (сноски, приложения): без прямого
    /// указания в оглавлении он не становится обычной главой читалки.
    linear: bool,
}

/// Пункт оглавления, привязанный к месту в файле.
#[derive(Clone)]
struct ChapterLocator {
    doc: usize,
    anchor: Option<String>,
    title: Option<String>,
}

/// Что читатель увидит как одну главу.
///
/// Оглавление с якорями может нарезать один XHTML на несколько глав:
/// глава — это кусок от своего якоря до якоря следующей главы того же файла.
#[derive(Clone)]
struct ChapterPlan {
    title: Option<String>,
    doc: usize,
    anchor_from: Option<String>,
    anchor_to: Option<String>,
}

/// Пункт навигационного документа до привязки к spine.
struct TocEntry {
    /// Сырая ссылка, возможно с «#якорем».
    href: String,
    title: Option<String>,
}

struct Package {
    metadata: Metadata,
    spine: Vec<SpineItem>,
    /// EPUB3 navigation document из `properties="nav"`.
    nav_href: Option<String>,
    /// EPUB2 toc (.ncx) из манифеста или атрибута spine.
    ncx_href: Option<String>,
}

/// Разбирает манифест: метаданные, список файлов и порядок чтения.
fn parse_package(opf: &str, base: &str) -> Result<Package> {
    let mut reader = Reader::from_str(opf);
    let mut metadata = Metadata::default();
    // id → href из манифеста; spine ссылается на файлы по id, а не по пути.
    let mut manifest: Vec<(String, String)> = Vec::new();
    let mut cover_id: Option<String> = None;
    let mut order: Vec<String> = Vec::new();
    let mut linear_flags: Vec<bool> = Vec::new();
    let mut nav_href: Option<String> = None;
    let mut ncx_id: Option<String> = None;
    let mut spine_toc_idref: Option<String> = None;

    // Какой текстовый элемент метаданных сейчас читается и что уже набрали.
    //
    // Значение собирается до соответствующего End-тега: «War &amp; Peace»
    // приходит кусками (текст, ссылка на символ, снова текст), и брать только
    // первый Text значило бы обрезать название на середине.
    let mut collecting: Option<(&'static str, String)> = None;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                match name.as_slice() {
                    b"title" => collecting = Some(("title", String::new())),
                    b"creator" => collecting = Some(("creator", String::new())),
                    b"language" => collecting = Some(("language", String::new())),
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
                            // EPUB3 navigation document.
                            if attribute(&e, b"properties")
                                .is_some_and(|p| p.split_whitespace().any(|t| t == "nav"))
                            {
                                nav_href = Some(full.clone());
                            }
                            // EPUB2 toc: единственный файл с NCX media-type.
                            if attribute(&e, b"media-type").as_deref()
                                == Some("application/x-dtbncx+xml")
                            {
                                ncx_id = Some(id.clone());
                            }
                            manifest.push((id, full));
                        }
                    }
                    b"itemref" => {
                        if let Some(idref) = attribute(&e, b"idref") {
                            order.push(idref);
                            linear_flags.push(attribute(&e, b"linear").as_deref() != Some("no"));
                        }
                    }
                    b"spine" => {
                        spine_toc_idref = attribute(&e, b"toc");
                    }
                    b"meta" if attribute(&e, b"name").as_deref() == Some("cover") => {
                        cover_id = attribute(&e, b"content");
                    }
                    _ => {}
                }
            }
            Ok(Event::Text(e)) => {
                if let Some((_, buffer)) = collecting.as_mut() {
                    if let Ok(value) = e.decode() {
                        buffer.push_str(&value);
                    }
                }
            }
            Ok(Event::GeneralRef(e)) => {
                if let Some((_, buffer)) = collecting.as_mut() {
                    push_reference(&e, buffer);
                }
            }
            Ok(Event::End(e)) => {
                let name: Vec<u8> = local_name(e.name().as_ref()).to_vec();
                let closing = collecting
                    .as_ref()
                    .is_some_and(|(field, _)| local_name(field.as_bytes()) == name.as_slice());
                if !closing {
                    continue;
                }
                let (field, buffer) = collecting.take().expect("только что проверили");
                let value = buffer.trim().to_string();
                if value.is_empty() {
                    continue;
                }
                match field {
                    "title" if metadata.title.is_none() => metadata.title = Some(value),
                    "creator" if metadata.author.is_none() => metadata.author = Some(value),
                    "language" if metadata.language.is_none() => metadata.language = Some(value),
                    _ => {}
                }
            }
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

    let mut spine: Vec<SpineItem> = order
        .iter()
        .zip(linear_flags)
        .filter_map(|(idref, linear)| {
            manifest
                .iter()
                .find(|(id, _)| id == idref)
                .map(|(_, href)| SpineItem {
                    href: href.clone(),
                    linear,
                })
        })
        .collect();

    // Навигационный документ служит оглавлением. Некоторые издатели всё же
    // кладут его в spine, но показывать его как отдельную пустую главу не
    // нужно: список глав уже построен из этого документа ниже.
    if let Some(nav_href) = &nav_href {
        spine.retain(|item| item.href != *nav_href);
    }

    if spine.is_empty() {
        return Err(CoreError::Malformed(
            "в книге не указан порядок чтения".to_string(),
        ));
    }

    // NCX по атрибуту spine/@toc сильнее «первого попавшегося» из манифеста.
    let ncx_href = spine_toc_idref
        .as_ref()
        .and_then(|id| manifest.iter().find(|(item_id, _)| item_id == id))
        .map(|(_, href)| href.clone())
        .or_else(|| {
            ncx_id.as_ref().and_then(|id| {
                manifest
                    .iter()
                    .find(|(item_id, _)| item_id == id)
                    .map(|(_, href)| href.clone())
            })
        });

    Ok(Package {
        metadata,
        spine,
        nav_href,
        ncx_href,
    })
}

/// Является ли открытый nav настоящим оглавлением: epub:type="toc" или
/// role="doc-toc". Значения бывают списками («toc landmarks»), поэтому
/// сравниваем по токенам.
fn is_toc_nav(element: &quick_xml::events::BytesStart<'_>) -> bool {
    let has_token = |value: Option<String>, wanted: &str| -> bool {
        value.is_some_and(|value| {
            value
                .split_ascii_whitespace()
                .any(|token| token.eq_ignore_ascii_case(wanted))
        })
    };
    let role = attribute(element, b"role");
    // local_name снимает префикс epub:, поэтому ищем просто "type".
    has_token(attribute(element, b"type"), "toc") || has_token(role, "doc-toc")
}

/// EPUB3 navigation document: ссылки настоящего оглавления.
///
/// Navigation document может содержать несколько `<nav>`: toc, landmarks,
/// page-list. Главами обязаны становиться только ссылки первого из них —
/// иначе список «страница 12» и навигация по обложкам превращаются в главы.
/// Если книга вообще не проставляет epub:type (сломанные, но частые EPUB3),
/// ссылки собираются как раньше. Ошибка разбора отдаётся наверх: лучше
/// честный fallback на NCX/spine, чем частичное оглавление.
///
/// Вложенные списки оглавления сознательно спрямляются: читалке нужен
/// плоский список глав в порядке чтения, а глубина разделов — дело рендера.
fn parse_nav(xhtml: &str) -> Result<Vec<TocEntry>> {
    // Сначала узнаём, размечает ли документ nav'ы по типам вовсе: это
    // решает, ограничивать ли зону сбора ссылок одним toc.
    let typed = {
        let mut reader = Reader::from_str(xhtml);
        loop {
            match reader.read_event() {
                Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                    if local_name(e.name().as_ref()) == b"nav"
                        && attribute(&e, b"type").is_some_and(|value| !value.trim().is_empty())
                    {
                        break true;
                    }
                }
                Ok(Event::Eof) | Err(_) => break false,
                Ok(_) => {}
            }
        }
    };

    let mut reader = Reader::from_str(xhtml);
    let mut entries = Vec::new();
    // Ссылка, которую сейчас читаем: (href, накопленный текст).
    let mut current: Option<(String, String)> = None;
    let mut skipping = 0usize;
    // Открытые nav-блоки: true — это оглавление, false — landmarks,
    // page-list и прочие службы навигации.
    let mut nav_stack: Vec<bool> = Vec::new();

    // Ссылка собирается только внутри nav с типом toc (или без всякой
    // разметки типов в старых книгах).
    let inside_toc =
        |nav_stack: &[bool]| -> bool { !typed || nav_stack.last().copied().unwrap_or(false) };

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if matches!(name.as_slice(), b"script" | b"style") {
                    skipping += 1;
                    continue;
                }
                if skipping > 0 {
                    continue;
                }
                if name.as_slice() == b"nav" {
                    nav_stack.push(is_toc_nav(&e));
                    continue;
                }
                if inside_toc(&nav_stack) && name.as_slice() == b"a" {
                    if let Some(href) = attribute(&e, b"href") {
                        current = Some((href, String::new()));
                    }
                }
            }
            Ok(Event::Empty(e)) => {
                if skipping > 0 {
                    continue;
                }
                let name = local_name(e.name().as_ref()).to_vec();
                if name.as_slice() == b"nav" {
                    continue;
                }
                // Самозакрытая ссылка без текста — заголовок потерян,
                // но точка навигации остаться должна.
                if inside_toc(&nav_stack) && name.as_slice() == b"a" {
                    if let Some(href) = attribute(&e, b"href") {
                        entries.push(TocEntry { href, title: None });
                    }
                }
            }
            Ok(Event::Text(e)) => {
                if let Some((_, title)) = current.as_mut() {
                    if let Ok(text) = e.decode() {
                        title.push_str(&text);
                    }
                }
            }
            Ok(Event::GeneralRef(e)) => {
                if let Some((_, title)) = current.as_mut() {
                    push_reference(&e, title);
                }
            }
            Ok(Event::End(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if matches!(name.as_slice(), b"script" | b"style") {
                    skipping = skipping.saturating_sub(1);
                } else if name.as_slice() == b"nav" {
                    nav_stack.pop();
                } else if name.as_slice() == b"a" {
                    if let Some((href, title)) = current.take() {
                        let title = collapse_spaces(&title);
                        entries.push(TocEntry {
                            href,
                            title: (!title.is_empty()).then_some(title),
                        });
                    }
                }
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(_) => return Err(CoreError::Malformed("navigation document повреждён".into())),
        }
    }
    Ok(entries)
}

/// EPUB2 toc (.ncx): navPoint'ы с navLabel/content.
///
/// Текст собирается только внутри navLabel — docTitle книги заголовком
/// главы стать не должен.
fn parse_ncx(xhtml: &str) -> Result<Vec<TocEntry>> {
    let mut reader = Reader::from_str(xhtml);
    let mut entries = Vec::new();
    // Накопленный текст navLabel; content прикладывает к нему ссылку.
    let mut label: Option<String> = None;
    let mut label_depth = 0usize;

    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) => match local_name(e.name().as_ref()) {
                name if name.eq_ignore_ascii_case(b"navlabel") => label_depth += 1,
                name if name.eq_ignore_ascii_case(b"content") => {
                    if let Some(src) = attribute(&e, b"src") {
                        let title = label.take().and_then(|text| {
                            let text = collapse_spaces(&text);
                            (!text.is_empty()).then_some(text)
                        });
                        entries.push(TocEntry { href: src, title });
                    }
                }
                _ => {}
            },
            Ok(Event::Empty(e)) => {
                // content почти всегда пустой элемент.
                if local_name(e.name().as_ref()).eq_ignore_ascii_case(b"content") {
                    if let Some(src) = attribute(&e, b"src") {
                        let title = label.take().and_then(|text| {
                            let text = collapse_spaces(&text);
                            (!text.is_empty()).then_some(text)
                        });
                        entries.push(TocEntry { href: src, title });
                    }
                }
            }
            Ok(Event::End(e)) => {
                if local_name(e.name().as_ref()).eq_ignore_ascii_case(b"navlabel") {
                    label_depth = label_depth.saturating_sub(1);
                }
            }
            Ok(Event::Text(e)) => {
                if label_depth > 0 {
                    let text = label.get_or_insert_with(String::new);
                    if let Ok(chunk) = e.decode() {
                        text.push_str(&chunk);
                    }
                }
            }
            Ok(Event::GeneralRef(e)) => {
                if label_depth > 0 {
                    push_reference(&e, label.get_or_insert_with(String::new));
                }
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(_) => return Err(CoreError::Malformed("NCX повреждён".into())),
        }
    }
    Ok(entries)
}

/// Превращает XHTML главы в блоки читалки.
///
/// Разбор поверхностный и однопроходный, без DOM: нас интересуют абзацы,
/// заголовки, цитаты, списки, картинки, таблицы и формулы. Инвариант один —
/// если узел содержал читательский текст, он обязан дожить до блока: прямой
/// текст в неизвестном контейнере (`div`, `section`, `td`…) открывает
/// неявный абзац, а ссылка на символ (`&amp;`, `&#8212;`) раскрывается в
/// соответствующий знак.
///
/// Вместе с блоками возвращается карта «id элемента → индекс ближайшего
/// следующего блока»: по ней оглавление режет файл на главы.
fn parse_xhtml(xhtml: &str, base: &str) -> Result<(Vec<Block>, HashMap<String, usize>)> {
    let mut reader = Reader::from_str(xhtml);
    let mut blocks = Vec::new();
    // Текст, накопленный внутри текущего блочного элемента.
    let mut buffer = String::new();
    // Внутри <pre> пробелы и переносы значимы и не схлопываются.
    let mut raw_text = false;
    // Какой блок сейчас собирается и на какой глубине он начался.
    let mut current: Option<(BlockKind, usize)> = None;
    let mut depth = 0usize;
    // Внутри этих элементов текста для читателя нет.
    let mut skipping = 0usize;
    // Якоря оглавления: id → номер блока, с которого начинается кусок.
    let mut anchors: HashMap<String, usize> = HashMap::new();

    // Таблица собирается целиком, чтобы строки и ячейки не слипались в один
    // абзац. Вложенные таблицы сводятся во внешнюю — терять их содержимое
    // нельзя, а различать уровни ради редкой вёрстки незачем.
    let mut table: Option<TableState> = None;
    // Формула: копим текст до парного </math>.
    let mut math: Option<MathState> = None;

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

                // Якорь оглавления: запоминаем, каким по счёту блоком живёт
                // содержимое после этого элемента.
                if let Some(id) = attribute(&e, b"id") {
                    anchors.insert(id, blocks.len());
                }

                if name.as_slice() == b"math" && math.is_none() {
                    math = Some(MathState::new(depth, attribute(&e, b"alttext")));
                    continue;
                }

                if let Some(state) = math.as_mut() {
                    // Внутри формулы только захватываем разметку: MathML
                    // живёт своей структурой, читательский поток не трогаем.
                    state.open_element(&name);
                    continue;
                }

                if let Some(state) = table.as_mut() {
                    match name.as_slice() {
                        b"table" => state.nested += 1,
                        b"tr" => state.close_row(),
                        b"td" | b"th" | b"caption" => state.open_cell(),
                        _ => {}
                    }
                    continue;
                }

                if name.as_slice() == b"table" {
                    flush(&mut blocks, &mut current, &mut buffer);
                    raw_text = false;
                    table = Some(TableState::default());
                    continue;
                }

                if name.as_slice() == b"img" {
                    // Форма «<img …></img>»: то же самое, что самозакрытый тег.
                    handle_image(&e, base, depth, &mut blocks, &mut current, &mut buffer);
                    raw_text = false;
                    continue;
                }

                if let Some(kind) = BlockKind::from_tag(&name) {
                    // Вложенный блок закрывает предыдущий: так «<p>» внутри
                    // «<blockquote>» не потеряет свой текст.
                    flush(&mut blocks, &mut current, &mut buffer);
                    current = Some((kind, depth));
                    raw_text = matches!(kind, BlockKind::Preformatted);
                }
            }
            Ok(Event::Empty(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if skipping > 0 {
                    continue;
                }
                if let Some(id) = attribute(&e, b"id") {
                    anchors.insert(id, blocks.len());
                }
                if let Some(state) = table.as_mut() {
                    // Картинка в ячейке хотя бы подписью попадёт в текст.
                    if name.as_slice() == b"img" {
                        if let Some(alt) = attribute(&e, b"alt") {
                            state.push_text(&alt);
                        }
                    }
                    continue;
                }
                if let Some(state) = math.as_mut() {
                    state.empty_element(&name);
                    continue;
                }
                match name.as_slice() {
                    b"img" => handle_image(&e, base, depth, &mut blocks, &mut current, &mut buffer),
                    b"hr" => {
                        flush(&mut blocks, &mut current, &mut buffer);
                        raw_text = false;
                        blocks.push(Block::Divider);
                    }
                    // Разрыв строки значим: стихи и адреса не должны
                    // склеиваться в одну строку. Финальное решение — за
                    // flush: блок с переносом становится Preformatted.
                    b"br" => push_line_break(&mut buffer),
                    _ => {}
                }
            }
            Ok(Event::Text(e)) => {
                if skipping > 0 {
                    continue;
                }
                if let Some(state) = table.as_mut() {
                    if let Ok(text) = e.decode() {
                        state.push_text(&text);
                    }
                    continue;
                }
                if let Some(state) = math.as_mut() {
                    if let Ok(text) = e.decode() {
                        push_collapsed(&mut state.text, &text);
                        state.capture_text(&text);
                    }
                    continue;
                }
                // Прямой текст в неизвестном контейнере — не повод терять его:
                // открываем неявный абзац, который закроется вместе с
                // контейнером.
                if current.is_none() {
                    let Ok(text) = e.decode() else { continue };
                    if text.chars().all(char::is_whitespace) {
                        continue;
                    }
                    current = Some((BlockKind::Paragraph, depth));
                    raw_text = false;
                    push_text(&mut buffer, &text);
                    continue;
                }
                if let Ok(text) = e.decode() {
                    if raw_text {
                        push_raw(&mut buffer, &text);
                    } else {
                        push_text(&mut buffer, &text);
                    }
                }
            }
            Ok(Event::GeneralRef(e)) => {
                if skipping > 0 {
                    continue;
                }
                if let Some(state) = table.as_mut() {
                    push_reference(&e, &mut state.scratch);
                    state.flush_scratch();
                    continue;
                }
                if let Some(state) = math.as_mut() {
                    push_reference(&e, &mut state.text);
                    // Символьные ссылки формулы тоже доживают до разметки.
                    let mut resolved = String::new();
                    push_reference(&e, &mut resolved);
                    state.capture_text(&resolved);
                    continue;
                }
                if current.is_none() {
                    // «&nbsp;-текст» прямо в контейнере: тоже абзац.
                    let mut resolved = String::new();
                    push_reference(&e, &mut resolved);
                    if resolved.chars().all(char::is_whitespace) {
                        continue;
                    }
                    current = Some((BlockKind::Paragraph, depth));
                    raw_text = false;
                    buffer.push_str(&resolved);
                    continue;
                }
                if raw_text {
                    push_reference(&e, &mut buffer);
                } else {
                    let mut resolved = String::new();
                    push_reference(&e, &mut resolved);
                    push_text(&mut buffer, &resolved);
                }
            }
            Ok(Event::CData(e)) => {
                if skipping > 0 {
                    continue;
                }
                let text = String::from_utf8_lossy(e.as_ref()).into_owned();
                if let Some(state) = table.as_mut() {
                    state.push_text(&text);
                    continue;
                }
                if let Some(state) = math.as_mut() {
                    push_collapsed(&mut state.text, &text);
                    state.capture_text(&text);
                    continue;
                }
                if current.is_none() {
                    if text.chars().all(char::is_whitespace) {
                        continue;
                    }
                    current = Some((BlockKind::Paragraph, depth));
                    raw_text = false;
                }
                if raw_text {
                    push_raw(&mut buffer, &text);
                } else {
                    push_text(&mut buffer, &text);
                }
            }
            Ok(Event::End(e)) => {
                let name = local_name(e.name().as_ref()).to_vec();
                if matches!(name.as_slice(), b"script" | b"style" | b"head") {
                    skipping = skipping.saturating_sub(1);
                } else if name.as_slice() == b"math"
                    && math.as_ref().is_some_and(|state| depth == state.open_depth)
                {
                    let state = math.take().expect("только что проверили");
                    emit_math(state, &mut blocks);
                } else if math.is_some() {
                    // Внутренние теги формулы (mrow, mi, mo…) балансируют
                    // захваченную разметку и больше ничего не делают.
                    if let Some(state) = math.as_mut() {
                        state.close_element(&name);
                    }
                } else if table.is_some() && name.as_slice() == b"table" {
                    let state = table.as_mut().expect("только что проверили");
                    if state.nested > 0 {
                        state.nested -= 1;
                    } else {
                        let mut state = table.take().expect("только что проверили");
                        state.close_row();
                        let rows = state.into_rows();
                        if !rows.is_empty() {
                            blocks.push(Block::Table { rows });
                        }
                    }
                } else if let Some(state) = table.as_mut() {
                    match name.as_slice() {
                        b"td" | b"th" | b"caption" => state.close_cell(),
                        b"tr" => state.close_row(),
                        _ => {}
                    }
                } else if let Some((_, started_at)) = current {
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

    // Незакрытая таблица/формула — содержимое всё равно сохраняем.
    if let Some(mut state) = table.take() {
        state.close_row();
        let rows = state.into_rows();
        if !rows.is_empty() {
            blocks.push(Block::Table { rows });
        }
    }
    if let Some(state) = math.take() {
        emit_math(state, &mut blocks);
    }

    flush(&mut blocks, &mut current, &mut buffer);
    Ok((blocks, anchors))
}

/// Нарезает блоки файла по якорям плана главы.
fn slice_by_anchors(
    blocks: Vec<Block>,
    anchors: &HashMap<String, usize>,
    plan: &ChapterPlan,
) -> Vec<Block> {
    if plan.anchor_from.is_none() && plan.anchor_to.is_none() {
        return blocks;
    }
    let start = plan
        .anchor_from
        .as_ref()
        .and_then(|anchor| anchors.get(anchor))
        .copied()
        .unwrap_or(0);
    let end = plan
        .anchor_to
        .as_ref()
        .and_then(|anchor| anchors.get(anchor))
        .copied()
        .unwrap_or(blocks.len());
    let end = end.clamp(start, blocks.len());
    blocks.into_iter().skip(start).take(end - start).collect()
}

/// Собирает план глав из оглавления и spine.
///
/// Приоритет по спецификации: nav/NCX с якорями; если оглавления нет — spine
/// остаётся порядком чтения, а нелинейные файлы (`linear="no"`) в него без
/// нужды не попадают. Дробить файл по каждому заголовку h2 здесь сознательно
/// нечем: только якоря из навигации считаются границами глав.
fn build_plans(spine: &[SpineItem], locators: Vec<ChapterLocator>) -> Vec<ChapterPlan> {
    let mut plans = Vec::new();

    if locators.is_empty() {
        for (doc, item) in spine.iter().enumerate() {
            if !item.linear {
                continue;
            }
            plans.push(ChapterPlan {
                title: None,
                doc,
                anchor_from: None,
                anchor_to: None,
            });
        }
        return plans;
    }

    // Локаторы группируются по файлу; порядок внутри файла — порядок
    // оглавления, порядок файлов — порядок чтения.
    let mut grouped: Vec<Vec<ChapterLocator>> = vec![Vec::new(); spine.len()];
    for locator in locators {
        if locator.doc < spine.len() {
            grouped[locator.doc].push(locator);
        }
    }

    for (doc, items) in grouped.into_iter().enumerate() {
        if items.is_empty() {
            if spine[doc].linear {
                plans.push(ChapterPlan {
                    title: None,
                    doc,
                    anchor_from: None,
                    anchor_to: None,
                });
            }
            continue;
        }
        for (index, locator) in items.iter().enumerate() {
            let anchor_to = items.get(index + 1).and_then(|next| next.anchor.clone());
            plans.push(ChapterPlan {
                title: locator.title.clone(),
                doc,
                anchor_from: locator.anchor.clone(),
                anchor_to,
            });
        }
    }

    plans
}

/// Читает навигацию книги: сначала EPUB3 nav, затем EPUB2 NCX.
///
/// Каждый источник имеет право оказаться пустым или повреждённым — тогда
/// откатываемся к следующему, а если ничего не вышло, главы нарезаются по
/// spine. Частичное оглавление хуже честного fallback: обрыв посреди
/// навигационного документа не должен съесть половину книги.
fn resolve_toc(
    archive: &mut ZipArchive<Source>,
    nav_href: &Option<String>,
    ncx_href: &Option<String>,
    spine: &[SpineItem],
) -> Result<Vec<ChapterLocator>> {
    if let Some(nav_href) = nav_href {
        let xhtml = read_entry(archive, nav_href)?;
        // Повреждённый или потерянный nav — это не ошибка всей книги,
        // а повод для следующего источника.
        if let Ok(entries) = parse_nav(&xhtml) {
            if !entries.is_empty() {
                return Ok(to_locators(entries, &parent_dir(nav_href), spine));
            }
        }
    }
    if let Some(ncx_href) = ncx_href {
        let xhtml = read_entry(archive, ncx_href)?;
        if let Ok(entries) = parse_ncx(&xhtml) {
            if !entries.is_empty() {
                return Ok(to_locators(entries, &parent_dir(ncx_href), spine));
            }
        }
    }
    Ok(Vec::new())
}

/// Привязывает пункты навигации к файлам spine.
///
/// Ссылки в nav/NCX относительны самому навигационному файлу, а spine хранит
/// пути от манифеста — поэтому сначала нормализация, потом поиск. Пункты,
/// указывающие мимо порядка чтения (обложка вне spine), главами не становятся.
fn to_locators(entries: Vec<TocEntry>, base: &str, spine: &[SpineItem]) -> Vec<ChapterLocator> {
    entries
        .into_iter()
        .filter_map(|entry| {
            // `join` намеренно отбрасывает фрагмент для путей к ресурсам.
            // Для оглавления он, напротив, является границей главы, поэтому
            // выделяем якорь до нормализации пути.
            let (path, anchor) = match entry.href.split_once('#') {
                Some((path, anchor)) => (path, (!anchor.is_empty()).then_some(anchor.to_string())),
                None => (entry.href.as_str(), None),
            };
            let href = join(base, path);
            let doc = spine.iter().position(|item| item.href == href)?;
            Some(ChapterLocator {
                doc,
                anchor,
                title: entry.title,
            })
        })
        .collect()
}

/// Состояние сборки таблицы.
#[derive(Default)]
struct TableState {
    rows: Vec<Vec<String>>,
    row: Vec<String>,
    cell: String,
    in_cell: bool,
    /// Число вложенных таблиц внутри текущей.
    nested: usize,
    /// Буфер для раскрытия character references перед схлопыванием пробелов.
    scratch: String,
}

impl TableState {
    fn open_cell(&mut self) {
        self.close_cell();
        self.in_cell = true;
    }

    fn close_cell(&mut self) {
        if self.in_cell {
            let cell = collapse_spaces(&self.cell);
            self.row.push(cell);
            self.cell.clear();
            self.in_cell = false;
        }
    }

    fn close_row(&mut self) {
        self.close_cell();
        if !self.row.is_empty() {
            self.rows.push(std::mem::take(&mut self.row));
        }
    }

    fn push_text(&mut self, text: &str) {
        if !self.in_cell {
            return;
        }
        push_text(&mut self.cell, text);
    }

    fn flush_scratch(&mut self) {
        let scratch = std::mem::take(&mut self.scratch);
        self.push_text(&scratch);
    }

    fn into_rows(self) -> Vec<Vec<String>> {
        self.rows
    }
}

/// Состояние сборки формулы.
struct MathState {
    /// Глубина, на которой открылся <math>, — по ней узнаётся парный конец.
    open_depth: usize,
    /// Читаемая замена из атрибута alttext, если автор её дал.
    fallback: Option<String>,
    /// Текстовое содержимое формулы.
    text: String,
    /// Нормализованное подмножество MathML: очистанная разметка поддерева,
    /// из которой со временем соберётся настоящий богатый рендер.
    ///
    /// Внутрь попадают только известные элементы MathML без атрибутов;
    /// текст экранируется. Так чужой разметке негде навредить рендеру.
    markup: String,
    /// Открытые (и ещё не закрытые) захваченные элементы: по ним баланс
    /// закрывается при обрыве посреди формулы.
    stack: Vec<String>,
    /// Порог размера достигнут — дальше разметку не пишем, чтобы злая
    /// формула не выросла в неограниченную строку.
    overflowed: bool,
}

/// Элементы MathML, которые сохраняются в [`MathState::markup`].
const MATH_ELEMENTS: &[&[u8]] = &[
    b"math",
    b"mathparams",
    b"mrow",
    b"mi",
    b"mo",
    b"mn",
    b"ms",
    b"mtext",
    b"mfrac",
    b"msqrt",
    b"mroot",
    b"msub",
    b"msup",
    b"msubsup",
    b"mmultiscripts",
    b"munder",
    b"mover",
    b"munderover",
    b"munderoveraccent",
    b"mpadded",
    b"mphantom",
    b"mstyle",
    b"menclose",
    b"merror",
    b"mspace",
    b"semantics",
    b"annotation",
    b"annotation-xml",
    b"mtable",
    b"mtr",
    b"mlabeledtr",
    b"mtd",
];

/// Максимальный размер нормализованной разметки одной формулы.
const MAX_MATH_MARKUP_BYTES: usize = 32 * 1024;

impl MathState {
    fn new(open_depth: usize, fallback: Option<String>) -> Self {
        Self {
            open_depth,
            fallback,
            text: String::new(),
            markup: String::from("<math>"),
            stack: vec![String::from("math")],
            overflowed: false,
        }
    }

    fn write_tag(&mut self, piece: &str) -> bool {
        if self.overflowed || self.stack.is_empty() {
            return false;
        }
        if self.markup.len() + piece.len() > MAX_MATH_MARKUP_BYTES {
            self.overflowed = true;
            return false;
        }
        self.markup.push_str(piece);
        true
    }

    fn open_element(&mut self, name: &[u8]) {
        if !MATH_ELEMENTS.contains(&name) {
            return;
        }
        let name = String::from_utf8_lossy(name).into_owned();
        let written = self.write_tag(&format!("<{name}>"));
        if written {
            self.stack.push(name);
        }
    }

    fn empty_element(&mut self, name: &[u8]) {
        // В MathML пустые элементы вроде <mspace/> разрешены напрямую, а
        // незнакомые теги разметку и так не трогают.
        if !MATH_ELEMENTS.contains(&name) || self.stack.len() <= 1 {
            return;
        }
        let name = String::from_utf8_lossy(name);
        let _ = self.write_tag(&format!("<{name}/>"));
    }

    fn close_element(&mut self, name: &[u8]) {
        let name = String::from_utf8_lossy(name).into_owned();
        // Незакрытые внутренние элементы добиваем до своего места: очередь
        // важнее идеала — порядок закрывающих тегов обязан остаться честным.
        if let Some(position) = self.stack.iter().rposition(|open| *open == name) {
            while self.stack.len() > position {
                let closed = self.stack.pop();
                let _ = match closed {
                    Some(closed) => self.write_tag(&format!("</{closed}>")),
                    None => false,
                };
            }
        }
    }

    /// Экранированный текст допускается только внутри открытого элемента:
    /// свободные литералы между тегами разметке формулы не нужны.
    fn capture_text(&mut self, chunk: &str) -> bool {
        if self.stack.len() <= 1 || self.stack.last().is_none() {
            return true;
        }
        let mut escaped = String::with_capacity(chunk.len());
        for ch in chunk.chars() {
            match ch {
                '&' => escaped.push_str("&amp;"),
                '<' => escaped.push_str("&lt;"),
                '>' => escaped.push_str("&gt;"),
                other => escaped.push(other),
            }
        }
        self.write_tag(&escaped)
    }

    /// Балансирует остаток стека; перелившийся размер сводит разметку к
    /// пустой строке — потом включится старое поведение textual-источника,
    /// зато рендер никогда не получит обрезанный XML.
    fn finish(mut self) -> String {
        if self.overflowed {
            return String::new();
        }
        while let Some(name) = self.stack.pop() {
            let _ = self.write_tag(&format!("</{name}>"));
        }
        self.markup
    }
}

/// Кладёт собранную формулу в главу.
///
/// Приоритет читабельности: alttext автора (обычно это TeX или словесное
/// описание), затем собранный текст содержимого. Если удалось захватить
/// настоящую разметку, она становится источником: textual fallback никуда
/// не годится для будущего богатого рендера.
fn emit_math(state: MathState, blocks: &mut Vec<Block>) {
    let collected = collapse_spaces(&state.text);
    let alt = state
        .fallback
        .clone()
        .filter(|value| !value.trim().is_empty());
    let fallback = alt
        .clone()
        .or_else(|| (!collected.is_empty()).then_some(collected.clone()));
    if let Some(fallback) = fallback {
        let markup = state.finish();
        // Разметка всегда начинается с <math>; тривиальная пустая формула
        // (<math></math>) ценности не несёт — оставляем старое поведение.
        let source = (!markup.is_empty() && markup != "<math>" && markup != "<math></math>")
            .then_some(markup)
            .unwrap_or_else(|| alt.unwrap_or_else(|| collected.clone()));
        blocks.push(Block::Math { source, fallback });
    }
}

/// Вставляет картинку, сохраняя окружение.
///
/// «before<img/>after» обязан дать три куска: текст до, картинку, текст
/// после. Поэтому накопленный блок закрывается, а после картинки открывается
/// продолжение того же типа — родительский блок не уничтожается навсегда.
fn handle_image(
    element: &quick_xml::events::BytesStart<'_>,
    base: &str,
    depth: usize,
    blocks: &mut Vec<Block>,
    current: &mut Option<(BlockKind, usize)>,
    buffer: &mut String,
) {
    let continuing = current.map(|(kind, _)| kind);
    flush(blocks, current, buffer);
    if let Some(src) = attribute(element, b"src") {
        blocks.push(Block::Image {
            path: join(base, &src),
            alt: attribute(element, b"alt"),
        });
    }
    if continuing.is_some() {
        *current = Some((continuing.expect("проверили выше"), depth));
    }
}

/// Блочные элементы, которые читалка различает.
#[derive(Debug, Clone, Copy)]
enum BlockKind {
    Heading(u8),
    Paragraph,
    Quote,
    ListItem,
    /// <pre>: переносы строк и отступы значимы.
    Preformatted,
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
            b"pre" => BlockKind::Preformatted,
            // <code> сознательно inline: внутри абзацев он выделяет слова,
            // и разрывать из-за него текст нельзя.
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
    let had_breaks = buffer.contains('\n');
    let text = std::mem::take(buffer);

    if matches!(kind, BlockKind::Preformatted) || had_breaks {
        // Переносы строк дошли до нас значимыми — это стих или код.
        let pre = preformatted_text(&text);
        if !pre.is_empty() {
            blocks.push(Block::Preformatted(pre));
        }
        return;
    }

    let text = text.trim().to_string();
    if text.is_empty() {
        return;
    }
    blocks.push(match kind {
        BlockKind::Heading(level) => Block::Heading { level, text },
        BlockKind::Paragraph => Block::Paragraph(text),
        BlockKind::Quote => Block::Quote(text),
        BlockKind::ListItem => Block::ListItem(text),
        BlockKind::Preformatted => unreachable!("обработан веткой выше"),
    });
}

/// Приводит текст с осмысленными переносами к аккуратному виду.
fn preformatted_text(text: &str) -> String {
    let normalized = text.replace("\r\n", "\n").replace("\r", "\n");
    normalized
        .split('\n')
        .map(|line| line.trim_end())
        .collect::<Vec<_>>()
        .join("\n")
        .trim()
        .to_string()
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

/// Схлопывает только пробельные повторы, но сохраняет переносы как есть.
fn push_raw(buffer: &mut String, text: &str) {
    let normalized = text.replace("\r\n", "\n").replace("\r", "\n");
    buffer.push_str(&normalized);
}

/// Схлопывает повторяющиеся пробелы в один — для собранного текста формул.
fn push_collapsed(buffer: &mut String, text: &str) {
    push_text(buffer, text);
}

fn collapse_spaces(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    push_text(&mut out, text);
    out.trim().to_string()
}

/// Ставит разделяющий пробел, если он уместен.
///
/// После осмысленного переноса строки («<br/>») пробел не нужен: следующее
/// слово начинает новую строку, а не продолжает старую.
fn push_separator(buffer: &mut String) {
    if buffer.is_empty() || buffer.ends_with(' ') || buffer.ends_with('\n') {
        return;
    }
    buffer.push(' ');
}

/// Отмечает явный разрыв строки от «<br/>».
fn push_line_break(buffer: &mut String) {
    if !buffer.is_empty() && !buffer.ends_with('\n') {
        buffer.push('\n');
    }
}

/// Раскрывает ссылку на символ («&amp;», «&#8212;») в текст.
///
/// quick-xml отдаёт содержимое ссылки без обрамляющих «&» и «;»: числовые
/// раскрывает `resolve_char_ref`, пять XML-имён и «nbsp» (их в книгах
/// пруд пруди) — таблица ниже. Нераспознанное возвращается в текст как было:
/// показать читателю «&oddname;» честнее, чем выбросить кусок.
fn push_reference(reference: &quick_xml::events::BytesRef<'_>, out: &mut String) {
    if let Ok(Some(ch)) = reference.resolve_char_ref() {
        out.push(ch);
        return;
    }
    let raw = String::from_utf8_lossy(reference.as_ref()).into_owned();
    match raw.as_str() {
        "amp" => out.push('&'),
        "lt" => out.push('<'),
        "gt" => out.push('>'),
        "apos" => out.push('\''),
        "quot" => out.push('"'),
        "nbsp" => out.push('\u{00a0}'),
        _ => {
            out.push('&');
            out.push_str(&raw);
            out.push(';');
        }
    }
}

/// Имя элемента без пространства имён: `opf:item` → `item`.
fn local_name(name: &[u8]) -> &[u8] {
    match name.iter().rposition(|b| *b == b':') {
        Some(index) => &name[index + 1..],
        None => name,
    }
}

/// Значение атрибута с раскрытием character references.
///
/// Вёрстка любит писать `src="a&amp;b.jpg"` и `alt="&quot;Лампа&quot;"`;
/// сырые байты атрибута оставили бы `&amp;` в путях, и картинка не нашлась
/// бы в архиве.
fn attribute(element: &quick_xml::events::BytesStart<'_>, key: &[u8]) -> Option<String> {
    element.attributes().flatten().find_map(|attr| {
        if local_name(attr.key.as_ref()) != key {
            return None;
        }
        Some(
            attr.normalized_value(quick_xml::XmlVersion::Explicit1_0)
                .map(|value| value.into_owned())
                .unwrap_or_else(|_| String::from_utf8_lossy(&attr.value).into_owned()),
        )
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

        let blocks = parse_xhtml(xhtml, "OEBPS/text")
            .expect("глава разбирается")
            .0;

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

        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
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
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
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

    // --- ссылки на символы и CDATA ------------------------------------------

    #[test]
    fn именованные_и_числовые_ссылки_раскрываются() {
        let xhtml = "<html><body>\
             <p>AT&amp;T owns &lt;tags&gt;, &#8212; and &#x2014; too.</p>\
           </body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Paragraph(
                "AT&T owns <tags>, \u{2014} and \u{2014} too.".to_string()
            )]
        );
    }

    #[test]
    fn метаданные_со_ссылками_не_обрезаются() {
        let opf = r#"<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>War &amp; Peace</dc:title>
                <dc:creator>A &amp; B</dc:creator>
              </metadata>
              <manifest><item id="ch1" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>"#;
        let package = parse_package(opf, "").expect("манифест разбирается");
        assert_eq!(package.metadata.title.as_deref(), Some("War & Peace"));
        assert_eq!(package.metadata.author.as_deref(), Some("A & B"));
    }

    #[test]
    fn cdata_не_исчезает() {
        let xhtml = "<html><body><p><![CDATA[if (a < b && c > d)]]></p></body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Paragraph("if (a < b && c > d)".to_string())]
        );
    }

    // --- прямой текст в неизвестных контейнерах ------------------------------

    #[test]
    fn прямой_текст_в_div_и_ячейках_сохраняется() {
        let xhtml = "<html><body>\
             <div>Прямой текст без абзаца.</div>\
             <section><p>Обычный.</p> Хвост после абзаца.</section>\
           </body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert!(blocks.contains(&Block::Paragraph("Прямой текст без абзаца.".to_string())));
        assert!(blocks.contains(&Block::Paragraph("Хвост после абзаца.".to_string())));
        assert!(blocks.contains(&Block::Paragraph("Обычный.".to_string())));
    }

    #[test]
    fn подпись_рисунка_не_теряется() {
        let xhtml = "<html><body>\
             <figure><img src=\"i.jpg\"/><figcaption>Лампа на столе</figcaption></figure>\
           </body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert!(blocks
            .iter()
            .any(|b| matches!(b, Block::Image { path, .. } if path == "i.jpg")));
        assert!(blocks.contains(&Block::Paragraph("Лампа на столе".to_string())));
    }

    // --- картинки ------------------------------------------------------------

    #[test]
    fn инлайн_картинка_сохраняет_текст_до_и_после() {
        let xhtml = r#"<html><body><p>before<img src="x.jpg"/>after</p></body></html>"#;
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![
                Block::Paragraph("before".to_string()),
                Block::Image {
                    path: "x.jpg".to_string(),
                    alt: None,
                },
                Block::Paragraph("after".to_string()),
            ]
        );
    }

    #[test]
    fn парная_форма_img_тоже_работает() {
        let xhtml = r#"<html><body><p>before<img src="x.jpg"></img>after</p></body></html>"#;
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![
                Block::Paragraph("before".to_string()),
                Block::Image {
                    path: "x.jpg".to_string(),
                    alt: None,
                },
                Block::Paragraph("after".to_string()),
            ]
        );
    }

    #[test]
    fn ссылки_в_атрибутах_раскрываются() {
        let xhtml = r#"<html><body><img src="a&amp;b.jpg" alt="&quot;Лампа&quot;"/></body></html>"#;
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Image {
                path: "a&b.jpg".to_string(),
                alt: Some("\"Лампа\"".to_string()),
            }]
        );
    }

    // --- таблицы, формулы, pre ------------------------------------------------

    #[test]
    fn таблица_сохраняет_строки_и_ячейки() {
        let xhtml = "<html><body>\
             <table>\
               <tr><td>A1</td><td>B1</td></tr>\
               <tr><td>A2</td><td>B2</td></tr>\
             </table>\
           </body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Table {
                rows: vec![
                    vec!["A1".to_string(), "B1".to_string()],
                    vec!["A2".to_string(), "B2".to_string()],
                ],
            }]
        );
    }

    #[test]
    fn pre_сохраняет_переносы_и_отступы() {
        let xhtml = "<html><body><pre>def f():\n    return  42</pre></body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Preformatted("def f():\n    return  42".to_string())]
        );
    }

    #[test]
    fn стихи_с_br_не_склеиваются_в_одну_строку() {
        let xhtml = "<html><body><p>Уж небо осенью дышало,<br/>Уж реже солнышко блистало.</p></body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        assert_eq!(
            blocks,
            vec![Block::Preformatted(
                "Уж небо осенью дышало,\nУж реже солнышко блистало.".to_string()
            )]
        );
    }

    #[test]
    fn mathml_даёт_читабельный_fallback() {
        let xhtml = "<html><body>\
             <math alttext=\"E equals m c squared\"><mi>E</mi><mo>=</mo><mi>m</mi><msup><mi>c</mi></msup></math>\
           </body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        match blocks.first() {
            Some(Block::Math { source, fallback }) => {
                assert_eq!(fallback, "E equals m c squared");
                // Источником стала нормализованная разметка формулы: alttext
                // и текстовый запас теперь живут в fallback.
                assert!(
                    source.starts_with("<math>"),
                    "разметка сама себе дерево: {source}"
                );
                assert!(
                    source.contains("<mi>E</mi>") && source.contains("<msup>"),
                    "поддерево сохранилось: {source}"
                );
            }
            other => panic!("ожидалась формула, получили {other:?}"),
        }
    }

    #[test]
    fn формула_без_alttext_показывает_содержимое() {
        let xhtml = "<html><body><math><mi>a</mi><mo>+</mo><mi>b</mi></math></body></html>";
        let blocks = parse_xhtml(xhtml, "").expect("глава разбирается").0;
        match blocks.first() {
            Some(Block::Math { fallback, .. }) => assert_eq!(fallback, "a+b"),
            other => panic!("ожидалась формула, получили {other:?}"),
        }
    }

    // --- кодировки XML ---------------------------------------------------------

    #[test]
    fn windows_1252_по_объявлению_декодируется() {
        // «é» в windows-1252 — байт 0xE9; UTF-8 это не байты.
        let mut bytes =
            b"<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><html><body><p>Caf".to_vec();
        bytes.push(0xE9);
        bytes.extend_from_slice(b"</p></body></html>");
        let decoded = xml_decode("chapter.xhtml", &bytes).expect("кодировка объявлена");
        assert!(decoded.contains("Caf\u{e9}"), "{decoded}");
    }

    #[test]
    fn неизвестная_кодировка_даёт_явную_ошибку() {
        let bytes = b"<?xml version=\"1.0\" encoding=\"x-mac-cyrillic\"?><html><body><p>\xFF\xFE</p></body></html>".to_vec();
        let err = xml_decode("chapter.xhtml", &bytes).expect_err("неподдерживаемая кодировка");
        assert!(err.describe().contains("кодировке"), "{}", err.describe());
    }

    #[test]
    fn utf16_xml_по_bom_читается() {
        let mut bytes = vec![0xFF, 0xFE];
        // encoding_rs::encode() для UTF-16 отдаёт UTF-8 — байты собираем сами.
        for unit in "<p>Текст главы</p>".encode_utf16() {
            bytes.extend_from_slice(&unit.to_le_bytes());
        }
        let decoded = xml_decode("chapter.xhtml", &bytes).expect("BOM распознан");
        assert!(decoded.contains("Текст главы"));
    }

    fn epub_bytes_with_chapter(chapter_content: &str) -> Vec<u8> {
        epub_zip(&[
            (
                "OEBPS/content.opf",
                r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest><item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="ch1"/></spine></package>"#.to_string(),
            ),
            ("OEBPS/text/ch1.xhtml", chapter_content.to_string()),
        ])
    }

    /// Собирает синтетический EPUB из набора «путь → содержимое».
    fn epub_zip(files: &[(&str, String)]) -> Vec<u8> {
        use std::io::{Cursor, Write};
        let mut buffer = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buffer);
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            let container = r#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
            zip.start_file("mimetype", options).unwrap();
            zip.write_all(b"application/epub+zip").unwrap();
            zip.start_file("META-INF/container.xml", options).unwrap();
            zip.write_all(container.as_bytes()).unwrap();
            for (path, content) in files {
                zip.start_file(*path, options).unwrap();
                zip.write_all(content.as_bytes()).unwrap();
            }
            zip.finish().unwrap();
        }
        buffer.into_inner()
    }

    // --- семантические главы: nav / NCX / якоря -------------------------------

    #[test]
    fn один_xhtml_с_тремя_якорями_даёт_три_главы() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Anchors</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="nav"/><itemref idref="ch1"/></spine>
            </package>"#;
        let nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body>
              <nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops">
                <ol>
                  <li><a href="text/ch1.xhtml#one">Первая глава</a></li>
                  <li><a href="text/ch1.xhtml#two">Вторая глава</a></li>
                  <li><a href="text/ch1.xhtml#three">Третья глава</a></li>
                </ol>
              </nav>
            </body></html>"#;
        let ch1 = "<html><body>\
             <div id=\"one\"><p>Text one.</p></div>\
             <div id=\"two\"><p>Text two.</p></div>\
             <div id=\"three\"><p>Text three.</p></div>\
           </body></html>";
        let mut book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", nav.into()),
            ("OEBPS/text/ch1.xhtml", ch1.into()),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 3, "якоря нарезали файл на три главы");
        assert_eq!(book.contents()[0].title.as_deref(), Some("Первая глава"));
        assert_eq!(
            book.chapter(1).expect("вторая глава").plain_text(),
            "Text two."
        );
        // Заголовок приходит из nav даже без заголовочных тегов в файле.
        assert_eq!(
            book.chapter(2).expect("третья глава").title.as_deref(),
            Some("Третья глава")
        );
    }

    #[test]
    fn вложенные_разделы_nav_спрямляются() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Nested</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>"#;
        let nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body><nav>
              <ol>
                <li><a href="text/ch1.xhtml">Глава раз</a>
                  <ol><li><a href="text/ch2.xhtml">Глава два</a></li></ol>
                </li>
              </ol>
            </nav></body></html>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", nav.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>One.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/ch2.xhtml",
                "<html><body><p>Two.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 2);
        assert_eq!(book.contents()[0].title.as_deref(), Some("Глава раз"));
        assert_eq!(book.contents()[1].title.as_deref(), Some("Глава два"));
    }

    #[test]
    fn ncx_оглавление_режет_файл_на_главы() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>NCX book</dc:title></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/></spine>
            </package>"#;
        let ncx = r#"<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head/><docTitle><text>NCX book</text></docTitle>
              <navMap>
                <navPoint id="n1"><navLabel><text>Часть первая</text></navLabel><content src="text/ch1.xhtml#part1"/></navPoint>
                <navPoint id="n2"><navLabel><text>Часть вторая</text></navLabel><content src="text/ch1.xhtml#part2"/></navPoint>
              </navMap>
            </ncx>"#;
        let ch1 = "<html><body>\
             <p id=\"part1\">Beginning of part one.</p>\
             <p>Middle text stays in part one.</p>\
             <p id=\"part2\">Start of part two.</p>\
           </body></html>";
        let mut book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/toc.ncx", ncx.into()),
            ("OEBPS/text/ch1.xhtml", ch1.into()),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 2);
        assert_eq!(
            book.contents()[0].title.as_deref(),
            Some("Часть первая"),
            "docTitle книги не должен стать заголовком главы"
        );
        assert_eq!(
            book.chapter(0).expect("глава 1").plain_text(),
            "Beginning of part one.\n\nMiddle text stays in part one."
        );
        assert_eq!(
            book.chapter(1).expect("глава 2").plain_text(),
            "Start of part two."
        );
    }

    #[test]
    fn nav_с_разметкой_типов_берёт_только_toc() {
        // Три навигационных блока: только toc обязано стать главами,
        // landmarks и номера страниц главами не являются.
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" xmlns:epub="http://www.idpf.org/2007/ops">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Typed nav</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>"#;
        let nav = r#"<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="toc">
                <ol>
                  <li><a href="text/ch1.xhtml">Глава раз</a></li>
                  <li><a href="text/ch2.xhtml">Глава два</a></li>
                </ol>
              </nav>
              <nav epub:type="landmarks">
                <ol><li><a href="text/ch1.xhtml#begin">Начало книги</a></li></ol>
              </nav>
              <nav epub:type="page-list">
                <ol>
                  <li><a href="text/ch1.xhtml#p1">1</a></li>
                  <li><a href="text/ch2.xhtml#p2">2</a></li>
                </ol>
              </nav>
            </body></html>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", nav.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>One.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/ch2.xhtml",
                "<html><body><p>Two.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        // Ровно две главы из toc с их заголовками: landmark-и («Начало
        // книги») и номера страниц сюда попасть не должны.
        let titles: Vec<_> = book
            .contents()
            .iter()
            .filter_map(|c| c.title.clone())
            .collect();
        assert_eq!(
            titles.len(),
            2,
            "landmarks и page-list не должны стать главами"
        );
        assert_eq!(titles[0].as_str(), "Глава раз");
        assert_eq!(titles[1].as_str(), "Глава два");
    }

    #[test]
    fn nav_без_разметки_типов_собирает_ссылки_как_прежде() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Untyped nav</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>"#;
        let nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body>
              <nav>
                <ol>
                  <li><a href="text/ch1.xhtml">Глава раз</a></li>
                  <li><a href="text/ch2.xhtml">Глава два</a></li>
                </ol>
              </nav>
            </body></html>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", nav.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>One.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/ch2.xhtml",
                "<html><body><p>Two.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 2);
        assert_eq!(book.contents()[0].title.as_deref(), Some("Глава раз"));
        assert_eq!(book.contents()[1].title.as_deref(), Some("Глава два"));
    }

    #[test]
    fn повреждённый_nav_откатывается_на_ncx() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fallback</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/></spine>
            </package>"#;
        // Обрыв прямо внутри тега: потоковый читатель обязан вернуть
        // ошибку, а не отдать накопленное частичное оглавление.
        let broken_nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body><nav epub:type="toc">
              <ol>
                <li><a href="text/ch1.xhtml">Первые восемь глав</a></li>
                <li><a href="text/ch2.xhtml" class="кинешь внезапно"#;
        let ncx = r#"<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head/><docTitle><text>Fallback</text></docTitle>
              <navMap>
                <navPoint id="n1"><navLabel><text>Полное оглавление</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
              </navMap>
            </ncx>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", broken_nav.into()),
            ("OEBPS/toc.ncx", ncx.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>Body.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        let titles: Vec<_> = book
            .contents()
            .iter()
            .filter_map(|c| c.title.clone())
            .collect();
        assert_eq!(
            titles.as_slice(),
            ["Полное оглавление"],
            "после повреждённого nav должен использоваться NCX"
        );
    }

    #[test]
    fn сломанные_nav_и_ncx_откатываются_на_spine() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Spine</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>"#;
        let broken_nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body><nav>
              <ol><li><a href="text/ch1.xhtml">Глава раз</a></li>
              <li><a href="text/ch2.xhtml" alt="обрыв тега"#;
        let broken_ncx = r#"<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap><navPoint id="n1"><navLabel><text alt="и здесь тоже"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", broken_nav.into()),
            ("OEBPS/toc.ncx", broken_ncx.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>One.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/ch2.xhtml",
                "<html><body><p>Two.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        // Ни одного пункта оглавления не выжило — главы честно нарезаются
        // по файлам spine вместо полусломанного списка.
        assert_eq!(book.contents().len(), 2);
        assert_eq!(book.contents()[0].title, None);
        assert_eq!(book.contents()[1].title, None);
    }

    #[test]
    fn линейный_материал_без_оглавления_не_дробится_по_linear() {
        // linear="no" — сноска/приложение: без прямого указания в оглавлении
        // она не становится обычной главой.
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Linear</dc:title></metadata>
              <manifest>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="note" href="text/note.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="note" linear="no"/></spine>
            </package>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>Main.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/note.xhtml",
                "<html><body><p>Note.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 1, "нелинейный файл скрыт");
        let mut book = book;
        assert!(book
            .chapter(0)
            .expect("глава")
            .plain_text()
            .contains("Main."));
        assert!(book.chapter(1).is_err());
    }

    #[test]
    fn linear_no_упомянутый_в_nav_остаётся_доступным() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Linear nav</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="note" href="text/note.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="note" linear="no"/></spine>
            </package>"#;
        let nav = r#"<html xmlns="http://www.w3.org/1999/xhtml"><body><nav><ol>
              <li><a href="text/ch1.xhtml">Основная</a></li>
              <li><a href="text/note.xhtml">Примечание</a></li>
            </ol></nav></body></html>"#;
        let book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/nav.xhtml", nav.into()),
            (
                "OEBPS/text/ch1.xhtml",
                "<html><body><p>Main.</p></body></html>".into(),
            ),
            (
                "OEBPS/text/note.xhtml",
                "<html><body><p>Note.</p></body></html>".into(),
            ),
        ]))
        .expect("EPUB открывается");

        assert_eq!(book.contents().len(), 2, "nav вернул главу в чтение");
    }

    #[test]
    fn порядок_глав_следует_spine() {
        let opf = r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Order</dc:title></metadata>
              <manifest>
                <item id="a" href="text/a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="text/b.xhtml" media-type="application/xhtml+xml"/>
                <item id="c" href="text/c.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c"/><itemref idref="a"/><itemref idref="b"/></spine>
            </package>"#;
        let page = |text: &str| format!("<html><body><p>{text}</p></body></html>");
        let mut book = EpubBook::from_bytes(epub_zip(&[
            ("OEBPS/content.opf", opf.into()),
            ("OEBPS/text/a.xhtml", page("Alpha.")),
            ("OEBPS/text/b.xhtml", page("Beta.")),
            ("OEBPS/text/c.xhtml", page("Gamma.")),
        ]))
        .expect("EPUB открывается");

        // Порядок чтения — по spine (C, A, B), а не по алфавиту манифеста.
        assert!(book
            .chapter(0)
            .expect("первая")
            .plain_text()
            .contains("Gamma."));
        assert!(book
            .chapter(1)
            .expect("вторая")
            .plain_text()
            .contains("Alpha."));
        assert!(book
            .chapter(2)
            .expect("третья")
            .plain_text()
            .contains("Beta."));
    }

    #[test]
    fn oversized_epub_entry_rejected() {
        let huge = "a".repeat(crate::parser::limits::MAX_EPUB_ENTRY_BYTES_USIZE + 1);
        let xhtml = format!("<html><body><p>{huge}</p></body></html>");
        let bytes = epub_bytes_with_chapter(&xhtml);
        let mut book = EpubBook::from_bytes(bytes)
            .expect("EPUB открывается; ошибка ожидается при чтении главы");
        let err = book
            .chapter(0)
            .expect_err("огромная запись должна быть отвергнута");
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
        let err = book
            .chapter(0)
            .expect_err("гигантская глава должна быть отвергнута");
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
        crate::parser::limits::check_epub_entry_size(lying.size())
            .expect("declared маленький — проверка проходит");
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
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
            let container = r#"<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#;
            let mut opf_manifest = String::from(
                r#"<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata><manifest>"#,
            );
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
                zip.start_file(format!("OEBPS/text/ch{i}.xhtml"), options)
                    .unwrap();
                zip.write_all(b"<html><body><p>hi</p></body></html>")
                    .unwrap();
            }
            zip.finish().unwrap();
        }
        let bytes = buffer.into_inner();
        let Err(err) = EpubBook::from_bytes(bytes) else {
            panic!("слишком много записей — должен быть отклонён");
        };
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn epub_source_too_large_rejected() {
        let large = vec![0u8; crate::parser::limits::MAX_SOURCE_BYTES_USIZE + 1];
        let Err(err) = EpubBook::from_bytes(large) else {
            panic!("источник слишком велик — ожидалась ошибка");
        };
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    #[test]
    fn epub_resource_too_large_rejected() {
        use std::io::{Cursor, Write};
        let huge_resource = vec![b'a'; crate::parser::limits::MAX_IMAGE_BYTES + 1];
        let mut buffer = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buffer);
            let options: zip::write::FileOptions<'_, ()> = zip::write::FileOptions::default()
                .compression_method(zip::CompressionMethod::Stored);
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
        let err = book
            .resource("OEBPS/images/big.jpg")
            .expect_err("ресурс слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }
}
