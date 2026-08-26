//! Простой текст.
//!
//! У TXT нет ни разметки, ни оглавления, ни метаданных — есть только строки.
//! Поэтому вся работа парсера в том, чтобы угадать по ним структуру: где
//! кончается абзац, что похоже на заголовок главы и в какой кодировке всё это
//! записано.

use std::fs;
use std::path::Path;

use crate::error::Result;
use crate::parser::limits;
use crate::parser::{Block, Book, Chapter, ChapterInfo, Metadata};

/// Книга из простого текста.
#[derive(Debug)]
pub struct TxtBook {
    metadata: Metadata,
    contents: Vec<ChapterInfo>,
    /// Главы уже разобраны: TXT редко бывает больше нескольких мегабайт, и
    /// держать его в памяти дешевле, чем перечитывать файл ради каждой главы.
    chapters: Vec<Chapter>,
}

impl TxtBook {
    pub fn open(path: &Path) -> Result<Self> {
        let metadata = fs::metadata(path)?;
        limits::check_txt_size(metadata.len())?;
        let bytes = fs::read(path)?;
        // Повторная проверка на случай гонки между metadata и read.
        limits::check_txt_size(bytes.len() as u64)?;
        let title = path
            .file_stem()
            .and_then(|s| s.to_str())
            .map(str::to_string);
        Ok(TxtBook::from_bytes(&bytes, title)?)
    }

    /// Текст, лежащий в памяти: так книга приходит из браузера.
    ///
    /// Название здесь параметром, а не из имени файла: имени у байтов нет, а
    /// у того, кто их принёс, оно было.
    pub fn from_bytes(bytes: &[u8], title: Option<String>) -> Result<Self> {
        limits::check_txt_size(bytes.len() as u64)?;
        let text = decode(bytes);
        limits::check_total_text_len(text.len())?;

        let chapters = split_chapters(&text);
        // Проверяем итоговый объём после разбивки на главы — защита от
        // гигантской книги, которая после нормализации всё равно огромна.
        let total: usize = chapters.iter().map(|c| c.plain_text().len()).sum();
        limits::check_total_text_len(total)?;
        for chapter in &chapters {
            limits::check_chapter_text_len(chapter.plain_text().len())?;
        }
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
/// Порядок строгий: сначала BOM'ы (они однозначны), затем проверка UTF-8 как
/// самого частого варианта, и только потом легаси-кодировки. Считать любой
/// non-UTF8 файл windows-1251 нельзя: cp866 и koi8-r до сих пор живут в
/// старых архивах, и их перекодированный мусор выглядит как «текст», пока
/// читатель не откроет первую страницу.
fn decode(bytes: &[u8]) -> String {
    // BOM снимается явно: иначе первым символом книги станет невидимый
    // «\u{feff}», и первое слово перестанет находиться в словаре.
    if let Some(rest) = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        return match std::str::from_utf8(rest) {
            Ok(text) => text.to_string(),
            Err(_) => legacy_decode(rest),
        };
    }
    // UTF-16LE: «FF FE». UTF-32LE начинается с «FF FE 00 00» и тут не
    // поддерживается сознательно — перепутать его с LE-текстом опасно.
    if bytes.starts_with(&[0xFF, 0xFE]) {
        return encoding_rs::UTF_16LE.decode(&bytes[2..]).0.into_owned();
    }
    if bytes.starts_with(&[0xFE, 0xFF]) {
        return encoding_rs::UTF_16BE.decode(&bytes[2..]).0.into_owned();
    }

    match std::str::from_utf8(bytes) {
        Ok(text) => text.to_string(),
        Err(_) => legacy_decode(bytes),
    }
}

/// Легаси-декодирование: перебираем три исторические кириллические кодировки
/// и оставляем ту, чей результат похож на живой русский текст.
///
/// Признак похожести — доля гласных среди кириллических букв: у осмысленного
/// текста она стабильно держится около 40%, а у перекодированного мусора
/// проваливается, потому что буквы выпадают случайно.
fn legacy_decode(bytes: &[u8]) -> String {
    const CANDIDATES: [&encoding_rs::Encoding; 3] = [
        encoding_rs::WINDOWS_1251,
        encoding_rs::KOI8_R,
        encoding_rs::IBM866,
    ];

    let mut best: Option<(String, i64)> = None;
    for encoding in CANDIDATES {
        let (text, _, _) = encoding.decode(bytes);
        let text = text.into_owned();
        let deviation = match russian_deviation(&text) {
            Some(deviation) => deviation,
            // Слишком мало кириллицы для суждения — кандидат не выигрывает.
            None => continue,
        };
        if best
            .as_ref()
            .is_none_or(|(_, best_deviation)| deviation < *best_deviation)
        {
            best = Some((text, deviation));
        }
    }

    // Ни один кандидат не набрал достаточно кириллицы (короткий текст или
    // вообще не русский файл) — тогда windows-1251 честный выбор по
    // умолчанию: она чаще остальных встречается в русских книгах.
    best.map(|(text, _)| text)
        .unwrap_or_else(|| encoding_rs::WINDOWS_1251.decode(bytes).0.into_owned())
}

/// Расхождение доли гласных с нормой живого русского текста (0 — идеально).
fn russian_deviation(text: &str) -> Option<i64> {
    let mut vowels = 0usize;
    let mut total = 0usize;
    for ch in text.chars() {
        if !matches!(ch, 'а'..='я' | 'А'..='Я' | 'ё' | 'Ё') {
            continue;
        }
        total += 1;
        // Регистр приходится сводить по-настоящему: to_ascii_lowercase на
        // кириллице ничего не делает, и ВЫПИСАННЫЕ КАПСОМ заголовки остались
        // бы без гласных, искажая всю метрику.
        let lower = ch.to_lowercase().next().unwrap_or(ch);
        if matches!(
            lower,
            'а' | 'е' | 'и' | 'о' | 'у' | 'ы' | 'э' | 'ю' | 'я' | 'ё'
        ) {
            vowels += 1;
        }
    }
    // Меньше дюжины букв — статистика пустая, решать рано.
    if total < 12 {
        return None;
    }
    let share = (vowels * 100 / total) as i64;
    Some((share - 42).abs())
}

/// Делит текст на главы и блоки.
///
/// Абзац — это кусок между пустыми строками. Разбор идёт по состоянию строк,
/// а не по literal-разбиванию: концы строки бывают LF, CRLF и одиночный CR,
/// и файл с «\r\n\r\n» обязан делиться так же, как файл с «\n\n». Пустой
/// строкой считается любая, где только пробелы, а несколько пустых подряд
/// не порождают пустых блоков.
fn split_chapters(text: &str) -> Vec<Chapter> {
    let mut chapters: Vec<Chapter> = Vec::new();
    let mut current = Chapter::default();
    let mut paragraph = String::new();
    let mut paragraph_lines = 0usize;
    let mut saw_blank = false;

    for raw in lines_any(text) {
        if raw.trim().is_empty() {
            flush_paragraph(
                &mut paragraph,
                &mut paragraph_lines,
                &mut current,
                &mut chapters,
            );
            saw_blank = true;
            continue;
        }

        // Книга без единой пустой строки («стена текста») всё равно не должна
        // превращаться в один гигантский абзац. Если пустых разделителей не
        // было вовсе, режем по концам предложений, когда накопленное уже
        // велико; обычные книги с пустыми строками этот путь не трогает.
        if !saw_blank
            && paragraph.chars().count() > WALL_PARAGRAPH_LIMIT
            && paragraph.ends_with(['.', '!', '?', '»', '"'])
        {
            flush_paragraph(
                &mut paragraph,
                &mut paragraph_lines,
                &mut current,
                &mut chapters,
            );
        }

        if !paragraph.is_empty() {
            paragraph.push(' ');
        }
        paragraph.push_str(raw.trim());
        paragraph_lines += 1;
    }
    flush_paragraph(
        &mut paragraph,
        &mut paragraph_lines,
        &mut current,
        &mut chapters,
    );

    if !current.blocks.is_empty() {
        chapters.push(current);
    }

    // Книга без единого заголовка — это одна глава, а не ноль.
    if chapters.is_empty() {
        chapters.push(Chapter::default());
    }
    chapters
}

/// Предел одного абзаца для книг вообще без пустых строк.
const WALL_PARAGRAPH_LIMIT: usize = 2000;

/// Закрывает накопленный абзац и решает, чем он был.
///
/// Заголовок и разделитель опознаются только у короткого блока из одной-двух
/// строк: многострочный капслок — это вырванный кусок набранного капслоком
/// текста, а не глава, и дробить по нему книгу нельзя. Исключение — явное
/// ключевое слово («CHAPTER», «Глава»): такие строки не теряются даже в
/// длинных блоках.
fn flush_paragraph(
    paragraph: &mut String,
    paragraph_lines: &mut usize,
    current: &mut Chapter,
    chapters: &mut Vec<Chapter>,
) {
    let text = std::mem::take(paragraph);
    let lines = std::mem::take(paragraph_lines);
    if text.is_empty() {
        return;
    }

    if is_divider(&text) {
        current.blocks.push(Block::Divider);
        return;
    }

    if keyword_heading(&text) || (lines <= 2 && caps_heading(&text)) {
        // Заголовок начинает новую главу — но только если в предыдущей уже
        // что-то было. Иначе «CHAPTER I» сразу после названия книги породило
        // бы пустую главу.
        if !current.blocks.is_empty() {
            chapters.push(std::mem::take(current));
        }
        current.title = Some(text.clone());
        current.blocks.push(Block::Heading { level: 2, text });
        return;
    }

    current.blocks.push(Block::Paragraph(text));
}

/// Строки текста при любом варианте конца строки: LF, CRLF или одиночный CR.
fn lines_any(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text;
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        let end = rest.find(['\r', '\n']).unwrap_or(rest.len());
        let line = &rest[..end];
        rest = &rest[end..];
        // Снимаем сам конец строки, учитывая пару «\r\n».
        if let Some(after) = rest.strip_prefix('\r') {
            rest = after.strip_prefix('\n').unwrap_or(after);
        } else if let Some(after) = rest.strip_prefix('\n') {
            rest = after;
        }
        Some(line)
    })
}

/// Похож ли блок на заголовок главы: капслок или ключевое слово.
///
/// Живёт для тестов: разбор пользуется частями напрямую, чтобы учесть число
/// строк абзаца.
#[cfg(test)]
fn is_heading(text: &str) -> bool {
    keyword_heading(text) || caps_heading(text)
}

/// Капслочный заголовок: «CHAPTER III», «THE OLD LIBRARY».
fn caps_heading(text: &str) -> bool {
    let length = text.chars().count();
    if length == 0 || length > 60 {
        return false;
    }
    // Точка в конце — признак обычного предложения, а не заголовка.
    if text.ends_with(['.', ',', ';', ':', '!', '?']) {
        return false;
    }

    let letters: String = text.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.is_empty() {
        return false;
    }

    // Набрано капслоком и при этом короткое: длинные капслочные абзацы —
    // это текст, набранный заглавными, а не заголовок.
    letters.chars().all(char::is_uppercase) && text.split_whitespace().count() <= 6
}

/// Заголовок по ключевому слову: «Глава третья», «PART TWO», «Книга вторая».
fn keyword_heading(text: &str) -> bool {
    if text.chars().count() > 80 {
        return false;
    }
    if text.ends_with(['.', ',', ';', ':', '!', '?']) {
        return false;
    }

    let mut words = text.split_whitespace();
    let first = words
        .next()
        .unwrap_or_default()
        .trim_end_matches(['.', ':', ','])
        .to_lowercase();
    // Для общих слов вроде «часть» рядом обязано стоять число: иначе «Часть
    // разговора» посреди текста стала бы новой главой.
    let second_is_number = words
        .next()
        .map(|word| number_like(word.trim_end_matches(['.', ',', ':'])))
        .unwrap_or(false);

    match first.as_str() {
        "глава" | "chapter" => true,
        "часть" | "part" | "книга" | "book" => second_is_number,
        _ => false,
    }
}

/// Похож ли слово на номер главы: арабская цифра, римская цифра или
/// числительное прописью.
fn number_like(word: &str) -> bool {
    let lower = word.to_lowercase();
    if lower.is_empty() {
        return false;
    }
    if lower.chars().all(|c| c.is_ascii_digit()) {
        return true;
    }
    if lower
        .chars()
        .all(|c| matches!(c, 'i' | 'v' | 'x' | 'l' | 'c' | 'd' | 'm'))
    {
        return true;
    }
    matches!(
        lower.as_str(),
        "один"
            | "одна"
            | "два"
            | "две"
            | "три"
            | "четыре"
            | "пять"
            | "шесть"
            | "семь"
            | "восемь"
            | "девять"
            | "десять"
            | "первая"
            | "вторая"
            | "третья"
            | "четвертая"
            | "пятая"
            | "шестая"
            | "седьмая"
            | "восьмая"
            | "девятая"
            | "десятая"
            | "первый"
            | "второй"
            | "третий"
            | "четвертый"
            | "пятый"
            | "1-я"
            | "2-я"
            | "1-й"
            | "2-й"
            | "one"
            | "two"
            | "three"
            | "four"
            | "five"
            | "six"
            | "seven"
            | "eight"
            | "nine"
            | "ten"
            | "eleven"
            | "twelve"
            | "first"
            | "second"
            | "third"
            | "fourth"
            | "fifth"
            | "sixth"
            | "seventh"
            | "eighth"
            | "ninth"
            | "tenth"
    )
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

    #[test]
    fn oversized_txt_из_памяти_отвергается() {
        let huge = vec![b'a'; crate::parser::limits::MAX_TXT_BYTES_USIZE + 1];
        let err = TxtBook::from_bytes(&huge, None).expect_err("огромный TXT должен быть отвергнут");
        assert!(
            err.describe().contains("слишком велика"),
            "ожидалось 'слишком велика', получили: {}",
            err.describe()
        );
    }

    #[test]
    fn oversized_txt_файл_отвергается_без_unbounded_read() {
        // Создаём разреженный файл нужного размера без записи 10 MiB нулей
        // напрямую: set_len эффективно, не тратя память.
        let path = std::env::temp_dir().join("wolfy_txt_oversized.txt");
        let file = fs::File::create(&path).expect("файл");
        file.set_len(crate::parser::limits::MAX_TXT_BYTES + 1)
            .expect("расширить файл");
        drop(file);
        let err = TxtBook::open(&path).expect_err("файл слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn txt_ровно_на_границе_лимита_принимается() {
        // Чуть меньше лимита главы (5 MiB) — должна открыться. TXT лимит шире,
        // но глава ограничивает одиночный TXT без разбивки.
        let size = crate::parser::limits::MAX_CHAPTER_TEXT_BYTES - 1024;
        let data = vec![b'a'; size];
        let book = TxtBook::from_bytes(&data, None).expect("граничный размер должен приниматься");
        assert_eq!(book.contents().len(), 1);
    }

    #[test]
    fn txt_total_text_limit_rejected() {
        // Генерируем текст, который после декодирования превысит MAX_TOTAL_TEXT_BYTES.
        // Чтобы не аллоцировать 20 MiB одним куском, склеим 2 куска.
        let big = "a".repeat(crate::parser::limits::MAX_TOTAL_TEXT_BYTES + 1);
        let err = TxtBook::from_bytes(big.as_bytes(), None).expect_err("общий текст слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }

    // --- концы строк -------------------------------------------------------

    #[test]
    fn crlf_абзацы_разделяются_как_lf() {
        let path = книга(
            "wolfy_txt_crlf_paragraphs.txt",
            b"The library smelled of dust.\r\n\r\nEvelyn pushed the door.\r\n",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(
            book.chapter(0).expect("глава").blocks,
            vec![
                Block::Paragraph("The library smelled of dust.".to_string()),
                Block::Paragraph("Evelyn pushed the door.".to_string()),
            ]
        );
    }

    #[test]
    fn одиночный_cr_тоже_конец_строки() {
        let path = книга(
            "wolfy_txt_cr.txt",
            b"First paragraph.\r\rSecond paragraph.\r",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(
            book.chapter(0).expect("глава").blocks,
            vec![
                Block::Paragraph("First paragraph.".to_string()),
                Block::Paragraph("Second paragraph.".to_string()),
            ]
        );
    }

    #[test]
    fn смешанные_концы_строк_читаются_целиком() {
        let path = книга(
            "wolfy_txt_mixed_endings.txt",
            b"One.\r\nTwo.\nThree.\r\rFour.\r\n",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let chapter = book.chapter(0).expect("глава");
        let text = chapter.plain_text();
        for word in ["One.", "Two.", "Three.", "Four."] {
            assert!(text.contains(word), "потерян кусок «{word}»");
        }
    }

    #[test]
    fn сто_абзацев_crlf_сохраняются_полностью() {
        let mut content = String::new();
        for n in 1..=100 {
            content.push_str(&format!("Абзац номер {n} про старую библиотеку.\r\n\r\n"));
        }
        let path = книга("wolfy_txt_100_crlf.txt", content.as_bytes());
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let blocks = book.chapter(0).expect("глава").blocks;
        assert_eq!(blocks.len(), 100, "каждый абзац — отдельный блок");
        // Текст каждого абзаца сохранён полностью.
        assert!(blocks
            .iter()
            .any(|b| b.text() == Some("Абзац номер 100 про старую библиотеку.")));
        assert!(blocks
            .iter()
            .any(|b| b.text() == Some("Абзац номер 57 про старую библиотеку.")));
    }

    #[test]
    fn crlf_главы_делятся_по_ключевому_слову() {
        let path = книга(
            "wolfy_txt_crlf_chapters.txt",
            "CHAPTER I\r\n\r\nThe door opened.\r\n\r\nCHAPTER II\r\n\r\nEvelyn stepped in.\r\n"
                .as_bytes(),
        );
        let book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(book.contents().len(), 2);
        assert_eq!(book.contents()[0].title.as_deref(), Some("CHAPTER I"));
        assert_eq!(book.contents()[1].title.as_deref(), Some("CHAPTER II"));
    }

    #[test]
    fn пустые_строки_из_пробелов_тоже_разделяют() {
        let path = книга(
            "wolfy_txt_blank_spaces.txt",
            b"First.\n   \n\t\nSecond.\n\n\n\nThird.\n",
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let blocks = book.chapter(0).expect("глава").blocks;
        assert_eq!(
            blocks,
            vec![
                Block::Paragraph("First.".to_string()),
                Block::Paragraph("Second.".to_string()),
                Block::Paragraph("Third.".to_string()),
            ],
            "пробельные строки разделяют, лишние пустые не создают блоков"
        );
    }

    // --- кодировки ----------------------------------------------------------

    #[test]
    fn utf16le_bom_читается() {
        // encoding_rs::encode() для UTF-16 отдаёт UTF-8, поэтому байты
        // фикстуры собираются вручную.
        let mut bytes = vec![0xFF, 0xFE];
        for unit in "Глава первая".encode_utf16() {
            bytes.extend_from_slice(&unit.to_le_bytes());
        }
        let path = книга("wolfy_txt_utf16le.txt", &bytes);
        let book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(book.contents()[0].title.as_deref(), Some("Глава первая"));
    }

    #[test]
    fn utf16be_bom_читается() {
        let mut bytes = vec![0xFE, 0xFF];
        for unit in "Глава вторая".encode_utf16() {
            bytes.extend_from_slice(&unit.to_be_bytes());
        }
        let path = книга("wolfy_txt_utf16be.txt", &bytes);
        let book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(book.contents()[0].title.as_deref(), Some("Глава вторая"));
    }

    #[test]
    fn koi8_r_опознаётся_среди_легаси_кодировок() {
        // «Старая библиотека пахла пылью и кожаными переплётами» в koi8-r.
        let (bytes, _, _) =
            encoding_rs::KOI8_R.encode("Старая библиотека пахла пылью и кожаными переплётами.");
        let path = книга("wolfy_txt_koi8.txt", bytes.as_ref());
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let text = book.chapter(0).expect("глава").plain_text();
        assert!(
            text.contains("переплётами"),
            "koi8-r должен декодироваться в осмысленный текст: {text}"
        );
    }

    #[test]
    fn cp866_опознаётся_среди_легаси_кодировок() {
        let (bytes, _, _) =
            encoding_rs::IBM866.encode("Полка за полкой стояли под потолком в тишине.");
        let path = книга("wolfy_txt_cp866.txt", bytes.as_ref());
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let text = book.chapter(0).expect("глава").plain_text();
        assert!(
            text.contains("потолком"),
            "cp866 должен декодироваться в осмысленный текст: {text}"
        );
    }

    #[test]
    fn капс_не_сводит_метрику_гласных_к_нулю() {
        // to_ascii_lowercase на кириллице ничего не делает: раньше
        // ВЫПИСАННЫЕ КАПСОМ заголовки считались полностью безгласными,
        // и расхождение с нормой становилось шумом.
        let lower = "эхо утра над тихою рекой плыло медленно и широко";
        let upper = "ЭХО УТРА НАД ТИХОЮ РЕКОЙ ПЛЫЛО МЕДЛЕННО И ШИРОКО";
        assert_eq!(
            russian_deviation(lower),
            russian_deviation(upper),
            "регистр не должен менять метрику"
        );
    }

    #[test]
    fn текст_капсом_не_выбирает_неверную_легаси_кодировку() {
        // Настоящий windows-1251 файл, набранный заглавными. При сломанной
        // метрике таким текстам чаще отдавали koi8-r/кp866.
        let (bytes, _, _) =
            encoding_rs::WINDOWS_1251.encode("ПРИВЕТ, ЭХО ЮГА! ЖУРАВЛИ ЛЕТЕЛИ В ТЁПЛЫЕ КРАЯ.");
        let path = книга("wolfy_txt_caps1251.txt", bytes.as_ref());
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let text = book.chapter(0).expect("глава").plain_text();
        assert!(
            text.contains("ЖУРАВЛИ"),
            "windows-1251 должен остаться сам собой: {text}"
        );
    }

    #[test]
    fn английский_non_utf8_не_ломается_на_легаси_переборе() {
        // cp1252-байты без кириллицы: перебор не должен выбрать мусор.
        let path = книга(
            "wolfy_txt_cp1252.txt",
            &[b'T', b'h', 0xE9, b' ', b'd', b'o', b'o', b'r', b'.', 0x0A],
        );
        let mut book = TxtBook::open(&path).expect("книга открывается");
        assert!(book
            .chapter(0)
            .expect("глава")
            .plain_text()
            .starts_with("Th"));
    }

    // --- заголовки -----------------------------------------------------------

    #[test]
    fn многострочный_капслок_не_становится_главой() {
        // Длинный кусок, набранный капслоком, — это текст, а не заголовок.
        let path = книга(
            "wolfy_txt_caps_wall.txt",
            "THE LIBRARY WAS CLOSED\nFOR THE WHOLE SUMMER\nAND NOBODY CAME HERE\n\nОбычный текст дальше.\n".as_bytes(),
        );
        let book = TxtBook::open(&path).expect("книга открывается");

        assert_eq!(book.contents().len(), 1, "капслочная стена не дробит книгу");
    }

    #[test]
    fn часть_без_номера_не_главa_а_часть_с_номером_глава() {
        assert!(!is_heading("Часть разговора осталась за кадром."));
        assert!(is_heading("Часть вторая"));
        assert!(is_heading("Part Two"));
        assert!(!is_heading("Book was already open on the table"));
        assert!(is_heading("Книга вторая"));
        assert!(is_heading("Глава третья"));
        assert!(is_heading("CHAPTER IV. THE OLD LIBRARY"));
    }

    #[test]
    fn стена_текста_без_пустых_строк_режется_на_абзацы() {
        // Книга вообще без пустых строк: абзацы восстанавливаются по концам
        // предложений, когда накопилось больше предела.
        let sentence = "Старая библиотека хранила тысячи историй на пыльных полках. ";
        let mut content = String::new();
        for _ in 0..60 {
            let line = sentence.trim_end();
            content.push_str(line);
            content.push('\n');
        }
        let path = книга("wolfy_txt_wall.txt", content.as_bytes());
        let mut book = TxtBook::open(&path).expect("книга открывается");

        let blocks = book.chapter(0).expect("глава").blocks;
        assert!(
            blocks.len() > 1,
            "стена текста не должна стать одним гигантским абзацем"
        );
        // И ничего не потерялось.
        let joined: String = blocks
            .iter()
            .filter_map(|b| b.text())
            .collect::<Vec<_>>()
            .join(" ");
        assert!(joined.contains("хранила тысячи историй"));
    }
}
