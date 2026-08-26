//! Офлайн-словарь: перевод, толкования и произношение.
//!
//! Отвечает на два вопроса, которых нет ни у лексикона, ни у перевода: что
//! слово значит само по себе и как оно звучит. Перевод отвечает на первый
//! лишь отчасти — «library» это и «библиотека», и «книгохранилище», а выбрать
//! между ними помогает толкование, а не второй перевод.
//!
//! ## Почему отдельным файлом
//!
//! Словарь весит на порядок больше лексикона и потому не встраивается в DLL:
//! архив едет отдельным ресурсом установщика, а после согласия пользователя
//! распаковывается рядом с библиотекой. Пока файла нет, ядро отвечает «не
//! знаю» — и это нормальный ответ, а не ошибка.
//!
//! ## Почему не читается в память
//!
//! Семьдесят семь тысяч статей — это семь мегабайт, которые лежали бы в
//! памяти телефона ради строки, показываемой раз в минуту. Файл отсортирован
//! по слову, и ядро ищет в нём двоичным поиском прямо по диску: три-четыре
//! чтения по несколько килобайт вместо семи мегабайт в куче. После первого
//! обращения нужные страницы всё равно оседают в кэше системы, и поиск
//! становится обращением к памяти — но памяти чужой, которую система вправе
//! забрать под что-то нужнее.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use serde::Serialize;

use crate::parser::limits;
use crate::parser::Source;
use crate::Result;

/// Статья словаря.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    /// Слово в начальной форме — так, как оно стоит в словаре.
    pub word: String,
    /// Произношение в МФА без косых черт: «ˈlaɪbɹɛɹi».
    ///
    /// Пусто, если слова не оказалось в словаре произношений: толкование при
    /// этом всё равно есть, и показать его лучше, чем промолчать.
    pub pronunciation: String,
    /// Короткие русские эквиваленты из FreeDict/WikDict.
    pub translations: Vec<String>,
    pub senses: Vec<Sense>,
}

/// Одно значение слова.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Sense {
    /// Часть речи в том же наборе, что у разбора: `NOUN`, `VERB`, `ADJ`, `ADV`.
    pub pos: String,
    /// Толкование по-английски.
    ///
    /// Именно по-английски, и это не экономия на переводе. Толкование на
    /// изучаемом языке — то, чем словарь отличается от разговорника: читатель
    /// разбирает английское объяснение английского слова и остаётся в языке,
    /// вместо того чтобы выпрыгивать из него на каждом слове.
    pub definition: String,
}

/// Скачанный словарь.
///
/// Держит только открытый файл и его длину: всё остальное читается по месту.
#[derive(Debug)]
pub struct Dictionary {
    source: Source,
    length: u64,
    /// С какого байта идут статьи — заголовок пропускается один раз при
    /// открытии, а не при каждом поиске.
    start: u64,
}

/// Однобуквенные коды частей речи из файла — в тот же набор, что у разбора.
fn pos_name(code: &str) -> &'static str {
    match code {
        "n" => "NOUN",
        "v" => "VERB",
        "a" => "ADJ",
        "r" => "ADV",
        _ => "X",
    }
}

impl Dictionary {
    /// Открывает скачанный словарь.
    pub fn open(path: &Path) -> Result<Dictionary> {
        // Ошибки ввода-вывода уходят наверх как есть: «нет файла» и «нет
        // прав» ядро различить не может, а клиенту в обоих случаях отвечать
        // одинаково — словаря нет, покажем перевод.
        let file = std::fs::File::open(path)?;
        let length = file.metadata()?.len();
        Dictionary::read(Source::File(file), length)
    }

    /// Словарь, целиком лежащий в памяти.
    ///
    /// Так он приходит в браузере: файловой системы там нет, а Cache Storage
    /// отдаёт содержимое буфером. Двоичный поиск при этом остаётся тем же —
    /// он ищет по смещениям, и откуда эти байты, ему всё равно.
    pub fn from_bytes(bytes: Vec<u8>) -> Result<Dictionary> {
        let length = bytes.len() as u64;
        Dictionary::read(Source::bytes(bytes), length)
    }

    fn read(source: Source, length: u64) -> Result<Dictionary> {
        limits::check_source_size(length)?;
        let mut dictionary = Dictionary {
            source,
            length,
            start: 0,
        };
        dictionary.start = dictionary.skip_header()?;
        Ok(dictionary)
    }

    /// Ищет слово. `None` — статьи нет, и это обычный ответ.
    ///
    /// Слово приводится к нижнему регистру: в файле статьи так и лежат, а
    /// читатель тапает и по слову в начале предложения.
    pub fn lookup(&mut self, word: &str) -> Result<Option<Entry>> {
        let needle = word.trim().to_lowercase();
        if needle.is_empty() {
            return Ok(None);
        }

        let mut low = self.start;
        let mut high = self.length;

        while low < high {
            let middle = low + (high - low) / 2;
            let (line_start, line) = self.line_at(middle)?;

            let Some(found) = line.split('\t').next() else {
                return Ok(None);
            };

            match found.cmp(needle.as_str()) {
                std::cmp::Ordering::Equal => return Ok(Some(parse(&line))),
                std::cmp::Ordering::Less => {
                    // Следующая строка: без этого при low == line_start поиск
                    // застрял бы на одной и той же строке навсегда.
                    low = line_start + line.len() as u64 + 1;
                }
                std::cmp::Ordering::Greater => {
                    if line_start <= low {
                        return Ok(None);
                    }
                    high = line_start;
                }
            }
        }
        Ok(None)
    }

    /// Пропускает строки-комментарии в начале файла.
    fn skip_header(&mut self) -> Result<u64> {
        let mut at = 0u64;
        loop {
            let (_, line) = self.line_at(at)?;
            if !line.starts_with('#') {
                return Ok(at);
            }
            at += line.len() as u64 + 1;
            if at >= self.length {
                return Ok(self.length);
            }
        }
    }

    /// Строка, внутри которой стоит этот байт, и её начало.
    fn line_at(&mut self, at: u64) -> Result<(u64, String)> {
        let start = self.line_start(at)?;
        let mut buffer = Vec::new();
        let mut chunk = [0u8; CHUNK];

        self.seek(start)?;
        loop {
            let read = self.take(&mut chunk)?;
            if read == 0 {
                break;
            }
            if let Some(end) = chunk[..read].iter().position(|byte| *byte == b'\n') {
                let end_slice = &chunk[..end];
                if buffer.len() + end_slice.len() > limits::MAX_DICTIONARY_LINE_BYTES {
                    return Err(limits::too_large_with_detail(&format!(
                        "строка словаря превышает лимит {} байт",
                        limits::MAX_DICTIONARY_LINE_BYTES
                    )));
                }
                buffer.extend_from_slice(end_slice);
                break;
            }
            if buffer.len() + read > limits::MAX_DICTIONARY_LINE_BYTES {
                return Err(limits::too_large_with_detail(&format!(
                    "строка словаря превышает лимит {} байт",
                    limits::MAX_DICTIONARY_LINE_BYTES
                )));
            }
            buffer.extend_from_slice(&chunk[..read]);
        }

        // Дополнительная проверка на случай уже накопленного буфера.
        limits::check_dictionary_line_len(buffer.len())?;

        // Битую строку не роняем ошибкой: словарь скачан по сети, и один
        // сбойный байт не повод отказать читателю в остальных семидесяти
        // тысячах статей.
        Ok((start, String::from_utf8_lossy(&buffer).into_owned()))
    }

    /// Начало строки, внутри которой стоит этот байт.
    fn line_start(&mut self, at: u64) -> Result<u64> {
        if at <= self.start {
            return Ok(self.start.min(self.length));
        }
        let mut end = at.min(self.length);

        loop {
            let from = end.saturating_sub(CHUNK as u64).max(self.start);
            let size = (end - from) as usize;
            if size == 0 {
                return Ok(self.start);
            }

            let mut chunk = vec![0u8; size];
            self.seek(from)?;
            self.read_exact(&mut chunk)?;

            if let Some(found) = chunk.iter().rposition(|byte| *byte == b'\n') {
                return Ok(from + found as u64 + 1);
            }
            if from <= self.start {
                return Ok(self.start);
            }
            end = from;
        }
    }

    fn seek(&mut self, at: u64) -> Result<()> {
        self.source.seek(SeekFrom::Start(at))?;
        Ok(())
    }

    fn take(&mut self, into: &mut [u8]) -> Result<usize> {
        Ok(self.source.read(into)?)
    }

    fn read_exact(&mut self, into: &mut [u8]) -> Result<()> {
        self.source.read_exact(into)?;
        Ok(())
    }
}

/// Сколько читать за раз.
///
/// Четыре килобайта — страница памяти: меньше система всё равно не прочитает,
/// а больше не понадобится, потому что статья длиннее страницы не бывает.
const CHUNK: usize = 4096;

/// Разбирает строку файла: `слово<TAB>мфа<TAB>часть|толкование…`.
fn parse(line: &str) -> Entry {
    let mut columns = line.split('\t');
    let word = columns.next().unwrap_or_default().to_string();
    let pronunciation = columns.next().unwrap_or_default().to_string();

    let mut translations = Vec::new();
    let senses = columns
        .filter_map(|column| {
            let (code, definition) = column.split_once('|')?;
            if definition.is_empty() {
                return None;
            }
            if code == "t" {
                translations.push(definition.to_string());
                return None;
            }
            Some(Sense {
                pos: pos_name(code).to_string(),
                definition: definition.to_string(),
            })
        })
        .collect();

    Entry {
        word,
        pronunciation,
        translations,
        senses,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// Словарик из нескольких статей — с тем же заголовком, что у настоящего.
    fn словарь(тело: &str) -> (tempfile::NamedTempFile, Dictionary) {
        let mut file = tempfile::NamedTempFile::new().expect("файл не создался");
        write!(
            file,
            "# wolfy english dictionary v1\n# generated\t2026-08-23\n{тело}"
        )
        .expect("не записалось");
        file.flush().expect("не сбросилось");
        let dictionary = Dictionary::open(file.path()).expect("словарь не открылся");
        (file, dictionary)
    }

    const ТЕЛО: &str = "\
about\tәˈbaʊt\tr|in the area or vicinity
gloom\tɡlum\tn|a feeling of melancholy apprehension
library\tˈlaɪˌbɹɛɹi\tt|библиотека\tt|книгохранилище\tn|a room where books are kept\tn|a depository built to contain books
water\tˈwɔtɚ\tn|a liquid necessary for life\tv|supply with water
zebra\tˈzibɹə\tn|a striped horse
";

    /// В браузере файла нет, а словарь всё равно обязан искаться.
    #[test]
    fn словарь_из_памяти_ищет_так_же_как_с_диска() {
        let текст = format!(
            "# wolfy english dictionary v1
# generated	2026-08-23
{ТЕЛО}"
        );
        let mut dictionary =
            Dictionary::from_bytes(текст.into_bytes()).expect("словарь из памяти не открылся");

        let entry = dictionary
            .lookup("Library")
            .expect("поиск сломался")
            .expect("статьи нет");
        assert_eq!(entry.word, "library");
        assert_eq!(entry.translations, vec!["библиотека", "книгохранилище"]);

        // И отсутствие статьи — обычный ответ, а не ошибка.
        assert!(dictionary
            .lookup("zzzqx")
            .expect("поиск сломался")
            .is_none());
    }

    #[test]
    fn слово_находится_вместе_с_произношением_и_значениями() {
        let (_file, mut dictionary) = словарь(ТЕЛО);
        let entry = dictionary
            .lookup("library")
            .expect("поиск сломался")
            .expect("статьи нет");

        assert_eq!(entry.word, "library");
        assert_eq!(entry.pronunciation, "ˈlaɪˌbɹɛɹi");
        assert_eq!(entry.translations, ["библиотека", "книгохранилище"]);
        assert_eq!(entry.senses.len(), 2);
        assert_eq!(entry.senses[0].pos, "NOUN");
        assert_eq!(entry.senses[0].definition, "a room where books are kept");
    }

    #[test]
    fn находятся_края_и_середина() {
        // Двоичный поиск чаще всего врёт именно на краях.
        let (_file, mut dictionary) = словарь(ТЕЛО);
        for word in ["about", "gloom", "library", "water", "zebra"] {
            assert!(
                dictionary.lookup(word).expect("поиск сломался").is_some(),
                "слово «{word}» не нашлось"
            );
        }
    }

    #[test]
    fn незнакомое_слово_это_обычный_ответ_а_не_ошибка() {
        let (_file, mut dictionary) = словарь(ТЕЛО);
        for word in ["aardvark", "middle", "zzzqx", ""] {
            assert_eq!(
                dictionary.lookup(word).expect("поиск сломался"),
                None,
                "слово «{word}» нашлось из ниоткуда"
            );
        }
    }

    #[test]
    fn регистр_не_мешает() {
        // Читатель тапает и по слову в начале предложения.
        let (_file, mut dictionary) = словарь(ТЕЛО);
        assert!(dictionary
            .lookup("Library")
            .expect("поиск сломался")
            .is_some());
        assert!(dictionary
            .lookup("  GLOOM  ")
            .expect("поиск сломался")
            .is_some());
    }

    #[test]
    fn части_речи_переводятся_в_общий_набор() {
        let (_file, mut dictionary) = словарь(ТЕЛО);
        let entry = dictionary
            .lookup("water")
            .expect("поиск сломался")
            .expect("нет");
        let parts: Vec<&str> = entry.senses.iter().map(|s| s.pos.as_str()).collect();
        assert_eq!(parts, vec!["NOUN", "VERB"]);
    }

    #[test]
    fn статья_без_произношения_всё_равно_показывается() {
        // Слова может не быть в словаре произношений, а толкование есть —
        // промолчать хуже, чем показать половину.
        let (_file, mut dictionary) = словарь("mizzle\t\tn|a light drizzle\n");
        let entry = dictionary
            .lookup("mizzle")
            .expect("поиск сломался")
            .expect("нет");
        assert!(entry.pronunciation.is_empty());
        assert_eq!(entry.senses.len(), 1);
    }

    #[test]
    fn пустой_словарь_не_роняет_поиск() {
        let (_file, mut dictionary) = словарь("");
        assert_eq!(dictionary.lookup("library").expect("поиск сломался"), None);
    }

    #[test]
    fn отсутствующий_файл_это_ошибка_а_не_паника() {
        assert!(Dictionary::open(Path::new("нет-такого-файла.tsv")).is_err());
    }

    #[test]
    fn oversized_dictionary_line_rejected() {
        let huge = "a".repeat(crate::parser::limits::MAX_DICTIONARY_LINE_BYTES + 1);
        let line = format!("word\tpron\t{huge}\n");
        // Словарь открывается, но чтение уже на этапе skip_header или lookup
        // должно обнаружить слишком длинную строку. В текущей реализации
        // защита срабатывает уже при open (через skip_header), что тоже
        // считается контролируемой ошибкой «слишком велика», а не паникой/OOM.
        let mut file = tempfile::NamedTempFile::new().expect("файл не создался");
        use std::io::Write;
        write!(
            file,
            "# wolfy english dictionary v1\n# generated\t2026-08-23\n{line}"
        )
        .expect("не записалось");
        file.flush().expect("не сбросилось");
        let open_result = Dictionary::open(file.path());
        if let Ok(mut dictionary) = open_result {
            let err = dictionary
                .lookup("word")
                .expect_err("строка слишком длинна");
            assert!(
                err.describe().contains("слишком велика"),
                "{}",
                err.describe()
            );
        } else {
            let err = open_result.expect_err("ожидалась ошибка лимита");
            assert!(
                err.describe().contains("слишком велика"),
                "{}",
                err.describe()
            );
        }
    }

    #[test]
    fn dictionary_source_too_large_rejected() {
        let large = vec![b'a'; crate::parser::limits::MAX_SOURCE_BYTES_USIZE + 1];
        let err = Dictionary::from_bytes(large).expect_err("источник слишком велик");
        assert!(
            err.describe().contains("слишком велика"),
            "{}",
            err.describe()
        );
    }
}
