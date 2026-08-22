//! Сквозной путь ядра: файл книги → глава → предложения → карточка слова.
//!
//! Юнит-тесты проверяют разметку по кускам, но настоящий EPUB — это zip с
//! путями, пространствами имён и относительными ссылками, и ломается он
//! обычно именно на стыках. Поэтому здесь книга собирается целиком и
//! проходится ровно так, как её пройдёт читалка.

use std::fs::File;
use std::io::Write;
use std::path::PathBuf;

use wolfy_core::lexicon::{analyze, FormKind, Lexicon};
use wolfy_core::parser::{open, Block};
use wolfy_core::tokenizer::{split, tokenize, TokenKind};

const CONTAINER: &str = r#"<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"#;

const OPF: &str = r#"<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Old Library</dc:title>
    <dc:creator>Evelyn Hart</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="cover" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>"#;

const CHAPTER_ONE: &str = r#"<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>Chapter III</title></head>
  <body>
    <h2>The Old Library</h2>
    <p>The library smelled of dust, leather and old paper. Evelyn
       pushed the heavy door and stepped into the quiet hall.</p>
    <p>She <em>had been reading</em> since dawn, and the margins were
       filled with her neat handwriting.</p>
    <img src="../images/lamp.jpg" alt="A green lamp"/>
  </body>
</html>"#;

const CHAPTER_TWO: &str = r#"<?xml version="1.0"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <body>
    <h2>The Catalogue</h2>
    <p>Mr. Ashton counted the children twice.</p>
  </body>
</html>"#;

/// Собирает настоящий EPUB во временном файле.
fn собрать_epub(name: &str) -> PathBuf {
    let path = std::env::temp_dir().join(name);
    let file = File::create(&path).expect("временный файл");
    let mut zip = zip::ZipWriter::new(file);

    // Без сжатия: тесту важна структура архива, а не размер.
    let options: zip::write::FileOptions<'_, ()> =
        zip::write::FileOptions::default().compression_method(zip::CompressionMethod::Stored);

    let files = [
        ("mimetype", "application/epub+zip"),
        ("META-INF/container.xml", CONTAINER),
        ("OEBPS/content.opf", OPF),
        ("OEBPS/text/ch1.xhtml", CHAPTER_ONE),
        ("OEBPS/text/ch2.xhtml", CHAPTER_TWO),
        ("OEBPS/images/cover.jpg", "not-really-a-jpeg"),
    ];
    for (name, content) in files {
        zip.start_file(name, options).expect("запись в архив");
        zip.write_all(content.as_bytes()).expect("запись файла");
    }
    zip.finish().expect("архив закрывается");
    path
}

#[test]
fn epub_открывается_и_отдаёт_метаданные_с_оглавлением() {
    let path = собрать_epub("wolfy_integration.epub");
    let book = open(&path).expect("книга открывается");

    assert_eq!(book.metadata().title.as_deref(), Some("The Old Library"));
    assert_eq!(book.metadata().author.as_deref(), Some("Evelyn Hart"));
    assert_eq!(book.metadata().language.as_deref(), Some("en"));
    // Обложка найдена по properties и путь к ней разрешён относительно
    // манифеста, а не корня архива.
    assert_eq!(
        book.metadata().cover.as_deref(),
        Some("OEBPS/images/cover.jpg")
    );
    assert_eq!(book.contents().len(), 2);
}

#[test]
fn глава_читается_по_требованию_а_не_вся_книга_сразу() {
    let path = собрать_epub("wolfy_integration_lazy.epub");
    let mut book = open(&path).expect("книга открывается");

    let chapter = book.chapter(0).expect("первая глава");
    assert_eq!(chapter.title.as_deref(), Some("The Old Library"));

    // Заголовок, два абзаца и картинка — вложенный <em> абзац не разорвал.
    assert_eq!(chapter.blocks.len(), 4);
    assert!(matches!(chapter.blocks[1], Block::Paragraph(_)));
    assert!(chapter.plain_text().contains("had been reading since dawn"));

    // Вторая глава доступна тем же способом и читается независимо.
    let second = book.chapter(1).expect("вторая глава");
    assert_eq!(second.title.as_deref(), Some("The Catalogue"));
}

#[test]
fn относительный_путь_к_иллюстрации_разрешается_от_главы() {
    let path = собрать_epub("wolfy_integration_image.epub");
    let mut book = open(&path).expect("книга открывается");
    let chapter = book.chapter(0).expect("первая глава");

    let Some(Block::Image { path, alt }) = chapter
        .blocks
        .iter()
        .find(|b| matches!(b, Block::Image { .. }))
    else {
        panic!("иллюстрация не найдена");
    };
    // «../images/lamp.jpg» из OEBPS/text — это OEBPS/images/lamp.jpg.
    assert_eq!(path, "OEBPS/images/lamp.jpg");
    assert_eq!(alt.as_deref(), Some("A green lamp"));
}

#[test]
fn путь_от_файла_книги_до_карточки_слова_проходится_целиком() {
    // Ровно то, что делает читалка: открыть книгу, взять главу, разбить на
    // предложения, ткнуть в слово и получить разбор.
    let path = собрать_epub("wolfy_integration_card.epub");
    let mut book = open(&path).expect("книга открывается");

    let text = book.chapter(0).expect("глава").plain_text();
    let tokens = tokenize(&text);
    let sentences = split(&tokens);

    // Сокращение в тексте есть только во второй главе, здесь границы простые.
    assert!(
        sentences.len() >= 3,
        "предложений мало: {}",
        sentences.len()
    );

    // Находим слово, по которому тапнет читатель.
    let token = tokens
        .iter()
        .find(|t| t.kind == TokenKind::Word && t.text == "reading")
        .expect("«reading» есть в главе");

    // Предложение вокруг слова — это контекст для перевода.
    let context = sentences
        .iter()
        .find(|s| s.range.contains(&token.range.start))
        .expect("слово попало в предложение");
    assert!(context.text.contains("She had been reading since dawn"));

    let analysis = analyze(Lexicon::embedded(), &token.text);
    assert_eq!(analysis.lemma, "read");
    assert_eq!(analysis.form, FormKind::Regular);
    assert!(
        analysis.facts.iter().any(|f| f.value.contains("-ing")),
        "разбор не объяснил окончание: {:?}",
        analysis.facts
    );
}

#[test]
fn сокращение_в_главе_не_режет_предложение() {
    let path = собрать_epub("wolfy_integration_abbr.epub");
    let mut book = open(&path).expect("книга открывается");

    let text = book.chapter(1).expect("вторая глава").plain_text();
    let sentences = split(&tokenize(&text));

    // «Mr. Ashton counted...» — одно предложение, а не два.
    assert!(
        sentences
            .iter()
            .any(|s| s.text == "Mr. Ashton counted the children twice."),
        "границы предложений разъехались: {:?}",
        sentences.iter().map(|s| &s.text).collect::<Vec<_>>()
    );
}

#[test]
fn битый_архив_возвращает_ошибку_а_не_панику() {
    let path = std::env::temp_dir().join("wolfy_integration_broken.epub");
    File::create(&path)
        .expect("временный файл")
        .write_all(b"this is not a zip archive at all")
        .expect("запись");

    let Err(err) = open(&path) else {
        panic!("битый файл не должен открываться");
    };
    assert!(
        err.describe().contains("архив"),
        "непонятное сообщение: {}",
        err.describe()
    );
}
