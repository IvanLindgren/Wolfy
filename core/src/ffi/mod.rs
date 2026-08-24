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
use std::sync::Mutex;

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
static BOOKS: Mutex<Option<HashMap<i64, Box<dyn Book>>>> = Mutex::new(None);

/// Открытые сессии — библиотека и настройки читателя. Номер вместо указателя
/// по той же причине, что у книг.
static SESSIONS: Mutex<Option<HashMap<i64, Session>>> = Mutex::new(None);

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
        let mut guard = BOOKS.lock().ok()?;
        guard.get_or_insert_with(HashMap::new).insert(handle, book);
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

/// Закрывает книгу и отпускает её файл.
#[no_mangle]
pub extern "C" fn wolfy_book_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut guard) = BOOKS.lock() {
            if let Some(books) = guard.as_mut() {
                books.remove(&handle);
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
fn with_book<T, F>(handle: i64, body: F) -> Option<T>
where
    F: FnOnce(&mut Box<dyn Book>) -> T,
{
    let mut guard = match BOOKS.lock() {
        Ok(guard) => guard,
        Err(_) => {
            set_error("реестр книг повреждён");
            return None;
        }
    };

    match guard.as_mut().and_then(|books| books.get_mut(&handle)) {
        Some(book) => Some(body(book)),
        None => {
            set_error("книга уже закрыта или не открывалась");
            None
        }
    }
}

/// Достаёт сессию из реестра и что-то с ней делает.
fn with_session<T, F>(handle: i64, body: F) -> Option<T>
where
    F: FnOnce(&mut Session) -> T,
{
    let mut guard = match SESSIONS.lock() {
        Ok(guard) => guard,
        Err(_) => {
            set_error("реестр сессий повреждён");
            return None;
        }
    };

    match guard
        .as_mut()
        .and_then(|sessions| sessions.get_mut(&handle))
    {
        Some(session) => Some(body(session)),
        None => {
            set_error("сессия уже закрыта или не открывалась");
            None
        }
    }
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
        let mut guard = SESSIONS.lock().ok()?;
        guard
            .get_or_insert_with(HashMap::new)
            .insert(handle, session);
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
        let mut guard = SESSIONS.lock().ok()?;
        guard
            .get_or_insert_with(HashMap::new)
            .insert(handle, session);
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
/// Отвечает `{"library":bool,"settings":bool}`. Считает ядро, а не клиент:
/// только оно знает, изменила ли команда хоть что-нибудь, — повторное
/// сохранение слова, которое уже в колоде, не меняет ничего.
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
/// # Safety
/// `handle` — номер, выданный [`wolfy_session_open`] и ещё не закрытый.
#[no_mangle]
pub extern "C" fn wolfy_session_saved(handle: i64, library: bool, settings: bool) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_session(handle, |session| {
            if library {
                session.library_dirty = false;
            }
            if settings {
                session.settings_dirty = false;
            }
        });
    }));
}

/// Закрывает сессию.
///
/// Несохранённое при этом теряется: записать его самостоятельно ядро не может
/// — оно не знает, где у этой платформы файлы.
#[no_mangle]
pub extern "C" fn wolfy_session_close(handle: i64) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut guard) = SESSIONS.lock() {
            if let Some(sessions) = guard.as_mut() {
                sessions.remove(&handle);
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
}
