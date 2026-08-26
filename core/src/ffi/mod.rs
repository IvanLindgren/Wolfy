//! Граница с клиентом: C-совместимый интерфейс ядра.
//!
//! ## Почему через JSON, а не через плоские структуры
//!
//! Соблазн отдавать `#[repr(C)]`-структуры велик, но у него есть цена: каждое
//! поле нужно вручную разложить в JNI на стороне Kotlin, каждый массив —
//! выделить и освободить, а любое изменение формы данных ломает обе стороны
//! молча. JSON снимает всё это разом, и платим мы за него ровно одной
//! сериализацией.
//!
//! Цена измерена: разбор слова занимает единицы микросекунд, сериализация
//! ответа — того же порядка. При обещанном пороге в 15 мс это шум. Глава
//! книги крупнее, но она читается раз на несколько минут чтения, а не на
//! каждый тап.
//!
//! ## Правила этой границы
//!
//! 1. Через FFI не выпускается ни одна паника: каждый вход обёрнут в
//!    `catch_unwind`, иначе развёртывание стека уйдёт в чужой рантайм и уронит
//!    приложение целиком.
//! 2. Каждая строка, выданная ядром, освобождается [`wolfy_string_free`] —
//!    и только им. Освободить её аллокатором Kotlin нельзя.
//! 3. Ошибка возвращается как `null`, а её описание лежит в
//!    [`wolfy_last_error`] для того же потока.
//!
//! Заголовок для компоновщика — `core/include/wolfy_core.h`.

// Публичный, потому что в те же формы отвечает и `wasm`-дверь: контракт с
// клиентом один, и второй его копии быть не должно.
pub mod dto;
pub mod session;

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};

use crate::lexicon::{analyze, Lexicon};
use crate::parser::{self, Book};
use crate::tokenizer::{split, tokenize};

use session::{Command, Session};

use dto::{
    ArticleDto, BookDto, ChapterDto, ChunkDto, ExerciseDto, ExercisesDto, FindingDto, GrammarDto,
    MarkerDto, PartDto, ReferenceDto, TextDto, TokenDto, WordDto,
};

thread_local! {
    /// Описание последней ошибки в этом потоке.
    ///
    /// Именно в этом: клиент разбирает книгу в фоновом потоке и одновременно
    /// жмёт по словам в главном, и общая на всех переменная показывала бы
    /// чужую ошибку.
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
}

/// Открытые книги. Клиент держит не указатель, а число: указатель, пришедший
/// с чужой стороны, невозможно проверить, а несуществующий номер — можно.
///
/// Структура `Mutex<HashMap<Handle, Arc<Mutex<Book>>>>` выбрана намеренно,
/// чтобы тяжёлая работа не держала глобальный lock и паника одной книги не
/// отравляла реестр всех книг: глобальный мьютекс держится только на время
/// клонирования `Arc`, а сама операция идёт под пер-объектным мьютексом.
static BOOKS: LazyLock<Mutex<HashMap<i64, Arc<Mutex<Box<dyn Book>>>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Открытые сессии — библиотека и настройки читателя. Номер вместо указателя
/// по той же причине, что у книг.
static SESSIONS: LazyLock<Mutex<HashMap<i64, Arc<Mutex<Session>>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

/// Общий счётчик номеров. Один на книги и сессии: перепутать их нельзя, а
/// два счётчика выдавали бы одинаковые числа и путали бы при чтении журнала.
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

/// Версия ядра. Клиент сверяет её со своей и показывает в диагностике.
///
/// # Safety
/// Возвращённую строку освобождает [`wolfy_string_free`].
#[no_mangle]
pub extern "C" fn wolfy_version() -> *mut c_char {
    guard(|| Some(crate::VERSION.to_string()))
}

/// Описание последней ошибки в текущем потоке или `null`, если её не было.
///
/// # Safety
/// Строка принадлежит ядру и живёт до следующего вызова из этого потока.
/// Копируйте её, а не храните указатель.
#[no_mangle]
pub extern "C" fn wolfy_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| match slot.borrow().as_ref() {
        Some(message) => message.as_ptr(),
        None => std::ptr::null(),
    })
}

/// Освобождает строку, выданную ядром.
///
/// # Safety
/// Указатель обязан быть получен от функции этого модуля и не должен
/// освобождаться дважды.
#[no_mangle]
pub unsafe extern "C" fn wolfy_string_free(text: *mut c_char) {
    if text.is_null() {
        return;
    }
    // SAFETY: строка создана `CString::into_raw` в `guard`, владение
    // возвращается сюда ровно один раз.
    drop(unsafe { CString::from_raw(text) });
}

/// Разбирает одно слово: начальная форма, части речи, разбор, частотность.
///
/// # Safety
/// `word` — непустой указатель на строку в UTF-8, оканчивающуюся нулём.
#[no_mangle]
pub unsafe extern "C" fn wolfy_analyze_word(word: *const c_char) -> *mut c_char {
    guard(|| {
        let word = unsafe { read_string(word) }?;
        let analysis = analyze(Lexicon::embedded(), &word);
        to_json(&WordDto::from(&analysis))
    })
}

/// Разбивает текст на токены и предложения.
///
/// # Safety
/// `text` — непустой указатель на строку в UTF-8, оканчивающуюся нулём.
#[no_mangle]
pub unsafe extern "C" fn wolfy_tokenize(text: *const c_char) -> *mut c_char {
    guard(|| {
        let text = unsafe { read_string(text) }?;
        let tokens = tokenize(&text);
        let sentences = split(&tokens);

        to_json(&TextDto {
            tokens: tokens.iter().map(TokenDto::from).collect(),
            sentences: sentences.iter().map(Into::into).collect(),
        })
    })
}

/// Разбирает грамматику предложения: время, залог, модальность, условие.
///
/// На вход идёт предложение целиком, а не слово: разбор смотрит на соседей, и
/// обрывок фразы разберётся хуже, чем фраза целиком. Границы предложений
/// клиент уже знает — их отдаёт [`wolfy_tokenize`].
///
/// Смещения в ответе — индексы токенов того же разбора, что вернул
/// `wolfy_tokenize` для этого текста.
///
/// # Safety
/// `text` — непустой указатель на строку в UTF-8, оканчивающуюся нулём.
#[no_mangle]
pub unsafe extern "C" fn wolfy_explain(text: *const c_char) -> *mut c_char {
    guard(|| {
        let text = unsafe { read_string(text) }?;
        let tokens = tokenize(&text);
        let lexicon = Lexicon::embedded();
        let parts = crate::tagger::tag(lexicon, &tokens);
        let findings = crate::grammar::analyze(lexicon, &tokens);
        let chunks = crate::grammar::chunks(lexicon, &tokens, &findings);
        let markers = crate::grammar::markers(lexicon, &tokens, &findings);

        to_json(&GrammarDto {
            parts: parts.iter().map(PartDto::from).collect(),
            findings: findings.iter().map(FindingDto::from).collect(),
            chunks: chunks.iter().map(ChunkDto::from).collect(),
            markers: markers.iter().map(MarkerDto::from).collect(),
        })
    })
}

/// Отдаёт справочник грамматики целиком.
///
/// Целиком, а не по статье: их два десятка, вместе они весят несколько
/// килобайт, и ходить в ядро за каждой значило бы гонять границу FFI ради
/// экономии, которой не видно.
///
/// Объяснения приходят от самих детекторов — тех же, что разбирают книгу.
/// Поэтому справочник не может разойтись с тем, что читатель видит в карточке.
#[no_mangle]
pub extern "C" fn wolfy_grammar_reference() -> *mut c_char {
    guard(|| {
        let articles = crate::grammar::articles(Lexicon::embedded());
        to_json(&ReferenceDto {
            articles: articles.iter().map(ArticleDto::from).collect(),
        })
    })
}

/// Микро-упражнения по грамматике.
///
/// Отдаются все сразу — их несколько десятков, а перемешивать и выбирать из
/// них должна колода, которая одна знает, что читатель уже помнит.
///
/// # Safety
/// Возвращённую строку освобождает [`wolfy_string_free`].
#[no_mangle]
pub extern "C" fn wolfy_grammar_exercises() -> *mut c_char {
    guard(|| {
        let exercises = crate::grammar::exercises(Lexicon::embedded());
        to_json(&ExercisesDto {
            exercises: exercises.iter().map(ExerciseDto::from).collect(),
        })
    })
}

/// Открывает книгу и возвращает её номер, либо 0 при ошибке.
///
/// Номер обязателен к закрытию через [`wolfy_book_close`]: пока книга открыта,
/// ядро держит её файл.
///
/// # Safety
/// `path` — непустой указатель на строку в UTF-8, оканчивающуюся нулём.
#[no_mangle]
pub unsafe extern "C" fn wolfy_book_open(path: *const c_char) -> i64 {
    let opened = catch_unwind(AssertUnwindSafe(|| {
        let path = unsafe { read_string(path) }?;
        let book = match parser::open(Path::new(&path)) {
            Ok(book) => book,
            Err(err) => {
                set_error(&err.describe());
                return None;
            }
        };

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let mut guard = match BOOKS.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.insert(handle, Arc::new(Mutex::new(book)));
        Some(handle)
    }));

    match opened {
        Ok(Some(handle)) => {
            clear_error();
            handle
        }
        Ok(None) => 0,
        Err(_) => {
            set_error("ядро не смогло открыть книгу");
            0
        }
    }
}

/// Метаданные и оглавление открытой книги.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_book_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_book_metadata(handle: i64) -> *mut c_char {
    guard(|| {
        with_book(handle, |book| {
            to_json(&BookDto::new(book.metadata(), book.contents()))
        })?
    })
}

/// Читает главу книги. Это единственная тяжёлая операция — вызывайте её из
/// фонового потока.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_book_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_book_chapter(handle: i64, index: usize) -> *mut c_char {
    guard(|| {
        with_book(handle, |book| match book.chapter(index) {
            Ok(chapter) => to_json(&ChapterDto::from(&chapter)),
            Err(err) => {
                set_error(&err.describe());
                None
            }
        })?
    })
}

/// Загружает один бинарный ресурс открытой книги (сейчас — иллюстрацию EPUB).
///
/// В отличие от глав, байты не кодируются в JSON/Base64: это добавило бы
/// треть памяти к каждой картинке и лишнюю полную копию на UI-пути. Владелец
/// обязан один раз вызвать [`wolfy_bytes_free`] с тем же указателем и длиной.
/// При ошибке возвращается `null`, длина становится нулевой, а причина лежит
/// в [`wolfy_last_error`] текущего потока.
///
/// # Safety
/// `path` — корректная UTF-8 C-строка, `out_len` — доступный указатель на
/// `size_t`. Результат нельзя читать после `wolfy_bytes_free`.
#[no_mangle]
pub unsafe extern "C" fn wolfy_book_resource(
    handle: i64,
    path: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    if out_len.is_null() {
        set_error("не передана длина ресурса");
        return std::ptr::null_mut();
    }
    // Даже при ошибке вызывающий не увидит случайное старое значение.
    unsafe { *out_len = 0 };

    let produced = catch_unwind(AssertUnwindSafe(|| {
        let path = unsafe { read_string(path) }?;
        with_book(handle, |book| book.resource(&path))
    }));

    let bytes = match produced {
        Ok(Some(Ok(bytes))) => bytes,
        Ok(Some(Err(error))) => {
            set_error(&error.describe());
            return std::ptr::null_mut();
        }
        Ok(None) => return std::ptr::null_mut(),
        Err(_) => {
            set_error("внутренняя ошибка ядра при чтении ресурса");
            return std::ptr::null_mut();
        }
    };

    // `Vec::from_raw_parts(ptr, len, len)` был бы неверен: capacity Vec
    // может отличаться от len. Box<[u8]> хранит ровно известный размер и
    // поэтому симметрично освобождается функцией ниже.
    let length = bytes.len();
    let pointer = Box::into_raw(bytes.into_boxed_slice()) as *mut u8;
    unsafe { *out_len = length };
    clear_error();
    pointer
}

/// Освобождает байты, полученные от [`wolfy_book_resource`].
///
/// # Safety
/// `bytes` и `len` должны быть возвращены одной успешной операцией
/// `wolfy_book_resource`; повторный вызов с тем же указателем запрещён.
#[no_mangle]
pub unsafe extern "C" fn wolfy_bytes_free(bytes: *mut u8, len: usize) {
    if bytes.is_null() {
        return;
    }
    // SAFETY: ресурс передаётся как Box<[u8]> с ровно `len` элементами.
    let slice = std::ptr::slice_from_raw_parts_mut(bytes, len);
    drop(unsafe { Box::from_raw(slice) });
}

/// Читает главу вместе с токенами и предложениями — один тяжёлый переход.
///
/// Смещения токенов — в единицах UTF-16, текст токенов не дублируется.
/// Клиент режет строку главы по смещениям.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_book_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_book_prepared_chapter(handle: i64, index: usize) -> *mut c_char {
    guard(|| {
        with_book(handle, |book| match book.chapter(index) {
            Ok(chapter) => {
                let prepared = crate::prepared::prepare(&chapter);
                to_json(&prepared)
            }
            Err(err) => {
                set_error(&err.describe());
                None
            }
        })?
    })
}

/// Якоря полужирного выделения для куска текста.
///
/// Нумерация совпадает с [`wolfy_tokenize`] того же текста: клиент, который
/// разбирает книгу по абзацам, сопоставляет якоря со своими токенами по
/// номеру, ничего не пересчитывая.
///
/// # Safety
/// `text` — непустой указатель на UTF-8 строку с нулём на конце.
#[no_mangle]
pub unsafe extern "C" fn wolfy_text_anchors(text: *const c_char) -> *mut c_char {
    guard(|| {
        let text = unsafe { read_string(text) }?;
        let anchors = crate::reading::text_anchors(crate::lexicon::Lexicon::embedded(), &text);
        to_json(&anchors)
    })
}

/// Якоря полужирного выделения для главы: по числу на токен.
///
/// Считается на всю главу разом — десять тысяч переходов через границу FFI
/// ради десяти тысяч слов стоили бы дороже самого разбора. У всего, что не
/// слово, якорь нулевой, поэтому номера якорей совпадают с номерами токенов
/// подготовленной главы.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_book_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_book_chapter_anchors(handle: i64, index: usize) -> *mut c_char {
    guard(|| {
        with_book(handle, |book| match book.chapter(index) {
            Ok(chapter) => {
                let anchors = crate::reading::text_anchors(
                    crate::lexicon::Lexicon::embedded(),
                    &chapter.plain_text(),
                );
                to_json(&anchors)
            }
            Err(err) => {
                set_error(&err.describe());
                None
            }
        })?
    })
}

/// Отрезок чтения главы: докуда честно читать за один подход.
///
/// `from` — номер токена, с которого читатель продолжает; `target_words` —
/// сколько слов он готов прочитать. Конец отрезка подтягивается к границе
/// предложения, поэтому вернувшееся число слов бывает больше заказанного.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_book_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_book_chapter_segment(
    handle: i64,
    index: usize,
    from: usize,
    target_words: usize,
) -> *mut c_char {
    guard(|| {
        with_book(handle, |book| match book.chapter(index) {
            Ok(chapter) => {
                let text = chapter.plain_text();
                let tokens = crate::tokenizer::tokenize(&text);
                let sentences = crate::tokenizer::split(&tokens);
                let segment = crate::reading::segment(&tokens, &sentences, from, target_words);
                to_json(&crate::ffi::dto::SegmentDto::from(segment))
            }
            Err(err) => {
                set_error(&err.describe());
                None
            }
        })?
    })
}

/// Всё локальное для карточки за один вызов.
///
/// На вход: выбранное слово и предложение вокруг него. Возвращает анализ слова,
/// токены предложения, грамматику, маркеры и граф.
///
/// # Safety
/// `word` и `sentence` — непустые указатели на UTF-8 строки с нулём на конце.
#[no_mangle]
pub unsafe extern "C" fn wolfy_inspect_word(
    word: *const c_char,
    sentence: *const c_char,
) -> *mut c_char {
    guard(|| {
        let word = unsafe { read_string(word) }?;
        let sentence = unsafe { read_string(sentence) }?;
        let inspected = crate::inspect::inspect_word(&word, &sentence);
        to_json(&inspected)
    })
}

/// Закрывает книгу и отпускает её файл.
///
/// Удаляет handle из реестра даже если пер-объектный мьютекс poisoned:
/// удаление требует только глобального реестра, а не блокировки самой книги.
#[no_mangle]
pub extern "C" fn wolfy_book_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        match BOOKS.lock() {
            Ok(mut guard) => {
                guard.remove(&handle);
            }
            Err(poisoned) => {
                poisoned.into_inner().remove(&handle);
            }
        }
    }));
}

/// Общая обёртка входной точки.
///
/// Делает три вещи, одинаковые для всех вызовов: ловит панику, переводит
/// результат в C-строку и отражает исход в [`wolfy_last_error`].
fn guard<F>(body: F) -> *mut c_char
where
    F: FnOnce() -> Option<String>,
{
    let produced = catch_unwind(AssertUnwindSafe(body));

    let text = match produced {
        Ok(Some(text)) => text,
        Ok(None) => return std::ptr::null_mut(),
        Err(_) => {
            // Паника внутри ядра — это его собственный баг. Наружу она не
            // выпускается: развёртывание стека через границу FFI уронило бы
            // приложение целиком.
            set_error("внутренняя ошибка ядра");
            return std::ptr::null_mut();
        }
    };

    match CString::new(text) {
        Ok(value) => {
            clear_error();
            value.into_raw()
        }
        Err(_) => {
            // Нулевой байт внутри строки: C-строкой такое не отдать.
            set_error("в ответе ядра оказался недопустимый символ");
            std::ptr::null_mut()
        }
    }
}

/// Достаёт книгу из реестра и что-то с ней делает.
///
/// Алгоритм: lock registry, clone Arc, unlock registry, lock конкретную книгу.
/// Так тяжёлая работа не держит глобальный мьютекс и паника одной книги не
/// отравляет реестр всех книг. `catch_unwind` остаётся на FFI-границе.
fn with_book<T, F>(handle: i64, body: F) -> Option<T>
where
    F: FnOnce(&mut Box<dyn Book>) -> T,
{
    let book_arc = {
        let guard = match BOOKS.lock() {
            Ok(g) => g,
            Err(_) => {
                set_error("реестр книг повреждён");
                return None;
            }
        };
        match guard.get(&handle) {
            Some(arc) => Arc::clone(arc),
            None => {
                set_error("книга уже закрыта или не открывалась");
                return None;
            }
        }
    };

    let mut book_guard = match book_arc.lock() {
        Ok(g) => g,
        Err(_) => {
            set_error("книга повреждена после сбоя");
            return None;
        }
    };
    Some(body(&mut *book_guard))
}

/// Достаёт сессию из реестра и что-то с ней делает.
///
/// Тот же алгоритм, что у `with_book`: глобальный реестр не держится на время
/// выполнения команды, паника одной сессии не отравляет остальные.
fn with_session<T, F>(handle: i64, body: F) -> Option<T>
where
    F: FnOnce(&mut Session) -> T,
{
    let session_arc = {
        let guard = match SESSIONS.lock() {
            Ok(g) => g,
            Err(_) => {
                set_error("реестр сессий повреждён");
                return None;
            }
        };
        match guard.get(&handle) {
            Some(arc) => Arc::clone(arc),
            None => {
                set_error("сессия уже закрыта или не открывалась");
                return None;
            }
        }
    };

    let mut session_guard = match session_arc.lock() {
        Ok(g) => g,
        Err(_) => {
            set_error("сессия повреждена после сбоя");
            return None;
        }
    };
    Some(body(&mut *session_guard))
}

/// Читает необязательную C-строку: `null` здесь означает «записи ещё нет».
///
/// # Safety
/// `raw` — либо `null`, либо корректная строка с нулевым байтом на конце.
unsafe fn read_optional(raw: *const c_char) -> Option<String> {
    if raw.is_null() {
        return None;
    }
    // SAFETY: проверили на null; за корректность строки отвечает вызывающий.
    unsafe { CStr::from_ptr(raw) }
        .to_str()
        .ok()
        .map(str::to_string)
}

/// Читает C-строку, полученную с чужой стороны.
///
/// # Safety
/// `raw` — либо `null`, либо корректная строка с нулевым байтом на конце.
unsafe fn read_string(raw: *const c_char) -> Option<String> {
    if raw.is_null() {
        set_error("передан пустой указатель");
        return None;
    }
    // SAFETY: проверили на null; за корректность строки отвечает вызывающий,
    // и это зафиксировано в контракте функций модуля.
    match unsafe { CStr::from_ptr(raw) }.to_str() {
        Ok(text) => Some(text.to_string()),
        Err(_) => {
            set_error("строка не в UTF-8");
            None
        }
    }
}

fn to_json<T: serde::Serialize>(value: &T) -> Option<String> {
    match serde_json::to_string(value) {
        Ok(json) => Some(json),
        Err(err) => {
            set_error(&format!("ответ не сериализуется: {err}"));
            None
        }
    }
}

fn set_error(message: &str) {
    // Сообщение своё, но подстраховка нужна: в описание ошибки попадает путь
    // к файлу пользователя, а в нём может оказаться что угодно.
    let value = CString::new(message).unwrap_or_else(|_| {
        #[allow(clippy::expect_used)]
        CString::new("ошибка ядра").expect("постоянная строка без нулевых байтов")
    });
    LAST_ERROR.with(|slot| *slot.borrow_mut() = Some(value));
}

fn clear_error() {
    LAST_ERROR.with(|slot| *slot.borrow_mut() = None);
}

// --- сессия: библиотека и настройки ---

/// Открывает сессию на сохранённом состоянии (lenient, legacy).
///
/// Оба аргумента — записи, прочитанные клиентом с диска, или `null`, если их
/// ещё нет. Битую запись молча заменяет пустой: падение на старте не оставило
/// бы читателю ничего, а так приложение откроется. Для нового кода, где
/// повреждение обязано быть видимым, используйте [`wolfy_session_open_strict`]:
/// `null`/пустая запись -> Default, а непустая битая -> ошибка и `0`.
/// Клиент после ошибки не должен автоматически сохранять пустое состояние
/// поверх повреждённого.
///
/// Возвращает номер сессии или ноль при ошибке.
///
/// # Safety
/// `library` и `settings` — либо `null`, либо корректные UTF-8 строки с нулём
/// на конце.
#[no_mangle]
pub unsafe extern "C" fn wolfy_session_open(
    library: *const c_char,
    settings: *const c_char,
) -> i64 {
    let opened = catch_unwind(AssertUnwindSafe(|| {
        // Пустой указатель здесь не ошибка, а «записи ещё нет», поэтому
        // read_string с его диагностикой тут не годится.
        let library = unsafe { read_optional(library) };
        let settings = unsafe { read_optional(settings) };
        let session = Session::open(library.as_deref(), settings.as_deref());

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let mut guard = match SESSIONS.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.insert(handle, Arc::new(Mutex::new(session)));
        Some(handle)
    }));

    match opened {
        Ok(Some(handle)) => {
            clear_error();
            handle
        }
        Ok(None) => 0,
        Err(_) => {
            set_error("ядро не смогло открыть сессию");
            0
        }
    }
}

/// Строгое открытие сессии: отличает «файла нет» от «файл повреждён».
///
/// - `null` / пустая строка / whitespace -> `Default` (файла нет, ок)
/// - валидный JSON -> распарсенное состояние
/// - непустой битый JSON -> ошибка, возвращает `0` и кладёт описание в
///   [`wolfy_last_error`] (`library corrupted: ...` или `settings corrupted: ...`).
/// Клиент после такой ошибки не должен автоматически сохранять пустое
/// состояние поверх повреждённого; вместо этого показать восстановление
/// (primary valid -> primary, primary broken + backup valid -> backup,
/// оба broken -> явная ошибка/recovery UI).
///
/// # Safety
/// `library` и `settings` — либо `null`, либо корректные UTF-8 строки с нулём
/// на конце.
#[no_mangle]
pub unsafe extern "C" fn wolfy_session_open_strict(
    library: *const c_char,
    settings: *const c_char,
) -> i64 {
    let opened = catch_unwind(AssertUnwindSafe(|| {
        let library = unsafe { read_optional(library) };
        let settings = unsafe { read_optional(settings) };
        let session = match Session::try_open(library.as_deref(), settings.as_deref()) {
            Ok(s) => s,
            Err(e) => {
                set_error(&e);
                return None;
            }
        };

        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let mut guard = match SESSIONS.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.insert(handle, Arc::new(Mutex::new(session)));
        Some(handle)
    }));

    match opened {
        Ok(Some(handle)) => {
            clear_error();
            handle
        }
        Ok(None) => 0,
        Err(_) => {
            set_error("ядро не смогло открыть сессию (strict)");
            0
        }
    }
}

/// Выполняет команду над библиотекой или настройками.
///
/// `command` — JSON с полем `op`; остальные поля зависят от команды. Ответ —
/// JSON с полем `changed` и тем, что команда вернула. Полный перечень —
/// в `session::Command`.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
/// `command` — корректная UTF-8 строка с нулём на конце.
#[no_mangle]
pub unsafe extern "C" fn wolfy_session_run(handle: i64, command: *const c_char) -> *mut c_char {
    guard(|| {
        let text = unsafe { read_string(command) }?;
        let command: Command = match serde_json::from_str(&text) {
            Ok(command) => command,
            Err(err) => {
                // Имя незнакомой команды попадает в описание ошибки: без него
                // расхождение клиента и ядра ищется вслепую.
                set_error(&format!("ядро не поняло команду: {err}"));
                return None;
            }
        };
        with_session(handle, |session| to_json(&session.run(command)))?
    })
}

/// Библиотека целиком — то, что клиент пишет на диск.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_library(handle: i64) -> *mut c_char {
    guard(|| with_session(handle, |session| to_json(&session.library))?)
}

/// Настройки целиком.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_settings(handle: i64) -> *mut c_char {
    guard(|| with_session(handle, |session| to_json(&session.settings))?)
}

/// Что изменилось с последней записи на диск.
///
/// Отвечает `{"library":bool,"settings":bool,"practice":bool,"libraryGeneration":i64,"settingsGeneration":i64,"practiceGeneration":i64}`.
/// Считает ядро, а не клиент:
/// только оно знает, изменила ли команда хоть что-нибудь, — повторное
/// сохранение слова, которое уже в колоде, не меняет ничего.
/// Генерации нужны для §17 Persist performance: клиент сохраняет снапшот
/// поколения N и подтверждает `ackSaved(N)`, чтобы не потерять мутацию N+1.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_dirty(handle: i64) -> *mut c_char {
    guard(|| {
        with_session(handle, |session| {
            to_json(&serde_json::json!({
                "library": session.library_dirty,
                "settings": session.settings_dirty,
                "practice": session.practice_dirty,
                "libraryGeneration": session.library_generation,
                "settingsGeneration": session.settings_generation,
                "practiceGeneration": session.practice_generation,
                "librarySavedGeneration": session.library_saved_generation,
                "settingsSavedGeneration": session.settings_saved_generation,
                "practiceSavedGeneration": session.practice_saved_generation,
            }))
        })?
    })
}

/// Отмечает, что состояние записано на диск.
///
/// Отдельным вызовом, а не внутри чтения: между «отдай мне библиотеку» и
/// «файл лёг на диск» запись может не удаться, и снимать пометку до того, как
/// это подтвердилось, значит однажды потерять главу.
///
/// §17 Generation-aware: старый API без поколений подтверждает текущее поколение
/// (синхронный клиент без гонки). Для гонки с фоновой записью используйте
/// `wolfy_session_ack_saved` с конкретным поколением снапшота.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_saved(handle: i64, library: bool, settings: bool) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_session(handle, |session| {
            if library {
                let g = session.library_generation;
                session.ack_saved(Some(g), None, None);
            }
            if settings {
                let g = session.settings_generation;
                session.ack_saved(None, Some(g), None);
            }
        });
    }));
}

/// Отмечает, что состояние записано на диск, включая practice (§6).
///
/// Новый клиент должен звать эту функцию (3 флага), а не `wolfy_session_saved`.
/// Старая `wolfy_session_saved` оставлена для совместимости.
/// Для §17 используйте `wolfy_session_ack_saved` с поколениями.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_saved_with_practice(
    handle: i64,
    library: bool,
    settings: bool,
    practice: bool,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_session(handle, |session| {
            if library {
                let g = session.library_generation;
                session.ack_saved(Some(g), None, None);
            }
            if settings {
                let g = session.settings_generation;
                session.ack_saved(None, Some(g), None);
            }
            if practice {
                let g = session.practice_generation;
                session.ack_saved(None, None, Some(g));
            }
        });
    }));
}

/// Generation-aware подтверждение записи (§17).
///
/// `library_gen`, `settings_gen`, `practice_gen` — поколения снапшотов,
/// которые успешно записаны на диск. `-1` означает «не подтверждать этот домен».
/// Dirty снимается только до N: если текущее поколение уже N+1, dirty остаётся true.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_ack_saved(
    handle: i64,
    library_gen: i64,
    settings_gen: i64,
    practice_gen: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_session(handle, |session| {
            let lib = if library_gen >= 0 { Some(library_gen) } else { None };
            let set = if settings_gen >= 0 { Some(settings_gen) } else { None };
            let prac = if practice_gen >= 0 { Some(practice_gen) } else { None };
            session.ack_saved(lib, set, prac);
        });
    }));
}

/// Текущие поколения dirty-состояния (§17).
///
/// Отвечает `{"library":i64,"settings":i64,"practice":i64,
/// "librarySaved":i64,"settingsSaved":i64,"practiceSaved":i64}`.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_generations(handle: i64) -> *mut c_char {
    guard(|| {
        with_session(handle, |session| {
            to_json(&serde_json::json!({
                "library": session.library_generation,
                "settings": session.settings_generation,
                "practice": session.practice_generation,
                "librarySaved": session.library_saved_generation,
                "settingsSaved": session.settings_saved_generation,
                "practiceSaved": session.practice_saved_generation,
            }))
        })?
    })
}

/// Практика целиком — то, что клиент пишет на диск как `practice.json`.
///
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_practice(handle: i64) -> *mut c_char {
    guard(|| with_session(handle, |session| to_json(&session.practice))?)
}

/// Открывает сессию с явным practice (для нового хранения с отдельным файлом).
///
/// Третий аргумент — JSON `practice.json` или `null` если его ещё нет.
/// Ленient: битая запись заменяется пустой.
/// # Safety
/// `library`, `settings`, `practice` — либо `null`, либо корректные UTF-8 строки.
#[no_mangle]
pub unsafe extern "C" fn wolfy_session_open_with_practice(
    library: *const c_char,
    settings: *const c_char,
    practice: *const c_char,
) -> i64 {
    let opened = catch_unwind(AssertUnwindSafe(|| {
        let library = unsafe { read_optional(library) };
        let settings = unsafe { read_optional(settings) };
        let practice = unsafe { read_optional(practice) };
        let session = Session::open_with_practice(
            library.as_deref(),
            settings.as_deref(),
            practice.as_deref(),
        );
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let mut guard = match SESSIONS.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.insert(handle, Arc::new(Mutex::new(session)));
        Some(handle)
    }));
    match opened {
        Ok(Some(handle)) => {
            clear_error();
            handle
        }
        Ok(None) => 0,
        Err(_) => {
            set_error("ядро не смогло открыть сессию");
            0
        }
    }
}

/// Строгое открытие с practice.
///
/// # Safety
/// `library`, `settings`, `practice` — либо `null`, либо корректные UTF-8 строки.
#[no_mangle]
pub unsafe extern "C" fn wolfy_session_open_strict_with_practice(
    library: *const c_char,
    settings: *const c_char,
    practice: *const c_char,
) -> i64 {
    let opened = catch_unwind(AssertUnwindSafe(|| {
        let library = unsafe { read_optional(library) };
        let settings = unsafe { read_optional(settings) };
        let practice = unsafe { read_optional(practice) };
        let session = match Session::try_open_with_practice(
            library.as_deref(),
            settings.as_deref(),
            practice.as_deref(),
        ) {
            Ok(s) => s,
            Err(e) => {
                set_error(&e);
                return None;
            }
        };
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        let mut guard = match SESSIONS.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.insert(handle, Arc::new(Mutex::new(session)));
        Some(handle)
    }));
    match opened {
        Ok(Some(handle)) => {
            clear_error();
            handle
        }
        Ok(None) => 0,
        Err(_) => {
            set_error("ядро не смогло открыть сессию (strict)");
            0
        }
    }
}

/// Закрывает сессию.
///
/// Удаляет handle даже если пер-сессионный мьютекс poisoned: нужно только
/// глобальный реестр, а не блокировка самой сессии.
#[no_mangle]
pub extern "C" fn wolfy_session_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        match SESSIONS.lock() {
            Ok(mut guard) => {
                guard.remove(&handle);
            }
            Err(poisoned) => {
                poisoned.into_inner().remove(&handle);
            }
        }
    }));
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    /// Читает и освобождает строку, как это сделал бы клиент.
    fn забрать(raw: *mut c_char) -> Option<String> {
        if raw.is_null() {
            return None;
        }
        // SAFETY: строка получена из функции модуля и освобождается один раз.
        let text = unsafe { CStr::from_ptr(raw) }
            .to_string_lossy()
            .into_owned();
        unsafe { wolfy_string_free(raw) };
        Some(text)
    }

    fn ошибка() -> Option<String> {
        let raw = wolfy_last_error();
        if raw.is_null() {
            return None;
        }
        // SAFETY: указатель принадлежит ядру и действителен до следующего
        // вызова из этого потока.
        Some(
            unsafe { CStr::from_ptr(raw) }
                .to_string_lossy()
                .into_owned(),
        )
    }

    #[test]
    fn версия_ядра_отдаётся() {
        let version = забрать(wolfy_version()).expect("версия есть");
        assert_eq!(version, crate::VERSION);
    }

    #[test]
    fn справочник_приходит_json_ом() {
        let json = забрать(wolfy_grammar_reference()).expect("справочник есть");
        let value: serde_json::Value = serde_json::from_str(&json).expect("это JSON");

        let articles = value["articles"].as_array().expect("список статей");
        assert!(articles.len() >= 20, "статей всего {}", articles.len());

        // Объяснение приходит от детектора, а не из отдельного текста — иначе
        // справочник разошёлся бы с тем, что читатель видит в карточке.
        let perfect = articles
            .iter()
            .find(|a| a["rule"] == "present-perfect")
            .expect("статья про Present Perfect");
        assert_eq!(perfect["formula"], "have/has + V3");
        assert!(perfect["explanation"]
            .as_str()
            .is_some_and(|s| !s.is_empty()));
        assert_eq!(perfect["topic"], "tenses");
    }

    #[test]
    fn грамматика_приходит_json_ом() {
        let text = c"She has been reading the book.";
        let json = забрать(unsafe { wolfy_explain(text.as_ptr()) }).expect("разбор есть");
        let value: serde_json::Value = serde_json::from_str(&json).expect("это JSON");

        let findings = value["findings"].as_array().expect("список разборов");
        assert!(
            findings
                .iter()
                .any(|f| f["rule"] == "present-perfect-continuous"),
            "не нашлось нужного правила: {json}"
        );
        // Смещения обязаны быть в тех же токенах, что отдаёт wolfy_tokenize:
        // по ним клиент подсвечивает страницу.
        assert!(findings[0]["end"].as_u64().unwrap_or(0) > 0);
        assert!(
            value["chunks"]
                .as_array()
                .is_some_and(|chunks| !chunks.is_empty()),
            "роли не прошли через FFI: {json}"
        );
        assert!(
            value["markers"]
                .as_array()
                .is_some_and(|markers| !markers.is_empty()),
            "маркеры не прошли через FFI: {json}"
        );
    }

    #[test]
    fn грамматика_отдаёт_часть_речи_в_контексте() {
        let text = c"I will book a room.";
        let json = забрать(unsafe { wolfy_explain(text.as_ptr()) }).expect("разбор есть");
        let value: serde_json::Value = serde_json::from_str(&json).expect("это JSON");

        let book = value["parts"]
            .as_array()
            .and_then(|parts| parts.iter().find(|part| part["token"].as_u64() == Some(4)))
            .expect("контекстная часть речи book");
        assert_eq!(book["pos"], "VERB", "неверный контекстный разбор: {json}");

        let predicate = value["chunks"]
            .as_array()
            .and_then(|chunks| {
                chunks
                    .iter()
                    .find(|chunk| chunk["head"].as_u64() == Some(4))
            })
            .expect("book — вершина сказуемого");
        assert_eq!(predicate["tint"], "VERB", "неверная роль book: {json}");
    }

    #[test]
    fn разбор_слова_приходит_json_ом() {
        let word = CString::new("children").expect("строка без нулей");
        let json = забрать(unsafe { wolfy_analyze_word(word.as_ptr()) }).expect("разбор есть");

        let value: serde_json::Value = serde_json::from_str(&json).expect("это JSON");
        assert_eq!(value["lemma"], "child");
        assert_eq!(value["form"], "irregular");
        assert!(
            ошибка().is_none(),
            "успешный вызов не должен оставлять ошибку"
        );
    }

    #[test]
    fn токенизация_отдаёт_токены_и_предложения() {
        let text = CString::new("The door opened. Evelyn stepped in.").expect("строка");
        let json = забрать(unsafe { wolfy_tokenize(text.as_ptr()) }).expect("разбор есть");

        let value: serde_json::Value = serde_json::from_str(&json).expect("это JSON");
        assert_eq!(value["sentences"].as_array().map(Vec::len), Some(2));
        assert_eq!(value["tokens"][0]["kind"], "word");
        assert_eq!(value["tokens"][0]["start"], 0);
    }

    #[test]
    fn пустой_указатель_даёт_ошибку_а_не_падение() {
        let result = unsafe { wolfy_analyze_word(std::ptr::null()) };
        assert!(result.is_null());
        assert!(
            ошибка().is_some_and(|e| e.contains("пустой указатель")),
            "ошибка не описана: {:?}",
            ошибка()
        );
    }

    #[test]
    fn несуществующая_книга_даёт_ошибку_а_не_падение() {
        let path = CString::new("нет-такого-файла.epub").expect("строка");
        let handle = unsafe { wolfy_book_open(path.as_ptr()) };

        assert_eq!(
            handle, 0,
            "открытие несуществующей книги должно провалиться"
        );
        assert!(ошибка().is_some(), "ошибка должна быть описана");
    }

    #[test]
    fn обращение_к_закрытой_книге_даёт_ошибку() {
        // Номер, который никогда не выдавался.
        let result = wolfy_book_metadata(999_999);
        assert!(result.is_null());
        assert!(
            ошибка().is_some_and(|e| e.contains("закрыта")),
            "ошибка не описана: {:?}",
            ошибка()
        );

        // Закрытие несуществующей книги безопасно и ничего не ломает.
        wolfy_book_close(999_999);
    }

    #[test]
    fn освобождение_пустого_указателя_безопасно() {
        unsafe { wolfy_string_free(std::ptr::null_mut()) };
    }

    #[test]
    fn ресурс_книги_проходит_бинарным_ffi_без_base64() {
        let handle = вставить_mock_книгу_без_задержки();
        let path = CString::new("images/lamp.jpg").expect("строка");
        let mut len = 0usize;

        let raw = unsafe { wolfy_book_resource(handle, path.as_ptr(), &mut len) };

        assert!(!raw.is_null(), "ресурс должен прийти, ошибка: {:?}", ошибка());
        assert_eq!(len, 3);
        let bytes = unsafe { std::slice::from_raw_parts(raw, len) };
        assert_eq!(bytes, &[7, 8, 9]);
        unsafe { wolfy_bytes_free(raw, len) };
        wolfy_book_close(handle);
    }

    // --- §14: FFI registry не должен глобально умирать от паники одного объекта ---

    use crate::parser::{Block, Chapter, ChapterInfo, Metadata};

    struct MockBook {
        metadata: Metadata,
        contents: Vec<ChapterInfo>,
        delay_ms: u64,
        panic: bool,
        title: String,
    }

    impl crate::parser::Book for MockBook {
        fn metadata(&self) -> &Metadata {
            &self.metadata
        }
        fn contents(&self) -> &[ChapterInfo] {
            &self.contents
        }
        fn chapter(&mut self, _index: usize) -> crate::Result<Chapter> {
            if self.panic {
                panic!("mock panic for poison test");
            }
            if self.delay_ms > 0 {
                std::thread::sleep(std::time::Duration::from_millis(self.delay_ms));
            }
            Ok(Chapter {
                title: Some(self.title.clone()),
                blocks: vec![Block::Paragraph("test".to_string())],
            })
        }
        fn resource(&mut self, _path: &str) -> crate::Result<Vec<u8>> {
            Ok(vec![7, 8, 9])
        }
    }

    fn вставить_mock_книгу(delay_ms: u64, should_panic: bool) -> i64 {
        let book: Box<dyn crate::parser::Book> = Box::new(MockBook {
            metadata: Metadata {
                title: Some("Mock".to_string()),
                author: None,
                language: None,
                cover: None,
            },
            contents: vec![ChapterInfo { title: Some("Ch1".to_string()) }],
            delay_ms,
            panic: should_panic,
            title: "MockChapter".to_string(),
        });
        let handle = super::NEXT_HANDLE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let arc = std::sync::Arc::new(std::sync::Mutex::new(book));
        match super::BOOKS.lock() {
            Ok(mut g) => {
                g.insert(handle, std::sync::Arc::clone(&arc));
            }
            Err(p) => {
                p.into_inner().insert(handle, arc);
            }
        }
        handle
    }

    fn вставить_mock_книгу_без_задержки() -> i64 {
        вставить_mock_книгу(0, false)
    }

    #[test]
    fn две_разные_книги_не_блокируют_друг_друга() {
        // Тяжёлая работа не должна держаться под глобальным реестром.
        // Проверяем детерминированно через try_lock, а не только по времени.
        let h1 = вставить_mock_книгу(300, false);
        let h2 = вставить_mock_книгу_без_задержки();

        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
        let barrier_c = std::sync::Arc::clone(&barrier);
        let handle = h1;
        let thread = std::thread::spawn(move || {
            barrier_c.wait();
            // Этот вызов держит пер-книжный мьютекс ~300 мс, но глобальный должен быть свободен.
            let _ = wolfy_book_chapter(handle, 0);
        });

        barrier.wait();
        // Даём потоку войти в chapter и заснуть под пер-книжным мьютексом.
        std::thread::sleep(std::time::Duration::from_millis(80));

        // Глобальный реестр не должен быть заблокирован.
        let registry_free = super::BOOKS.try_lock().is_ok();
        assert!(
            registry_free,
            "глобальный реестр заблокирован тяжёлой работой одной книги — должна быть Arc<Mutex> схема"
        );

        // Операция над другой книгой должна пройти быстро (<100 мс), а не ждать 300 мс.
        let start = std::time::Instant::now();
        let meta = wolfy_book_metadata(h2);
        let elapsed = start.elapsed();
        assert!(!meta.is_null(), "вторая книга должна отвечать пока первая спит");
        unsafe { wolfy_string_free(meta) };
        assert!(
            elapsed.as_millis() < 120,
            "вторая книга заблокирована глобальным мьютексом: elapsed {}ms",
            elapsed.as_millis()
        );

        thread.join().expect("поток книги не должен падать");
        wolfy_book_close(h1);
        wolfy_book_close(h2);
    }

    #[test]
    fn две_разные_книги_параллельно_быстрее_последовательно() {
        let h1 = вставить_mock_книгу(150, false);
        let h2 = вставить_mock_книгу(150, false);

        let start = std::time::Instant::now();
        let t1 = std::thread::spawn(move || {
            let _ = wolfy_book_chapter(h1, 0);
        });
        let t2 = std::thread::spawn({
            let h = h2;
            move || {
                let _ = wolfy_book_chapter(h, 0);
            }
        });
        t1.join().unwrap();
        t2.join().unwrap();
        let elapsed = start.elapsed();
        // Последовательно было бы ~300 мс, параллельно ~150 мс. Даём запас до 260 мс.
        assert!(
            elapsed.as_millis() < 260,
            "две разные книги должны работать параллельно, elapsed {}ms",
            elapsed.as_millis()
        );

        wolfy_book_close(h1);
        wolfy_book_close(h2);

        // Одна и та же книга должна сериализоваться пер-книжным мьютексом.
        let h = вставить_mock_книгу(120, false);
        let start = std::time::Instant::now();
        let t1 = std::thread::spawn(move || {
            let _ = wolfy_book_chapter(h, 0);
        });
        // Чуть подождать, чтобы t1 захватил пер-книжный мьютекс.
        std::thread::sleep(std::time::Duration::from_millis(20));
        let t2 = std::thread::spawn(move || {
            let _ = wolfy_book_chapter(h, 0);
        });
        t1.join().unwrap();
        t2.join().unwrap();
        let elapsed = start.elapsed();
        // Две последовательные операции над одной книгой ~240 мс.
        assert!(
            elapsed.as_millis() >= 200,
            "одна книга должна сериализовать доступ пер-мьютексом, elapsed {}ms",
            elapsed.as_millis()
        );
        wolfy_book_close(h);
    }

    #[test]
    fn паника_одной_книги_не_ломает_реестр_и_close_удаляет() {
        let poisoned = вставить_mock_книгу(0, true);
        let healthy = вставить_mock_книгу_без_задержки();

        // Паника внутри chapter ловится catch_unwind на FFI-границе -> null + "внутренняя ошибка ядра".
        let result = wolfy_book_chapter(poisoned, 0);
        assert!(result.is_null(), "паника должна вернуться как null");
        // Ошибка паники ставится в том же потоке, где был вызов — проверяем, что вызов не убил процесс.
        // Следующий вызов того же handle должен дать poison-ошибку, а не "реестр повреждён".
        let second = wolfy_book_metadata(poisoned);
        assert!(second.is_null());
        let err = ошибка().expect("ошибка poison должна быть");
        assert!(
            err.contains("повреждена") || err.contains("сбоя"),
            "ожидали 'книга повреждена после сбоя', получили: {err}"
        );

        // Другая книга должна продолжать работать.
        let meta = wolfy_book_metadata(healthy);
        assert!(!meta.is_null(), "здоровая книга должна работать после паники другой");
        unsafe { wolfy_string_free(meta) };

        // Сессии тоже должны работать.
        let sess = unsafe { wolfy_session_open(std::ptr::null(), std::ptr::null()) };
        assert_ne!(sess, 0, "сессии должны работать после паники книги");
        let lib = wolfy_session_library(sess);
        assert!(!lib.is_null());
        unsafe { wolfy_string_free(lib) };
        wolfy_session_close(sess);

        // Close poisoned handle должен удалить его, а не висеть.
        wolfy_book_close(poisoned);
        let after_close = wolfy_book_metadata(poisoned);
        assert!(after_close.is_null());
        let err2 = ошибка().expect("после close должна быть 'закрыта'");
        assert!(
            err2.contains("закрыта"),
            "после close ожидаем 'книга уже закрыта', получили: {err2}"
        );

        wolfy_book_close(healthy);
    }

    #[test]
    fn сессия_poison_не_ломает_другие_сессии_и_книги() {
        let s1 = unsafe { wolfy_session_open(std::ptr::null(), std::ptr::null()) };
        let s2 = unsafe { wolfy_session_open(std::ptr::null(), std::ptr::null()) };
        assert_ne!(s1, 0);
        assert_ne!(s2, 0);

        // Отравляем s1: паника внутри пер-сессионного мьютекса.
        let arc = {
            let guard = super::SESSIONS.lock().unwrap();
            std::sync::Arc::clone(guard.get(&s1).expect("s1 должен быть"))
        };
        let cloned = std::sync::Arc::clone(&arc);
        let t = std::thread::spawn(move || {
            let _guard = cloned.lock().unwrap();
            panic!("mock session panic");
        });
        let _ = t.join(); // poisoned

        // s1 теперь poisoned
        let result = wolfy_session_library(s1);
        assert!(result.is_null());
        let err = ошибка().expect("сессия poison error");
        assert!(
            err.contains("повреждена") || err.contains("сессии"),
            "ожидали 'сессия повреждена после сбоя', получили: {err}"
        );

        // s2 должна работать
        let lib = wolfy_session_library(s2);
        assert!(!lib.is_null(), "вторая сессия должна работать после poison первой");
        unsafe { wolfy_string_free(lib) };

        // Книги тоже должны работать
        let h = вставить_mock_книгу_без_задержки();
        let meta = wolfy_book_metadata(h);
        assert!(!meta.is_null(), "книги должны работать после poison сессии");
        unsafe { wolfy_string_free(meta) };
        wolfy_book_close(h);

        // close poisoned должен работать и очищать слот
        wolfy_session_close(s1);
        let after = wolfy_session_library(s1);
        assert!(after.is_null());
        assert!(ошибка().unwrap().contains("закрыта"));

        wolfy_session_close(s2);
    }

    #[test]
    fn catch_unwind_остаётся_на_границе() {
        // Прямая паника внутри with_book body должна ловиться guard(), а не убивать процесс.
        let h = вставить_mock_книгу(0, false);
        // Вызываем with_book с паникующим closure через FFI? Имитируем через chapter panic уже проверено.
        // Здесь проверим, что даже паника внутри сессии (run) не убивает.
        let s = unsafe { wolfy_session_open(std::ptr::null(), std::ptr::null()) };
        // Невалидная команда уже тестируется, но паника карточки? Симулируем через прямой вызов guard с паникой.
        let result = super::guard(|| -> Option<String> { panic!("test panic inside guard") });
        assert!(result.is_null());
        assert!(ошибка().unwrap().contains("внутренняя ошибка ядра"));

        wolfy_book_close(h);
        wolfy_session_close(s);
    }
}
