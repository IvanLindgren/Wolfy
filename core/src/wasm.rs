//! Вторая дверь в то же ядро: `wasm-bindgen` для браузера.
//!
//! Дверь вторая, ядро одно. Всё, что здесь происходит, — это перевод строки
//! из JavaScript в ту же [`session::Command`], которую получает C-FFI, и
//! обратно. Ни одного правила грамматики, ни одной формулы расписания здесь
//! нет и быть не может: вторая реализация разошлась бы с первой через
//! полгода, и продукт превратился бы в два продукта под одним именем.
//!
//! ## Чем это отличается от `ffi/`
//!
//! 1. **Панику не поймать.** В wasm стек не разматывается, и `catch_unwind`
//!    ловить нечего. Поэтому ставится `console_error_panic_hook`, а каждая
//!    точка входа возвращает `Result`: битая книга обязана вернуться ошибкой,
//!    а не уронить вкладку.
//! 2. **Файлов нет.** Книга приходит буфером, лексикон и словарь — тоже.
//! 3. **Строки не освобождаются вручную.** `wasm-bindgen` копирует их в
//!    JavaScript сам, и `wolfy_string_free` здесь не нужен.
//!
//! Часов и генератора случайностей у ядра по-прежнему нет: `now` в
//! UTC-миллисекундах, `offsetMinutes` и свежие идентификаторы приходят
//! параметрами команды — ровно как на Android и Windows.

use wasm_bindgen::prelude::*;

use crate::dictionary::Dictionary;
use crate::ffi::dto::{
    BookDto, ChapterDto, ExercisesDto, GrammarDto, PartDto, ReferenceDto, TextDto, WordDto,
};
use crate::ffi::session::{Command, Session};
use crate::lexicon::{analyze, Lexicon};
use crate::parser::{self, Book};
use crate::tokenizer::{split, tokenize};

/// Ставит обработчик паники. Зовётся один раз при загрузке модуля.
#[wasm_bindgen(start)]
pub fn start() {
    console_error_panic_hook::set_once();
}

/// Версия ядра — веб показывает её в диагностике рядом с версией оболочки.
#[wasm_bindgen]
pub fn version() -> String {
    crate::VERSION.to_string()
}

/// Кладёт лексикон, скачанный отдельным запросом.
///
/// Отдельным, потому что внутри `.wasm` он весил бы полтора мегабайта до
/// первой буквы текста, а нужен не раньше первого тапа по слову. Отвечает
/// `false`, если лексикон уже стоит: заменять его на ходу нельзя — разбор
/// раздаёт ссылки внутрь его текста.
#[wasm_bindgen(js_name = installLexicon)]
pub fn install_lexicon(text: String) -> bool {
    Lexicon::install(text)
}

/// Стоит ли лексикон. По нему интерфейс решает, ждать ли его загрузки.
#[wasm_bindgen(js_name = lexiconReady)]
pub fn lexicon_ready() -> bool {
    Lexicon::installed()
}

/// Разбирает одно слово: начальная форма, части речи, разбор, частотность.
#[wasm_bindgen(js_name = analyzeWord)]
pub fn analyze_word(word: &str) -> Result<String, JsError> {
    let analysis = analyze(Lexicon::embedded(), word);
    json(&WordDto::from(&analysis))
}

/// Всё для карточки за один вызов — слово, предложение, грамматика и граф.
///
/// Смещения токенов — в UTF-16, текст токенов не дублируется.
#[wasm_bindgen(js_name = inspectWord)]
pub fn inspect_word(word: &str, sentence: &str) -> Result<String, JsError> {
    let dto = crate::inspect::inspect_word(word, sentence);
    json(&dto)
}

/// Разбивает текст на токены и предложения.
///
/// Позиции токенов — в единицах UTF-16, то есть ровно в тех индексах, которыми
/// оперируют строки JavaScript.
#[wasm_bindgen(js_name = tokenizeText)]
pub fn tokenize_text(text: &str) -> Result<String, JsError> {
    let tokens = tokenize(text);
    let sentences = split(&tokens);
    json(&TextDto {
        tokens: tokens.iter().map(Into::into).collect(),
        sentences: sentences.iter().map(Into::into).collect(),
    })
}

/// Разбирает грамматику предложения: находки, роли и маркеры.
///
/// На вход идёт предложение целиком, а не слово: разбор смотрит на соседей, и
/// обрывок фразы разберётся хуже, чем фраза целиком.
#[wasm_bindgen]
pub fn explain(text: &str) -> Result<String, JsError> {
    let tokens = tokenize(text);
    let lexicon = Lexicon::embedded();
    let parts = crate::tagger::tag(lexicon, &tokens);
    let findings = crate::grammar::analyze(lexicon, &tokens);
    let chunks = crate::grammar::chunks(lexicon, &tokens, &findings);
    let markers = crate::grammar::markers(lexicon, &tokens, &findings);

    json(&GrammarDto {
        parts: parts.iter().map(PartDto::from).collect(),
        findings: findings.iter().map(Into::into).collect(),
        chunks: chunks.iter().map(Into::into).collect(),
        markers: markers.iter().map(Into::into).collect(),
    })
}

/// Справочник грамматики целиком.
#[wasm_bindgen(js_name = grammarReference)]
pub fn grammar_reference() -> Result<String, JsError> {
    let articles = crate::grammar::articles(Lexicon::embedded());
    json(&ReferenceDto {
        articles: articles.iter().map(Into::into).collect(),
    })
}

/// Микро-упражнения по грамматике целиком.
#[wasm_bindgen(js_name = grammarExercises)]
pub fn grammar_exercises() -> Result<String, JsError> {
    let exercises = crate::grammar::exercises(Lexicon::embedded());
    json(&ExercisesDto {
        exercises: exercises.iter().map(Into::into).collect(),
    })
}

/// Открытая книга.
///
/// Владение отдаётся JavaScript целиком: объект живёт, пока на него есть
/// ссылка, и освобождается вызовом `free()`. Реестра номеров, как в C-FFI,
/// здесь не нужно — там он был нужен затем, что проверить чужой указатель
/// невозможно, а здесь указателей не видно вовсе.
#[wasm_bindgen]
pub struct WolfyBook {
    inner: Box<dyn Book>,
}

#[wasm_bindgen]
impl WolfyBook {
    /// Открывает книгу из буфера.
    ///
    /// `extension` отдельным аргументом, потому что имени файла у байтов нет.
    #[wasm_bindgen(js_name = open)]
    pub fn open(
        extension: &str,
        title: Option<String>,
        bytes: Vec<u8>,
    ) -> Result<WolfyBook, JsError> {
        parser::open_bytes(extension, title, bytes)
            .map(|inner| WolfyBook { inner })
            .map_err(described)
    }

    /// Собирает книгу из страниц, извлечённых в браузере: PDF через `pdf.js`
    /// и распознанные по фото страницы приходят сюда.
    #[wasm_bindgen(js_name = fromPages)]
    pub fn from_pages(title: Option<String>, pages: Vec<String>) -> Result<WolfyBook, JsError> {
        parser::from_pages(title, pages)
            .map(|inner| WolfyBook { inner })
            .map_err(described)
    }

    /// Метаданные и оглавление. Маленькие и нужны сразу.
    pub fn metadata(&self) -> Result<String, JsError> {
        json(&BookDto::new(self.inner.metadata(), self.inner.contents()))
    }

    /// Читает одну главу. Единственная тяжёлая операция — потому ядро и
    /// живёт в воркере.
    pub fn chapter(&mut self, index: usize) -> Result<String, JsError> {
        let chapter = self.inner.chapter(index).map_err(described)?;
        json(&ChapterDto::from(&chapter))
    }

    /// Читает главу вместе с токенами/предложениями — один тяжёлый переход.
    #[wasm_bindgen(js_name = preparedChapter)]
    pub fn prepared_chapter(&mut self, index: usize) -> Result<String, JsError> {
        let chapter = self.inner.chapter(index).map_err(described)?;
        let prepared = crate::prepared::prepare(&chapter);
        json(&prepared)
    }

    /// Якоря полужирного выделения: по числу на токен подготовленной главы.
    ///
    /// `Uint16Array` вместо JSON нарочно: на главу в десять тысяч токенов
    /// массив чисел в JSON весит под сорок килобайт текста, который ещё надо
    /// разобрать, а типизированный массив переезжает как есть.
    #[wasm_bindgen(js_name = chapterAnchors)]
    pub fn chapter_anchors(&mut self, index: usize) -> Result<Vec<u16>, JsError> {
        let chapter = self.inner.chapter(index).map_err(described)?;
        Ok(crate::reading::text_anchors(
            crate::lexicon::Lexicon::embedded(),
            &chapter.plain_text(),
        ))
    }

    /// Отрезок чтения: докуда честно читать за один подход.
    ///
    /// Конец подтягивается к границе предложения, поэтому слов в отрезке
    /// бывает больше заказанного — обрыв на полуфразе хуже перебора.
    #[wasm_bindgen(js_name = chapterSegment)]
    pub fn chapter_segment(
        &mut self,
        index: usize,
        from: usize,
        target_words: usize,
    ) -> Result<String, JsError> {
        let chapter = self.inner.chapter(index).map_err(described)?;
        let text = chapter.plain_text();
        let tokens = crate::tokenizer::tokenize(&text);
        let sentences = crate::tokenizer::split(&tokens);
        let segment = crate::reading::segment(&tokens, &sentences, from, target_words);
        json(&crate::ffi::dto::SegmentDto::from(segment))
    }

    /// Байты иллюстрации по пути из блока `image`.
    pub fn resource(&mut self, path: &str) -> Result<Vec<u8>, JsError> {
        self.inner.resource(path).map_err(described)
    }
}

/// Библиотека, колоды и настройки одного читателя.
///
/// Та же сессия, что на Android и Windows, и те же команды: `run` принимает
/// ровно тот JSON, который уходит в `wolfy_session_run`.
#[wasm_bindgen]
pub struct WolfySession {
    inner: Session,
}

#[wasm_bindgen]
impl WolfySession {
    /// Открывает сессию на сохранённом состоянии (lenient, legacy).
    ///
    /// Битую запись молча заменяет пустой: падение на старте не оставило бы
    /// читателю ничего, а так приложение откроется. Для нового кода, где
    /// повреждение обязано быть видимым, используйте `tryNew`/`openStrict`:
    /// `None`/пустая запись -> Default, а непустая битая -> Err. Клиент после
    /// Err не должен автоматически сохранять пустое состояние поверх повреждённого.
    /// `practice` — отдельный JSON (§6), может быть `undefined` на старых установках
    /// (тогда мигрирует из `settings`).
    #[wasm_bindgen(constructor)]
    pub fn new(
        library: Option<String>,
        settings: Option<String>,
        practice: Option<String>,
    ) -> WolfySession {
        WolfySession {
            inner: Session::open_with_practice(
                library.as_deref(),
                settings.as_deref(),
                practice.as_deref(),
            ),
        }
    }

    /// Строгое открытие: отличает «файла нет» от «файл повреждён».
    ///
    /// - `None` / `Some("")` / whitespace -> `Ok(Default)`
    /// - валидный JSON -> `Ok(Session)`
    /// - непустой битый JSON -> `Err("library corrupted: ...")` или
    ///   `Err("settings corrupted: ...")` или `Err("practice corrupted: ...")`.
    /// Используется на старте с двух-слотовой/журнальной схемой: primary valid
    /// -> primary, primary broken + backup valid -> backup, оба broken -> явная
    /// ошибка/recovery UI, а не молчаливый Default.
    #[wasm_bindgen(js_name = tryNew)]
    pub fn try_new(
        library: Option<String>,
        settings: Option<String>,
        practice: Option<String>,
    ) -> Result<WolfySession, JsError> {
        let inner = Session::try_open_with_practice(
            library.as_deref(),
            settings.as_deref(),
            practice.as_deref(),
        )
        .map_err(|e| JsError::new(&e))?;
        Ok(WolfySession { inner })
    }

    /// Алиас [`try_new`]: strict open.
    #[wasm_bindgen(js_name = openStrict)]
    pub fn open_strict(
        library: Option<String>,
        settings: Option<String>,
        practice: Option<String>,
    ) -> Result<WolfySession, JsError> {
        Self::try_new(library, settings, practice)
    }

    /// Выполняет команду. Та же команда, что уходит в `wolfy_session_run`.
    pub fn run(&mut self, command: &str) -> Result<String, JsError> {
        let command: Command = serde_json::from_str(command)
            // Имя незнакомой команды попадает в текст ошибки: без него
            // расхождение клиента и ядра ищется вслепую.
            .map_err(|e| JsError::new(&format!("ядро не поняло команду: {e}")))?;
        json(&self.inner.run(command))
    }

    /// Библиотека целиком — то, что клиент пишет в хранилище.
    pub fn library(&self) -> Result<String, JsError> {
        json(&self.inner.library)
    }

    /// Настройки целиком.
    pub fn settings(&self) -> Result<String, JsError> {
        json(&self.inner.settings)
    }

    /// Практика целиком (§6) — отдельный файл `practice.json`.
    pub fn practice(&self) -> Result<String, JsError> {
        json(&self.inner.practice)
    }

    /// Что изменилось с последней записи. Считает ядро: только оно знает,
    /// изменила ли команда хоть что-нибудь.
    pub fn dirty(&self) -> Result<String, JsError> {
        json(&serde_json::json!({
            "library": self.inner.library_dirty,
            "settings": self.inner.settings_dirty,
            "practice": self.inner.practice_dirty,
            "libraryGeneration": self.inner.library_generation,
            "settingsGeneration": self.inner.settings_generation,
            "practiceGeneration": self.inner.practice_generation,
            "librarySavedGeneration": self.inner.library_saved_generation,
            "settingsSavedGeneration": self.inner.settings_saved_generation,
            "practiceSavedGeneration": self.inner.practice_saved_generation,
        }))
    }

    /// Текущие поколения (§17).
    pub fn generations(&self) -> Result<String, JsError> {
        json(&serde_json::json!({
            "library": self.inner.library_generation,
            "settings": self.inner.settings_generation,
            "practice": self.inner.practice_generation,
            "librarySaved": self.inner.library_saved_generation,
            "settingsSaved": self.inner.settings_saved_generation,
            "practiceSaved": self.inner.practice_saved_generation,
        }))
    }

    /// Подтверждает запись (legacy, без поколений — подтверждает текущее поколение).
    pub fn saved(&mut self, library: bool, settings: bool) {
        if library {
            let g = self.inner.library_generation;
            self.inner.ack_saved(Some(g), None, None);
        }
        if settings {
            let g = self.inner.settings_generation;
            self.inner.ack_saved(None, Some(g), None);
        }
    }

    /// Подтверждает запись, включая practice по §6 (legacy).
    #[wasm_bindgen(js_name = savedWithPractice)]
    pub fn saved_with_practice(&mut self, library: bool, settings: bool, practice: bool) {
        if library {
            let g = self.inner.library_generation;
            self.inner.ack_saved(Some(g), None, None);
        }
        if settings {
            let g = self.inner.settings_generation;
            self.inner.ack_saved(None, Some(g), None);
        }
        if practice {
            let g = self.inner.practice_generation;
            self.inner.ack_saved(None, None, Some(g));
        }
    }

    /// Generation-aware подтверждение (§17): `ackSaved(N)` снимает dirty только до N.
    /// Передавайте поколения снапшотов, которые успешно записаны. `-1` = не подтверждать.
    #[wasm_bindgen(js_name = ackSaved)]
    pub fn ack_saved(&mut self, library_gen: i64, settings_gen: i64, practice_gen: i64) {
        let lib = if library_gen >= 0 {
            Some(library_gen)
        } else {
            None
        };
        let set = if settings_gen >= 0 {
            Some(settings_gen)
        } else {
            None
        };
        let prac = if practice_gen >= 0 {
            Some(practice_gen)
        } else {
            None
        };
        self.inner.ack_saved(lib, set, prac);
    }

    /// Кладёт офлайн-словарь, скачанный по согласию читателя.
    ///
    /// Целиком в память, а не по кускам: в браузере нет файла, по которому
    /// можно бегать двоичным поиском, зато распакованный словарь и так лежит
    /// в Cache Storage одним ресурсом.
    #[wasm_bindgen(js_name = installDictionary)]
    pub fn install_dictionary(&mut self, bytes: Vec<u8>) -> bool {
        self.inner.use_dictionary(bytes)
    }
}

/// Ищет статью в словаре, лежащем в памяти, без сессии.
///
/// Нужно диагностике настроек: проверить, что скачанный файл действительно
/// открывается, не трогая состояние читателя.
#[wasm_bindgen(js_name = probeDictionary)]
pub fn probe_dictionary(bytes: Vec<u8>, word: &str) -> Result<bool, JsError> {
    let mut dictionary = Dictionary::from_bytes(bytes).map_err(described)?;
    Ok(dictionary.lookup(word).map_err(described)?.is_some())
}

fn json<T: serde::Serialize>(value: &T) -> Result<String, JsError> {
    serde_json::to_string(value).map_err(|e| JsError::new(&format!("ответ не сериализуется: {e}")))
}

fn described(error: crate::CoreError) -> JsError {
    JsError::new(&error.describe())
}
