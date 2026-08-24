//! Центральные бюджеты парсера.
//!
//! Каждый файл книги — недоверенный вход. Маленький ZIP может
//! распаковаться в гигабайты (zip bomb), один XHTML — сожрать память,
//! а PDF — вытянуть текст на сотни мегабайт. Поэтому все лимиты живут
//! здесь, а не размазаны магическими числами по `epub.rs`, `txt.rs`,
//! `pdf.rs`.
//!
//! Значения выбраны как безопасные верхние границы для реальной книги
//! на слабом устройстве (2–3 GB RAM, A53). Это не точные замеры,
//! а защита от OOM: обычная книга укладывается с запасом, а
//! злонамеренная получает контролируемую ошибку вместо падения.

use crate::error::{CoreError, Result};

/// Сообщение, которое видит пользователь вместо OOM/паники.
///
/// Должно совпадать на native и web, чтобы клиент показывал один и тот
/// же текст: «книга слишком велика для безопасной обработки».
pub const TOO_LARGE_MSG: &str = "книга слишком велика для безопасной обработки";

/// Максимальный размер исходного файла книги на диске или в памяти.
///
/// Покрывает EPUB, TXT и PDF. Обычная книга — единицы мегабайт;
/// 80 MiB — уже очень большая книга со сканами.
pub const MAX_SOURCE_BYTES: u64 = 80 * 1024 * 1024;

/// То же в `usize` для проверок `bytes.len()`.
pub const MAX_SOURCE_BYTES_USIZE: usize = MAX_SOURCE_BYTES as usize;

/// Максимальный размер TXT-книги отдельно.
///
/// TXT несёт только текст без сжатия, поэтому лимит жёстче общего.
pub const MAX_TXT_BYTES: u64 = 10 * 1024 * 1024;
pub const MAX_TXT_BYTES_USIZE: usize = MAX_TXT_BYTES as usize;

/// Максимальное число записей в ZIP-архиве EPUB.
///
/// Защита от zip bomb с тысячами мелких файлов. Реальная книга
/// содержит десятки/сотни записей (главы + картинки + метаданные).
pub const MAX_EPUB_ENTRIES: usize = 2000;

/// Максимальный размер одной распакованной записи EPUB.
///
/// Проверяется дважды: сначала заявленный `entry.size()` из заголовка
/// ZIP, затем фактическое чтение через `take(MAX+1)` — заголовку
/// нельзя доверять.
pub const MAX_EPUB_ENTRY_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_EPUB_ENTRY_BYTES_USIZE: usize = MAX_EPUB_ENTRY_BYTES as usize;

/// Максимальный объём текста, который может породить одна глава.
///
/// Считается по уже извлечённому plain text (или по сырому XHTML до
/// разбора). Одна глава в 5 MiB текста — это ~800 тыс. слов, чего
/// в реальной книге не бывает.
pub const MAX_CHAPTER_TEXT_BYTES: usize = 5 * 1024 * 1024;

/// Максимальный общий объём извлечённого текста книги.
///
/// Ограничивает суммарный RSS после парсинга всех глав/страниц.
pub const MAX_TOTAL_TEXT_BYTES: usize = 20 * 1024 * 1024;

/// Максимальное число страниц PDF.
///
/// 3000 страниц — уже справочник/диссертация; больше — скорее
/// злонамеренный или повреждённый файл.
pub const MAX_PDF_PAGES: usize = 3000;

/// То же, что `MAX_TOTAL_TEXT_BYTES`, но явно для PDF, где текст
/// извлекается страницами на native или приходит страницами с web.
pub const MAX_PDF_TOTAL_TEXT_BYTES: usize = MAX_TOTAL_TEXT_BYTES;

/// Максимальная длина одной строки словаря.
///
/// Словарь читается двоичным поиском по диску; одна строка длиннее
/// 16 KiB — аномалия, а не статья.
pub const MAX_DICTIONARY_LINE_BYTES: usize = 16 * 1024;

/// Максимальный размер сырых байт картинки (EPUB resource).
///
/// Ограничивает `resource()` и cover extraction до декодирования.
pub const MAX_IMAGE_BYTES: usize = 25 * 1024 * 1024;

/// Максимальное число пикселей декодированной картинки.
///
/// Защита от decompression bomb: маленький JPEG может распаковаться в
/// огромный растр. 30 MP ≈ 8000×3750, куда больше любой обложки.
pub const MAX_IMAGE_PIXELS: u64 = 30_000_000;

/// Ошибка «слишком велика» — единая для всех парсеров, native и web.
pub fn too_large() -> CoreError {
    CoreError::Malformed(TOO_LARGE_MSG.to_string())
}

pub fn too_large_with_detail(detail: &str) -> CoreError {
    CoreError::Malformed(format!("{TOO_LARGE_MSG}: {detail}"))
}

/// Проверяет размер источника до любой тяжёлой работы.
pub fn check_source_size(size: u64) -> Result<()> {
    if size > MAX_SOURCE_BYTES {
        return Err(too_large_with_detail(&format!(
            "исходный файл {} байт превышает лимит {MAX_SOURCE_BYTES} байт",
            size
        )));
    }
    Ok(())
}

pub fn check_txt_size(size: u64) -> Result<()> {
    if size > MAX_TXT_BYTES {
        return Err(too_large_with_detail(&format!(
            "TXT {} байт превышает лимит {MAX_TXT_BYTES} байт",
            size
        )));
    }
    // TXT тоже не должен превышать общий лимит источника.
    check_source_size(size)
}

pub fn check_epub_entries(count: usize) -> Result<()> {
    if count > MAX_EPUB_ENTRIES {
        return Err(too_large_with_detail(&format!(
            "EPUB содержит {count} записей, лимит {MAX_EPUB_ENTRIES}"
        )));
    }
    Ok(())
}

pub fn check_epub_entry_size(declared: u64) -> Result<()> {
    if declared > MAX_EPUB_ENTRY_BYTES {
        return Err(too_large_with_detail(&format!(
            "запись EPUB {} байт превышает лимит {MAX_EPUB_ENTRY_BYTES} байт",
            declared
        )));
    }
    Ok(())
}

pub fn check_chapter_text_len(len: usize) -> Result<()> {
    if len > MAX_CHAPTER_TEXT_BYTES {
        return Err(too_large_with_detail(&format!(
            "текст главы {len} байт превышает лимит {MAX_CHAPTER_TEXT_BYTES} байт"
        )));
    }
    Ok(())
}

pub fn check_total_text_len(len: usize) -> Result<()> {
    if len > MAX_TOTAL_TEXT_BYTES {
        return Err(too_large_with_detail(&format!(
            "общий текст книги {len} байт превышает лимит {MAX_TOTAL_TEXT_BYTES} байт"
        )));
    }
    Ok(())
}

pub fn check_pdf_pages(count: usize) -> Result<()> {
    if count > MAX_PDF_PAGES {
        return Err(too_large_with_detail(&format!(
            "PDF содержит {count} страниц, лимит {MAX_PDF_PAGES}"
        )));
    }
    Ok(())
}

pub fn check_dictionary_line_len(len: usize) -> Result<()> {
    if len > MAX_DICTIONARY_LINE_BYTES {
        return Err(too_large_with_detail(&format!(
            "строка словаря {len} байт превышает лимит {MAX_DICTIONARY_LINE_BYTES} байт"
        )));
    }
    Ok(())
}

pub fn check_image_bytes(len: usize) -> Result<()> {
    if len > MAX_IMAGE_BYTES {
        return Err(too_large_with_detail(&format!(
            "изображение {} байт превышает лимит {MAX_IMAGE_BYTES} байт",
            len
        )));
    }
    Ok(())
}

pub fn check_image_pixels(pixels: u64) -> Result<()> {
    if pixels > MAX_IMAGE_PIXELS {
        return Err(too_large_with_detail(&format!(
            "изображение {} пикселей превышает лимит {MAX_IMAGE_PIXELS}",
            pixels
        )));
    }
    Ok(())
}
