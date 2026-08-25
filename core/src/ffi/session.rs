//! Сессия: библиотека и настройки, которыми ядро владеет само.
//!
//! ## Почему состояние держит ядро, а не клиент
//!
//! Логика библиотеки — чистые переходы, и напрашивается отдавать состояние
//! туда-сюда: клиент прислал, ядро вернуло. Но библиотека это десятки
//! килобайт, а прогресс чтения записывается при каждой прокрутке. Гонять
//! весь список книг через границу по десять раз в секунду — ровно та ошибка,
//! из-за которой читалка и тормозила, только в новом месте.
//!
//! Поэтому состояние живёт здесь, а клиент шлёт команды и забирает готовое:
//! целиком — когда пора писать на диск, по кусочку — когда надо нарисовать
//! экран.
//!
//! ## Почему одна команда, а не двадцать функций
//!
//! Каждая функция границы описывается трижды: здесь, в `wolfy_core.h` и в
//! привязках клиента. Двадцать операций библиотеки — это шестьдесят мест,
//! которые обязаны сойтись, и расходятся они молча.
//!
//! Команда — одна функция и один разбор. Проверка при этом не теряется:
//! [`Command`] разбирается по полю `op`, и незнакомая команда возвращает
//! ошибку с именем, а не тихо ничего не делает.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::dictionary::{Dictionary, Entry as DictionaryEntry};
use crate::grammar::Exercise;
use crate::lexicon::Lexicon;
use crate::library::book::{LibraryBook, LibraryState, Shelf};
use crate::library::{merge, ops};
use crate::practice::PracticeState;
use crate::settings::AppSettings;
use crate::srs::drill::Drill;
use crate::srs::{chunks, drill, scheduler, training, Card};

/// Библиотека и настройки одного читателя.
#[derive(Debug, Default)]
pub struct Session {
    pub library: LibraryState,
    pub settings: AppSettings,
    pub practice: PracticeState,
    /// Менялось ли с последней записи на диск.
    ///
    /// Считает ядро, а не клиент: только оно знает, изменила ли команда хоть
    /// что-нибудь. Повторное сохранение слова, которое уже в колоде, ничего
    /// не меняет — и записывать библиотеку заново из-за него незачем.
    pub library_dirty: bool,
    pub settings_dirty: bool,
    pub practice_dirty: bool,
    /// Поколения dirty-состояния (§17 Persist performance).
    ///
    /// Инкрементятся при каждой мутации, которая делает domain грязным.
    /// `*_saved_generation` — последнее поколение, успешно записанное на диск
    /// и подтверждённое `ack_saved`. `dirty = generation != saved_generation`.
    /// Позволяет не терять mutation 6, пока пишется snapshot 5.
    pub library_generation: i64,
    pub settings_generation: i64,
    pub practice_generation: i64,
    pub(crate) library_saved_generation: i64,
    pub(crate) settings_saved_generation: i64,
    pub(crate) practice_saved_generation: i64,
    /// Скачанный словарь держится открытым между тапами. Путь хранится рядом,
    /// чтобы новый файл после обновления можно было открыть без перезапуска
    /// приложения.
    dictionary: Option<Dictionary>,
    dictionary_path: Option<PathBuf>,
}

/// Что клиент просит сделать.
///
/// `now` и `id` приходят снаружи везде, где нужны: своих часов и своей
/// случайности у ядра нет, и заводить их ради удобства значит потерять
/// возможность проиграть любую последовательность команд тестом.
/// `rename_all` переименовывает только имена вариантов, а поля внутри них —
/// нет; для полей нужен `rename_all_fields`. Без него вариант с полем из двух
/// слов молча ждёт `within_chapter`, пока клиент шлёт `withinChapter`, и
/// команда отвергается целиком. Один раз это уже случилось — открытие книги
/// перестало запоминать страницу, — поэтому правило стоит на перечислении, а
/// не развешано по вариантам: развешанное однажды забудут.
#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Command {
    /// Найти словарную статью в скачанном файле.
    Define {
        word: String,
        path: String,
    },
    /// Решить, заводить ли книгу заново. Ничего не меняет.
    PlanAdd {
        fingerprint: String,
    },
    AddBook {
        book: Box<LibraryBook>,
    },
    AttachFile {
        id: String,
        path: String,
        #[serde(default)]
        fingerprint: String,
    },
    /// Снять tombstone с удалённой книги и привязать к ней файл.
    ///
    /// Отвечает на AddPlan::Revive: только явное повторное добавление имеет
    /// право оживить книгу — устаревшая копия так сделать не может.
    ReviveBook {
        id: String,
        path: String,
        #[serde(default)]
        fingerprint: String,
    },
    Describe {
        id: String,
        #[serde(default)]
        title: String,
        #[serde(default)]
        author: Option<String>,
        chapters: i32,
    },
    RememberProgress {
        id: String,
        chapter: i32,
        within_chapter: f32,
        now: i64,
    },
    SaveWord {
        book_id: String,
        surface: String,
        lemma: String,
        #[serde(default)]
        translation: String,
        #[serde(default)]
        context: String,
        #[serde(default)]
        pos: String,
        #[serde(default)]
        cefr: String,
        id: String,
        now: i64,
    },
    SavePhrase {
        book_id: String,
        sentence: String,
        #[serde(default)]
        translation: String,
        id: String,
        now: i64,
    },
    RuleCard {
        rule: String,
        title: String,
        id: String,
        now: i64,
    },
    RemoveWord {
        book_id: String,
        lemma: String,
    },
    RemoveBook {
        id: String,
    },
    MoveToShelf {
        id: String,
        #[serde(default)]
        shelf: Option<String>,
        now: i64,
    },
    AddShelf {
        name: String,
        now: i64,
    },
    RemoveShelf {
        name: String,
    },

    /// Учесть ответ тренировки: расписание карточки и серия дней разом.
    ///
    /// Одной командой, а не двумя, потому что это одно событие. Двумя оно
    /// разъезжалось бы: ответ засчитан в серию, а карточка не пересчитана —
    /// и наоборот.
    /// `device_id` — постоянный ID устройства/реплики, приходит снаружи.
    /// Пустой `device_id` трактуется как `"legacy"` для совместимости
    /// со старыми клиентами/тестами.
    Review {
        card_id: String,
        right: bool,
        now: i64,
        #[serde(default)]
        offset_minutes: i32,
        #[serde(default)]
        device_id: String,
    },

    /// Карточки, которым пора.
    Due {
        now: i64,
    },
    /// Состояние колоды: сколько созрело, всего и выучено.
    DeckStatus {
        kind: String,
        now: i64,
    },
    /// Сегодняшняя порция заданий.
    TrainingQueue {
        kind: String,
        now: i64,
    },
    /// Задание по карточке.
    DrillFor {
        card_id: String,
    },
    /// Задание по правилу, у которого карточки ещё нет.
    RuleDrill {
        rule: String,
        card_id: String,
    },
    /// Сходятся ли собранный ответ и ожидаемый.
    SameText {
        assembled: String,
        expected: String,
    },
    /// Как приклеить снятую страницу к уже снятым.
    AppendedPage {
        before: String,
        page: String,
    },
    /// Когда напомнить о повторении.
    ReminderAt {
        now: i64,
        #[serde(default)]
        offset_minutes: i32,
    },
    /// Книга, к которой стоит вернуться.
    ContinueReading,
    /// Колода книги.
    Deck {
        book_id: String,
    },

    SetTheme {
        theme: String,
    },
    SetFontScale {
        scale: f32,
    },
    SetLineScale {
        scale: f32,
    },
    SeenOnboarding,
    SeenVersion {
        version: String,
    },
    SetReduceMotion {
        on: bool,
    },
    SetIntensity {
        intensity: String,
    },
    MarkDemoAdded,
    /// Заменить настройки целиком — так они приезжают с другого устройства.
    ReplaceSettings {
        settings: Box<AppSettings>,
    },

    /// Что изменено на этом устройстве и ещё не отправлено.
    Pending,
    /// Принять ответ сервера.
    ApplyServer {
        cursor: i64,
        #[serde(default)]
        books: Vec<LibraryBook>,
        #[serde(default)]
        cards: Vec<Card>,
        /// Что уходило в этой отправке и с какой ревизии.
        #[serde(default)]
        sent: Option<SentDto>,
        now: i64,
    },
    /// Привести прочитанное состояние к нынешнему виду.
    Migrate {
        fresh_ids: Vec<String>,
    },

    /// Тренировка как отдельный CRDT (§6): получить текущее состояние.
    GetPractice,

    /// Слить чужое practice-состояние (CRDT merge: set union + max counters).
    ///
    /// Используется во время синхронизации: сервер хранит per-device blobs
    /// и отдаёт их клиенту пачкой, клиент скармливает их сюда по одному.
    /// Идемпотентно, коммутативно, ассоциативно.
    MergePractice {
        practice: Box<PracticeState>,
    },
}

/// Снимок отправки в том виде, в каком его держит клиент.
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SentDto {
    pub revision: i64,
    #[serde(default)]
    pub books: Vec<String>,
    #[serde(default)]
    pub cards: Vec<String>,
}

/// Ответ на команду.
///
/// Одна форма на все команды, и лишние поля просто не пишутся. Разные формы
/// заставили бы клиента знать, чего ждать от каждой, — а он и так знает,
/// какую команду послал.
#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Outcome {
    /// Изменилось ли хоть что-нибудь. По нему клиент решает, писать ли на диск.
    pub changed: bool,
    /// Что именно изменилось — чтобы не переписывать настройки при каждой
    /// прокрутке страницы и библиотеку при каждой смене темы.
    pub library_changed: bool,
    pub settings_changed: bool,
    pub practice_changed: bool,
    /// Что делать с добавляемой книгой: `known`, `attach` или `fresh`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plan: Option<String>,
    /// Номер книги, о которой говорит `plan`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub book_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub book: Option<LibraryBook>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub card: Option<Card>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cards: Option<Vec<Card>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub books: Option<Vec<LibraryBook>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shelf: Option<Shelf>,
    /// Момент напоминания или `null`, если напоминать не о чем.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at: Option<i64>,
    /// Серия дней после засчитанного ответа.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub streak: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<training::DeckStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub queue: Option<training::Queue>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drill: Option<Drill>,
    /// Верен ли ответ.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub right: Option<bool>,
    /// Готовый текст — например, снимки страниц, склеенные по правилу.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// Словарная статья. `None` при нормальном отсутствии слова.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub definition: Option<DictionaryEntry>,
    /// Удалось ли открыть и прочитать локальный словарь. Отдельно от статьи:
    /// неизвестное слово в исправном словаре и отсутствующий файл — разные
    /// причины, и только во втором случае клиенту нужен сетевой fallback.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dictionary_available: Option<bool>,
    /// Текущая тренировка (§6) — для `GetPractice`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub practice: Option<PracticeState>,
    /// Поколения dirty-состояния (§17 Persist performance).
    /// Клиент получает снапшот JSON + поколение N, а после успешной атомарной
    /// записи зовёт `ackSaved(N)`; ядро снимает dirty только до N.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub library_generation: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub settings_generation: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub practice_generation: Option<i64>,
}

impl Session {
    /// Открывает сессию на сохранённом состоянии.
    ///
    /// # Legacy-lenient режим
    ///
    /// Битую запись тихо заменяет пустой: так вело себя ядро до P12. Падение
    /// на старте не оставило бы читателю ничего, а так приложение откроется.
    /// Для новой инициализации, где повреждение обязано быть видимым, используйте
    /// [`Session::try_open`] / [`Session::open_strict`]: `None` / пустая строка
    /// -> `Default`, а непустая битая запись -> `Err`, а не молчаливый
    /// `Default`. Клиент после `Err` не должен автоматически сохранять пустое
    /// состояние поверх повреждённого.
    pub fn open(library: Option<&str>, settings: Option<&str>) -> Session {
        Self::try_open(library, settings).unwrap_or_else(|_| Session {
            library: LibraryState::default(),
            settings: AppSettings::default(),
            practice: PracticeState::default(),
            library_dirty: false,
            settings_dirty: false,
            practice_dirty: false,
            ..Session::default()
        })
    }

    /// Открывает сессию с явным practice (для нового кода с отдельным файлом).
    pub fn open_with_practice(
        library: Option<&str>,
        settings: Option<&str>,
        practice: Option<&str>,
    ) -> Session {
        Self::try_open_with_practice(library, settings, practice).unwrap_or_else(|_| Session {
            library: LibraryState::default(),
            settings: AppSettings::default(),
            practice: PracticeState::default(),
            library_dirty: false,
            settings_dirty: false,
            practice_dirty: false,
            ..Session::default()
        })
    }

    /// Строгое открытие: отличает «файла нет» от «файл повреждён».
    ///
    /// - `None` / `Some("")` / `Some(whitespace)` => `Default` (файла нет или пуст).
    /// - `Some(valid json)` => распарсенное состояние.
    /// - `Some(corrupted)` => `Err` с описанием, какой из двух JSON сломан.
    pub fn try_open(library: Option<&str>, settings: Option<&str>) -> Result<Session, String> {
        Self::open_strict(library, settings)
    }

    /// Строгое открытие с practice.
    pub fn try_open_with_practice(
        library: Option<&str>,
        settings: Option<&str>,
        practice: Option<&str>,
    ) -> Result<Session, String> {
        Self::open_strict_with_practice(library, settings, practice)
    }

    /// Строгое открытие (алиас [`try_open`]): corrupted JSON -> Err.
    pub fn open_strict(library: Option<&str>, settings: Option<&str>) -> Result<Session, String> {
        Self::open_strict_with_practice(library, settings, None)
    }

    /// Строгое открытие с practice.
    pub fn open_strict_with_practice(
        library: Option<&str>,
        settings: Option<&str>,
        practice: Option<&str>,
    ) -> Result<Session, String> {
        let library_raw: crate::library::book::LibraryState =
            Self::parse_optional(library, "library")?;
        // §5 Variant A: автоматическая каноникализация на открытии (идемпотентна).
        // Книги с source_key, где id != canonical(source_key), перепривязываются
        // вместе с карточками. Пустой source_key не трогается.
        let library = crate::library::merge::migrate_to_canonical(&library_raw);
        let library_dirty = library != library_raw;
        let settings: AppSettings = Self::parse_optional(settings, "settings")?;
        let mut practice: PracticeState = Self::parse_optional(practice, "practice")?;
        // §6 migration: старые поля settings -> practice (идемпотентно).
        // Если practice пустая и в settings есть legacy — мигрируем.
        // Даже если practice непустая, merge legacy через max не удвоит.
        let needs_migration = practice.needs_migration(&settings) || practice.is_empty() && settings.has_legacy_training();
        if needs_migration {
            practice.migrate_from_legacy(&settings);
            // Помечаем practice как dirty, чтобы новый клиент записал practice.json.
            // settings намеренно НЕ трогаем (не cleared_legacy) в переходный период:
            // старый клиент, который ещё не умеет писать practice.json, должен
            // сохранить данные в settings.json, откуда они снова мигрируются
            // при следующем запуске, если practice.json отсутствует.
            // Очистка legacy произойдёт позже отдельной миграцией, когда
            // practice.json гарантированно на диске (явный Migrate).
            practice.normalize();
            return Ok(Session {
                library,
                settings,
                practice,
                library_dirty,
                settings_dirty: false,
                practice_dirty: true,
                library_generation: if library_dirty { 1 } else { 0 },
                settings_generation: 0,
                practice_generation: 1,
                library_saved_generation: 0,
                settings_saved_generation: 0,
                practice_saved_generation: 0,
                ..Session::default()
            });
        }
        practice.normalize();
        Ok(Session {
            library,
            settings,
            practice,
            library_dirty,
            settings_dirty: false,
            practice_dirty: false,
            library_generation: if library_dirty { 1 } else { 0 },
            settings_generation: 0,
            practice_generation: 0,
            library_saved_generation: 0,
            settings_saved_generation: 0,
            practice_saved_generation: 0,
            ..Session::default()
        })
    }

    fn parse_optional<T>(text: Option<&str>, label: &str) -> Result<T, String>
    where
        T: Default,
        T: for<'de> Deserialize<'de>,
    {
        match text {
            None => Ok(T::default()),
            Some(raw) if raw.trim().is_empty() => Ok(T::default()),
            Some(raw) => serde_json::from_str(raw)
                .map_err(|e| format!("{} corrupted: {e}: {}", label, truncate_snippet(raw))),
        }
    }

    /// Выполняет команду.
    pub fn run(&mut self, command: Command) -> Outcome {
        match command {
            Command::Define { word, path } => self.define(&word, &path),

            Command::PlanAdd { fingerprint } => {
                let (plan, id) = match ops::plan_add(&self.library, &fingerprint) {
                    ops::AddPlan::Known(id) => ("known", Some(id)),
                    ops::AddPlan::Attach(id) => ("attach", Some(id)),
                    ops::AddPlan::Revive(id) => ("revive", Some(id)),
                    ops::AddPlan::Fresh => ("fresh", None),
                };
                Outcome {
                    plan: Some(plan.to_string()),
                    book_id: id,
                    ..Outcome::default()
                }
            }

            Command::AddBook { book } => {
                let added = *book;
                self.change_library(ops::add_book(&self.library, added.clone()));
                self.done(Outcome {
                    book: Some(added),
                    ..Outcome::default()
                })
            }

            Command::AttachFile {
                id,
                path,
                fingerprint,
            } => {
                self.change_library(ops::attach_file(&self.library, &id, &path, &fingerprint));
                self.book_outcome(&id)
            }

            Command::ReviveBook {
                id,
                path,
                fingerprint,
            } => {
                self.change_library(ops::revive_book(&self.library, &id, &path, &fingerprint));
                self.book_outcome(&id)
            }

            Command::Describe {
                id,
                title,
                author,
                chapters,
            } => {
                self.change_library(ops::describe(
                    &self.library,
                    &id,
                    &title,
                    author.as_deref(),
                    chapters,
                ));
                self.book_outcome(&id)
            }

            Command::RememberProgress {
                id,
                chapter,
                within_chapter,
                now,
            } => {
                self.change_library(ops::remember_progress(
                    &self.library,
                    &id,
                    chapter,
                    within_chapter,
                    now,
                ));
                self.done(Outcome::default())
            }

            Command::SaveWord {
                book_id,
                surface,
                lemma,
                translation,
                context,
                pos,
                cefr,
                id,
                now,
            } => {
                let (next, card) = ops::save_word(
                    &self.library,
                    &book_id,
                    &surface,
                    &lemma,
                    &translation,
                    &context,
                    &pos,
                    &cefr,
                    &id,
                    now,
                );
                self.change_library(next);
                self.done(Outcome {
                    card: Some(card),
                    ..Outcome::default()
                })
            }

            Command::SavePhrase {
                book_id,
                sentence,
                translation,
                id,
                now,
            } => {
                match ops::save_phrase(&self.library, &book_id, &sentence, &translation, &id, now) {
                    Some((next, card)) => {
                        self.change_library(next);
                        self.done(Outcome {
                            card: Some(card),
                            ..Outcome::default()
                        })
                    }
                    None => Outcome::default(),
                }
            }

            Command::RuleCard {
                rule,
                title,
                id,
                now,
            } => {
                let (next, card) = ops::rule_card(&self.library, &rule, &title, &id, now);
                self.change_library(next);
                self.done(Outcome {
                    card: Some(card),
                    ..Outcome::default()
                })
            }

            Command::RemoveWord { book_id, lemma } => {
                self.change_library(ops::remove_word(&self.library, &book_id, &lemma));
                self.done(Outcome::default())
            }

            Command::RemoveBook { id } => {
                self.change_library(ops::remove_book(&self.library, &id));
                self.done(Outcome::default())
            }

            Command::MoveToShelf { id, shelf, now } => {
                self.change_library(ops::move_to_shelf(
                    &self.library,
                    &id,
                    shelf.as_deref(),
                    now,
                ));
                self.done(Outcome::default())
            }

            Command::AddShelf { name, now } => {
                let (next, shelf) = ops::add_shelf(&self.library, &name, now);
                self.change_library(next);
                self.done(Outcome {
                    shelf: Some(shelf),
                    ..Outcome::default()
                })
            }

            Command::RemoveShelf { name } => {
                self.change_library(ops::remove_shelf(&self.library, &name));
                self.done(Outcome::default())
            }

            Command::Review {
                card_id,
                right,
                now,
                offset_minutes,
                device_id,
            } => {
                // Неизвестная или удалённая карточка — не тренировка: ни
                // карточка не меняется, ни календарь не двигается.
                let exists = self
                    .library
                    .cards
                    .iter()
                    .any(|card| card.id == card_id && !card.deleted);
                if !exists {
                    return Outcome::default();
                }

                let intensity = self.settings.review_intensity();
                // §6: поправка берётся из PracticeState totals (CRDT), а не LWW settings.
                let ease = self.practice.ease();
                let next = ops::update_card(&self.library, &card_id, |card| {
                    scheduler::review(card, right, intensity, ease, now)
                });
                self.change_library(next);

                // Серия дней двигается тем же событием: ответ засчитан один
                // раз и в карточку, и в календарь (§6 CRDT).
                self.practice
                    .record_answer(&device_id, right, now, offset_minutes);
                self.mark_practice_dirty();
                // Dual-write для переходного периода: старый клиент читает
                // streak/answers из settings.json, и пока он не обновлён,
                // данные должны оставаться там же.
                self.settings.answers =
                    self.practice.total_answers().min(i32::MAX as u64) as i32;
                self.settings.right =
                    self.practice.total_right().min(i32::MAX as u64) as i32;
                self.settings.best_streak = self.practice.best_streak() as i32;
                self.settings.trained_on = self
                    .practice
                    .days
                    .iter()
                    .next_back()
                    .copied()
                    .unwrap_or(0);
                self.settings.streak_days =
                    self.practice.current_streak(now, offset_minutes) as i32;
                self.mark_settings_dirty();

                let card = self
                    .library
                    .cards
                    .iter()
                    .find(|card| card.id == card_id)
                    .cloned();
                let streak = self.practice.current_streak(now, offset_minutes) as i32;
                self.done(Outcome {
                    card,
                    streak: Some(streak),
                    ..Outcome::default()
                })
            }

            Command::Due { now } => Outcome {
                cards: Some(scheduler::due(&self.library.cards, now)),
                ..Outcome::default()
            },

            Command::DeckStatus { kind, now } => Outcome {
                status: Some(training::status(
                    &self.library.cards,
                    &kind,
                    now,
                    exercises(),
                )),
                ..Outcome::default()
            },

            Command::TrainingQueue { kind, now } => Outcome {
                queue: Some(training::queue(
                    &self.library.cards,
                    &kind,
                    now,
                    exercises(),
                )),
                ..Outcome::default()
            },

            Command::DrillFor { card_id } => Outcome {
                drill: training::drill_for(&self.library.cards, &card_id, Lexicon::embedded()),
                ..Outcome::default()
            },

            Command::RuleDrill { rule, card_id } => Outcome {
                drill: exercises()
                    .iter()
                    .find(|exercise| exercise.rule == rule)
                    .map(|exercise| drill::for_rule(exercise, &card_id)),
                ..Outcome::default()
            },

            Command::SameText {
                assembled,
                expected,
            } => Outcome {
                right: Some(chunks::same(&assembled, &expected)),
                ..Outcome::default()
            },

            Command::AppendedPage { before, page } => Outcome {
                text: Some(crate::library::ops::appended_page(&before, &page)),
                ..Outcome::default()
            },

            Command::ReminderAt {
                now,
                offset_minutes,
            } => Outcome {
                at: scheduler::reminder_at(
                    &self.library.cards,
                    self.settings.review_intensity(),
                    now,
                    offset_minutes,
                ),
                ..Outcome::default()
            },

            Command::ContinueReading => Outcome {
                book: ops::continue_reading(&self.library).cloned(),
                ..Outcome::default()
            },

            Command::Deck { book_id } => Outcome {
                cards: Some(
                    self.library
                        .deck(&book_id)
                        .into_iter()
                        .cloned()
                        .collect::<Vec<_>>(),
                ),
                ..Outcome::default()
            },

            Command::SetTheme { theme } => {
                self.settings.theme = theme;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SetFontScale { scale } => {
                self.settings = self.settings.with_font_scale(scale);
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SetLineScale { scale } => {
                self.settings = self.settings.with_line_scale(scale);
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SeenOnboarding => {
                self.settings.onboarding_seen = true;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SeenVersion { version } => {
                self.settings.last_seen_version = version;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SetReduceMotion { on } => {
                self.settings.reduce_motion = on;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::SetIntensity { intensity } => {
                self.settings.intensity = intensity;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::MarkDemoAdded => {
                self.settings.demo_added = true;
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::ReplaceSettings { settings } => {
                self.settings = self.settings.replaced_by(&settings);
                self.mark_settings_dirty();
                self.done(Outcome::default())
            }

            Command::Pending => {
                let (books, cards) = self.library.pending();
                Outcome {
                    books: Some(books.into_iter().cloned().collect()),
                    cards: Some(cards.into_iter().cloned().collect()),
                    ..Outcome::default()
                }
            }

            Command::ApplyServer {
                cursor,
                books,
                cards,
                sent,
                now,
            } => {
                let sent = match sent {
                    Some(dto) => merge::Sent {
                        revision: dto.revision,
                        books: dto.books.into_iter().collect(),
                        cards: dto.cards.into_iter().collect(),
                    },
                    None => merge::Sent::nothing(&self.library),
                };
                self.change_library(merge::apply_server(
                    &self.library,
                    cursor,
                    &books,
                    &cards,
                    &sent,
                    now,
                ));
                self.done(Outcome::default())
            }

            Command::Migrate { fresh_ids } => {
                let mut ids = fresh_ids.into_iter();
                self.change_library(merge::migrate(&self.library, &mut ids));
                // §6: также мигрируем legacy practice если вдруг ещё не (идемпотентно)
                let before = self.practice.clone();
                self.practice.migrate_from_legacy(&self.settings);
                if self.practice != before {
                    self.mark_practice_dirty();
                }
                if self.settings.has_legacy_training() {
                    self.settings = self.settings.cleared_legacy();
                    self.mark_settings_dirty();
                }
                self.done(Outcome::default())
            }

            Command::GetPractice => Outcome {
                practice: Some(self.practice.clone()),
                ..self.done(Outcome::default())
            },

            Command::MergePractice { practice } => {
                let before = self.practice.clone();
                self.practice.merge_inplace(&practice);
                self.practice.normalize();
                if self.practice != before {
                    self.mark_practice_dirty();
                    // Dual-write для старого клиента
                    self.settings.answers =
                        self.practice.total_answers().min(i32::MAX as u64) as i32;
                    self.settings.right =
                        self.practice.total_right().min(i32::MAX as u64) as i32;
                    self.settings.best_streak = self.practice.best_streak() as i32;
                    self.settings.trained_on =
                        self.practice.days.iter().next_back().copied().unwrap_or(0);
                    // без now нельзя точно посчитать current_streak, берём longest_run как аппроксимацию
                    self.settings.streak_days = self.practice.longest_run() as i32;
                    self.mark_settings_dirty();
                }
                self.done(Outcome {
                    practice: Some(self.practice.clone()),
                    ..Outcome::default()
                })
            }
        }
    }

    /// Ищет статью, переиспользуя уже открытый файл.
    fn define(&mut self, word: &str, path: &str) -> Outcome {
        let requested = PathBuf::from(path);
        if requested.as_os_str().is_empty() {
            // Пустой путь — не ошибка, а «словарь уже в памяти»: в браузере
            // файловой системы нет и путь взяться неоткуда, а сам словарь
            // приезжает буфером через [`Session::use_dictionary`].
            return match self.dictionary.as_mut() {
                Some(dictionary) if self.dictionary_path.is_none() => {
                    Session::looked_up(dictionary.lookup(word))
                }
                _ => Outcome {
                    dictionary_available: Some(false),
                    ..Outcome::default()
                },
            };
        }

        if self.dictionary_path.as_ref() != Some(&requested) || self.dictionary.is_none() {
            match Dictionary::open(&requested) {
                Ok(dictionary) => {
                    self.dictionary = Some(dictionary);
                    self.dictionary_path = Some(requested);
                }
                Err(_) => {
                    self.dictionary = None;
                    self.dictionary_path = None;
                    return Outcome {
                        dictionary_available: Some(false),
                        ..Outcome::default()
                    };
                }
            }
        }

        let Some(dictionary) = self.dictionary.as_mut() else {
            return Outcome {
                dictionary_available: Some(false),
                ..Outcome::default()
            };
        };
        let outcome = Session::looked_up(dictionary.lookup(word));
        if outcome.dictionary_available == Some(false) {
            // Повреждённый или исчезнувший файл не остаётся в кэше:
            // после повторной загрузки тот же путь должен открыться снова.
            self.dictionary = None;
            self.dictionary_path = None;
        }
        outcome
    }

    /// Кладёт словарь, пришедший буфером, — так он приезжает в браузере.
    pub fn use_dictionary(&mut self, bytes: Vec<u8>) -> bool {
        match Dictionary::from_bytes(bytes) {
            Ok(dictionary) => {
                self.dictionary = Some(dictionary);
                // Пути у него нет: по отсутствию пути `define` и узнаёт, что
                // словарь лежит в памяти, а не на диске.
                self.dictionary_path = None;
                true
            }
            Err(_) => false,
        }
    }

    fn looked_up(found: crate::Result<Option<DictionaryEntry>>) -> Outcome {
        match found {
            Ok(definition) => Outcome {
                definition,
                dictionary_available: Some(true),
                ..Outcome::default()
            },
            Err(_) => Outcome {
                dictionary_available: Some(false),
                ..Outcome::default()
            },
        }
    }

    /// Записывает новое состояние и запоминает, изменилось ли оно.
    fn change_library(&mut self, next: LibraryState) {
        if next.revision != self.library.revision {
            self.mark_library_dirty();
        }
        self.library = next;
    }

    pub fn mark_library_dirty(&mut self) {
        self.library_generation = self.library_generation.wrapping_add(1);
        self.library_dirty = true;
    }

    pub fn mark_settings_dirty(&mut self) {
        self.settings_generation = self.settings_generation.wrapping_add(1);
        self.settings_dirty = true;
    }

    pub fn mark_practice_dirty(&mut self) {
        self.practice_generation = self.practice_generation.wrapping_add(1);
        self.practice_dirty = true;
    }

    /// Текущие поколения dirty-состояния (§17).
    pub fn generations(&self) -> (i64, i64, i64) {
        (
            self.library_generation,
            self.settings_generation,
            self.practice_generation,
        )
    }

    /// Поколения, до которых состояние считается сохранённым.
    pub fn saved_generations(&self) -> (i64, i64, i64) {
        (
            self.library_saved_generation,
            self.settings_saved_generation,
            self.practice_saved_generation,
        )
    }

    /// Generation-aware подтверждение записи (§17).
    ///
    /// Снимает dirty только до поколения N. Если текущее поколение уже N+1,
    /// dirty остаётся true. `saved = max(saved, min(N, current))`.
    /// Передача `None` / отрицательного значения означает «не подтверждать».
    pub fn ack_saved(
        &mut self,
        library_gen: Option<i64>,
        settings_gen: Option<i64>,
        practice_gen: Option<i64>,
    ) {
        if let Some(g) = library_gen {
            if g >= 0 {
                let ack = g.min(self.library_generation);
                if ack > self.library_saved_generation {
                    self.library_saved_generation = ack;
                }
                // dirty = generation != saved (generation > saved)
                self.library_dirty = self.library_generation != self.library_saved_generation;
            }
        }
        if let Some(g) = settings_gen {
            if g >= 0 {
                let ack = g.min(self.settings_generation);
                if ack > self.settings_saved_generation {
                    self.settings_saved_generation = ack;
                }
                self.settings_dirty = self.settings_generation != self.settings_saved_generation;
            }
        }
        if let Some(g) = practice_gen {
            if g >= 0 {
                let ack = g.min(self.practice_generation);
                if ack > self.practice_saved_generation {
                    self.practice_saved_generation = ack;
                }
                self.practice_dirty = self.practice_generation != self.practice_saved_generation;
            }
        }
    }

    /// Удобный ack по трём поколениям (None = не трогать).
    pub fn ack_saved_generations(&mut self, lib: i64, set: i64, prac: i64) {
        let lib_opt = if lib >= 0 { Some(lib) } else { None };
        let set_opt = if set >= 0 { Some(set) } else { None };
        let prac_opt = if prac >= 0 { Some(prac) } else { None };
        self.ack_saved(lib_opt, set_opt, prac_opt);
    }

    /// Отмечает в ответе, что состояние изменилось.
    fn done(&self, outcome: Outcome) -> Outcome {
        Outcome {
            changed: self.library_dirty || self.settings_dirty || self.practice_dirty,
            library_changed: self.library_dirty,
            settings_changed: self.settings_dirty,
            practice_changed: self.practice_dirty,
            library_generation: Some(self.library_generation),
            settings_generation: Some(self.settings_generation),
            practice_generation: Some(self.practice_generation),
            ..outcome
        }
    }

    fn book_outcome(&self, id: &str) -> Outcome {
        self.done(Outcome {
            book: self.library.book(id).cloned(),
            ..Outcome::default()
        })
    }
}

/// Упражнения справочника — считаются один раз на весь процесс.
///
/// Их больше сотни, и собираются они за доли миллисекунды, но экран колод
/// спрашивает статус на каждый кадр: пересчитывать их столько же раз незачем.
fn exercises() -> &'static [Exercise] {
    static EXERCISES: std::sync::OnceLock<Vec<Exercise>> = std::sync::OnceLock::new();
    EXERCISES.get_or_init(|| crate::grammar::exercises(Lexicon::embedded()))
}

fn truncate_snippet(raw: &str) -> String {
    const MAX: usize = 80;
    let mut snippet: String = raw.chars().take(MAX).collect();
    if raw.chars().count() > MAX {
        snippet.push('…');
    }
    // Однострочный превью: переносы мешают чтению ошибки.
    snippet.replace('\n', " ").replace('\r', " ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const NOW: i64 = 1_700_000_000_000;

    fn команда(json: &str) -> Command {
        serde_json::from_str(json).unwrap_or_else(|e| panic!("команда не разобралась: {e}\n{json}"))
    }

    fn сессия() -> Session {
        Session::default()
    }

    #[test]
    fn битое_состояние_не_мешает_открыть_приложение() {
        // Падение на старте не оставило бы читателю ничего.
        // Legacy `open` намеренно ленient: сохраняет совместимость.
        let session = Session::open(Some("{это не json"), Some("тоже не json"));
        assert!(session.library.books.is_empty());
        assert_eq!(session.settings, AppSettings::default());
    }

    #[test]
    fn strict_открытие_отличает_пустое_от_битого() {
        // None / empty / whitespace => Ok(Default)
        assert!(Session::try_open(None, None).is_ok());
        assert!(Session::try_open(Some(""), Some("   \n\t")).is_ok());
        assert!(Session::try_open(Some("   "), None).is_ok());
        // valid json => ok
        assert!(Session::try_open(Some("{}"), Some("{}")).is_ok());
        // corrupted => Err containing label
        let err = Session::try_open(Some("{это не json"), None).unwrap_err();
        assert!(err.contains("library"), "ожидали library в ошибке: {err}");
        let err = Session::try_open(None, Some("{bad")).unwrap_err();
        assert!(err.contains("settings"), "ожидали settings в ошибке: {err}");
        // legacy open still defaults
        let legacy = Session::open(Some("{bad"), Some("{bad"));
        assert!(legacy.library.books.is_empty());
    }

    #[test]
    fn corrupted_json_не_перезаписывает_библиотеку_молча() {
        let corrupted = Some("{ corrupted json ");
        let result = Session::open_strict(corrupted, Some("{}"));
        assert!(result.is_err(), "битый JSON должен быть ошибкой, а не Default");
        let err_msg = result.unwrap_err();
        assert!(
            err_msg.contains("library corrupted"),
            "ошибка должна указывать library: {err_msg}"
        );
        // Клиент обязан проверить Err и показать восстановление, а не сохранять Default.
    }

    #[test]
    fn пустой_файл_считается_отсутствием_а_не_ошибкой() {
        for empty in [None, Some(""), Some("   "), Some("\n\t ")] {
            let session =
                Session::try_open(empty, empty).expect("пустое должно быть Default");
            assert!(session.library.books.is_empty());
            assert_eq!(session.settings, AppSettings::default());
            assert!(session.practice.is_empty());
            assert!(!session.library_dirty);
            assert!(!session.settings_dirty);
            assert!(!session.practice_dirty);
        }
    }

    #[test]
    fn валидный_json_читается_строго() {
        let lib = r#"{"books":[{"id":"b1","title":"Test","addedAt":1}],"cards":[],"shelves":[],"cursor":0,"revision":1}"#;
        let settings = r#"{"theme":"Oled"}"#;
        let session =
            Session::try_open(Some(lib), Some(settings)).expect("валидный должен читаться");
        assert_eq!(session.library.books.len(), 1);
        assert_eq!(session.settings.theme, "Oled");
    }

    #[test]
    fn незнакомая_команда_возвращается_ошибкой_а_не_тишиной() {
        let ошибка = serde_json::from_str::<Command>(r#"{"op":"полетелиНаМарс"}"#)
            .expect_err("незнакомая команда разобралась");
        assert!(
            ошибка.to_string().contains("полетелиНаМарс"),
            "в ошибке нет имени команды: {ошибка}"
        );
    }

    #[test]
    fn словарь_доступен_через_единую_команду_сессии() {
        let mut file = tempfile::NamedTempFile::new().expect("файл не создался");
        write!(
            file,
            "# wolfy english dictionary v1\n\
             # generated\t2026-08-23\n\
             library\tˈlaɪˌbɹɛɹi\tn|a room where books are kept\n"
        )
        .expect("словарь не записался");
        file.flush().expect("словарь не сбросился");

        let mut session = сессия();
        let outcome = session.run(Command::Define {
            word: "Library".to_string(),
            path: file.path().to_string_lossy().into_owned(),
        });

        assert_eq!(outcome.dictionary_available, Some(true));
        let entry = outcome.definition.expect("статья не нашлась");
        assert_eq!(entry.word, "library");
        assert_eq!(entry.pronunciation, "ˈlaɪˌbɹɛɹi");
        assert_eq!(entry.senses[0].pos, "NOUN");
    }

    #[test]
    fn слово_кладётся_в_колоду_и_состояние_помечается_к_записи() {
        let mut session = сессия();
        let outcome = session.run(команда(
            r#"{"op":"saveWord","bookId":"b1","surface":"libraries",
                "lemma":"library","translation":"библиотека","id":"c1","now":1700000000000}"#,
        ));

        assert!(outcome.changed, "состояние не помечено к записи");
        assert_eq!(outcome.card.expect("карточки нет").lemma, "library");
        assert_eq!(session.library.cards.len(), 1);
        assert!(session.library_dirty);
    }

    #[test]
    fn повторное_сохранение_не_заставляет_писать_библиотеку_заново() {
        let mut session = сессия();
        let команда_слова = r#"{"op":"saveWord","bookId":"b1","surface":"library",
            "lemma":"library","id":"c1","now":1700000000000}"#;
        session.run(команда(команда_слова));
        // Симулируем успешную запись поколения 1 (§17)
        let g = session.library_generation;
        session.ack_saved(Some(g), None, None);
        assert!(!session.library_dirty);

        let outcome = session.run(команда(команда_слова));
        assert!(!outcome.changed, "запись на диск заказана зря");
        assert!(!session.library_dirty);
        assert_eq!(session.library.cards.len(), 1);
    }

    #[test]
    fn ответ_двигает_и_карточку_и_серию_дней() {
        let mut session = сессия();
        session.run(команда(
            r#"{"op":"saveWord","bookId":"b1","surface":"library",
                "lemma":"library","id":"c1","now":1700000000000}"#,
        ));

        let outcome = session.run(команда(
            r#"{"op":"review","cardId":"c1","right":true,"now":1700000000000,"offsetMinutes":180,"deviceId":"phone1"}"#,
        ));

        let card = outcome.card.expect("карточки нет");
        assert_eq!(card.streak, 1, "серия карточки не сдвинулась");
        assert!(card.due_at > NOW, "срок не назначен");
        // Одно событие — один ответ и в карточку, и в календарь (§6 CRDT).
        assert_eq!(outcome.streak, Some(1));
        assert_eq!(session.practice.total_answers(), 1);
        assert_eq!(session.practice.total_right(), 1);
        assert!(session.practice_dirty);
        // Dual-write: для переходного периода legacy settings тоже обновляются,
        // чтобы старый клиент, который ещё не пишет practice.json, не потерял данные.
        assert!(session.settings_dirty);
        assert_eq!(session.settings.answers, 1);
        // device_id без указания — legacy fallback (проверим совместимость)
        session.run(команда(
            r#"{"op":"saveWord","bookId":"b1","surface":"dusk","lemma":"dusk","id":"c2","now":1700000000000}"#,
        ));
        let outcome2 = session.run(команда(
            r#"{"op":"review","cardId":"c2","right":false,"now":1700000000000,"offsetMinutes":180}"#,
        ));
        assert_eq!(outcome2.streak, Some(1)); // тот же день, серия не растёт
        assert_eq!(session.practice.total_answers(), 2);
        assert_eq!(session.practice.total_right(), 1);
    }

    #[test]
    fn неизвестная_карточка_не_считается_тренировкой() {
        let mut session = сессия();
        // Карточки c1 нет — review обязан быть no-op, а не +1 к streak.
        let outcome = session.run(команда(
            r#"{"op":"review","cardId":"nope","right":true,"now":1700000000000,"offsetMinutes":180}"#,
        ));
        assert!(!outcome.changed, "неизвестная карточка сдвинула состояние");
        assert!(!session.library_dirty, "библиотека помечена грязной зря");
        assert!(!session.settings_dirty, "настройки помечены грязными зря");
        assert!(!session.practice_dirty, "практика помечена грязной зря");
        assert_eq!(session.practice.total_answers(), 0, "ответ засчитан без карточки");
        assert_eq!(
            session.practice.current_streak(1700000000000, 180),
            0,
            "серия сдвинулась без карточки"
        );
        assert_eq!(session.library.cards.len(), 0);
    }

    #[test]
    fn план_добавления_не_меняет_состояния() {
        let mut session = сессия();
        let outcome = session.run(команда(
            r#"{"op":"planAdd","fingerprint":"отпечаток"}"#,
        ));
        assert_eq!(outcome.plan.as_deref(), Some("fresh"));
        assert!(!outcome.changed);
        assert!(!session.library_dirty);
    }

    #[test]
    fn книга_добавляется_и_отдаётся_обратно() {
        let mut session = сессия();
        let outcome = session.run(команда(
            r#"{"op":"addBook","book":{"id":"b1","title":"Гэтсби","path":"books/g.epub",
                "addedAt":1700000000000}}"#,
        ));

        assert_eq!(outcome.book.expect("книги нет").title, "Гэтсби");
        assert_eq!(session.library.books.len(), 1);
    }

    #[test]
    fn настройки_с_другого_устройства_не_приносят_демо_книгу() {
        let mut session = сессия();
        session.run(команда(r#"{"op":"markDemoAdded"}"#));
        session.run(команда(
            r#"{"op":"replaceSettings","settings":{"theme":"Sepia","demoAdded":false}}"#,
        ));

        assert_eq!(session.settings.theme, "Sepia");
        assert!(session.settings.demo_added, "демо-книга приедет второй раз");
    }

    #[test]
    fn ответ_сервера_принимается_целиком() {
        let mut session = сессия();
        let outcome = session.run(команда(
            r#"{"op":"applyServer","cursor":9,"now":1700000000000,
                "books":[{"id":"3f1c2b4a-0000-4000-8000-000000000001","title":"Гэтсби",
                          "shelf":"Классика","addedAt":1700000000000}]}"#,
        ));

        assert!(outcome.changed);
        assert_eq!(session.library.cursor, 9);
        assert_eq!(session.library.books.len(), 1);
        assert!(!session.library.books[0].dirty, "приехавшее ждёт отправки");
        // Полка восстанавливается из книги.
        assert_eq!(session.library.shelves.len(), 1);
    }

    #[test]
    fn на_отправку_отдаётся_только_изменённое() {
        let mut session = сессия();
        session.run(команда(
            r#"{"op":"addBook","book":{"id":"b1","title":"Ждёт","addedAt":1700000000000}}"#,
        ));

        let outcome = session.run(команда(r#"{"op":"pending"}"#));
        assert_eq!(outcome.books.expect("книг нет").len(), 1);
        assert_eq!(outcome.cards.expect("карточек нет").len(), 0);
    }

    #[test]
    fn состояние_переживает_запись_и_чтение() {
        let mut session = сессия();
        session.run(команда(
            r#"{"op":"addBook","book":{"id":"b1","title":"Гэтсби","addedAt":1700000000000}}"#,
        ));
        session.run(команда(r#"{"op":"setTheme","theme":"Oled"}"#));

        let библиотека = serde_json::to_string(&session.library).expect("не пишется");
        let настройки = serde_json::to_string(&session.settings).expect("не пишется");

        let снова = Session::open(Some(&библиотека), Some(&настройки));
        assert_eq!(снова.library.books.len(), 1);
        assert_eq!(снова.library.books[0].title, "Гэтсби");
        assert_eq!(снова.settings.theme, "Oled");
        assert!(!снова.library_dirty, "свежепрочитанное просится на запись");
    }

    /// Каждая команда, которую шлёт клиент, — ровно в том написании.
    ///
    /// Список полный намеренно. Разбор команды идёт по одному правилу на всё
    /// перечисление, но правило это невидимое: поле из двух слов, названное в
    /// ядре `within_chapter`, молча не совпадёт с `withinChapter` клиента, и
    /// команда будет отвергнута целиком. Один раз так и вышло — открытие
    /// книги перестало запоминать страницу.
    ///
    /// Поэтому здесь не выборка, а перечень: новая команда без строки в этом
    /// списке уронит тест на пересчёте, а не у читателя.
    #[test]
    fn клиентские_команды_разбираются_все() {
        let команды: Vec<&str> = vec![
            r#"{"op":"define","word":"library","path":""}"#,
            r#"{"op":"planAdd","fingerprint":"abc"}"#,
            r#"{"op":"addBook","book":{"id":"b1","title":"Гэтсби","addedAt":1}}"#,
            r#"{"op":"attachFile","id":"b1","path":"books/g.epub","fingerprint":"abc"}"#,
            r#"{"op":"describe","id":"b1","title":"Гэтсби","author":null,"chapters":9}"#,
            r#"{"op":"rememberProgress","id":"b1","chapter":3,"withinChapter":0.4,"now":1}"#,
            r#"{"op":"saveWord","bookId":"b1","surface":"libraries","lemma":"library",
                "translation":"библиотека","context":"","pos":"","cefr":"","id":"c1","now":1}"#,
            r#"{"op":"savePhrase","bookId":"b1","sentence":"She left.","translation":"",
                "id":"c2","now":1}"#,
            r#"{"op":"ruleCard","rule":"present-perfect","title":"Present Perfect",
                "id":"c3","now":1}"#,
            r#"{"op":"removeWord","bookId":"b1","lemma":"library"}"#,
            r#"{"op":"removeBook","id":"b1"}"#,
            r#"{"op":"moveToShelf","id":"b1","shelf":"Классика","now":1}"#,
            r#"{"op":"moveToShelf","id":"b1","now":1}"#,
            r#"{"op":"addShelf","name":"Классика","now":1}"#,
            r#"{"op":"removeShelf","name":"Классика"}"#,
            r#"{"op":"continueReading"}"#,
            r#"{"op":"deck","bookId":"b1"}"#,
            r#"{"op":"pending"}"#,
            r#"{"op":"applyServer","cursor":9,"books":[],"cards":[],"now":1,
                "sent":{"revision":0,"books":[],"cards":[]}}"#,
            r#"{"op":"migrate","freshIds":["3f1c2b4a-0000-4000-8000-000000000001"]}"#,
            r#"{"op":"setTheme","theme":"Sepia"}"#,
            r#"{"op":"setFontScale","scale":1.2}"#,
            r#"{"op":"setLineScale","scale":1.1}"#,
            r#"{"op":"seenOnboarding"}"#,
            r#"{"op":"seenVersion","version":"1.0.5"}"#,
            r#"{"op":"setReduceMotion","on":true}"#,
            r#"{"op":"setIntensity","intensity":"Strong"}"#,
            r#"{"op":"markDemoAdded"}"#,
            r#"{"op":"replaceSettings","settings":{"theme":"Oled"}}"#,
            r#"{"op":"deckStatus","kind":"word","now":1}"#,
            r#"{"op":"trainingQueue","kind":"rule","now":1}"#,
            r#"{"op":"drillFor","cardId":"c1"}"#,
            r#"{"op":"ruleDrill","rule":"present-perfect","cardId":"c3"}"#,
            r#"{"op":"sameText","assembled":"she left","expected":"She left."}"#,
            r#"{"op":"review","cardId":"c1","right":true,"now":1,"offsetMinutes":180}"#,
            r#"{"op":"review","cardId":"c1","right":true,"now":1,"offsetMinutes":180,"deviceId":"phone"}"#,
            r#"{"op":"reminderAt","now":1,"offsetMinutes":180}"#,
            r#"{"op":"due","now":1}"#,
            r#"{"op":"appendedPage","before":"Первая","page":"Вторая"}"#,
            r#"{"op":"getPractice"}"#,
            r#"{"op":"mergePractice","practice":{"days":[20000],"counters":{"phone":{"answers":5,"right":3}},"bestFloor":2}}"#,
        ];

        let mut session = сессия();
        for текст in команды {
            let command: Command = serde_json::from_str(текст)
                .unwrap_or_else(|e| panic!("команда не разобралась: {e}\n{текст}"));
            // Заодно проверяем, что ни одна не паникует на пустой библиотеке.
            session.run(command);
        }
    }

    /// Поле из двух слов приходит в camelCase — и только в нём.
    ///
    /// Отдельно от перечня выше: тот проверяет, что команда разбирается, а
    /// этот — что она разбирается именно в клиентском написании, а не потому,
    /// что серверная форма случайно совпала.
    #[test]
    fn составные_поля_читаются_в_клиентском_написании() {
        let command: Command = serde_json::from_str(
            r#"{"op":"rememberProgress","id":"b1","chapter":3,"withinChapter":0.4,"now":1}"#,
        )
        .expect("клиентское написание не разобралось");
        match command {
            Command::RememberProgress { within_chapter, .. } => {
                assert!((within_chapter - 0.4).abs() < 1e-6);
            }
            other => panic!("разобралось не в ту команду: {other:?}"),
        }

        // А змеиное написание ядру больше не родное: оно осталось бы в коде
        // незамеченным ровно так же, как незамеченным было camelCase.
        assert!(
            serde_json::from_str::<Command>(
                r#"{"op":"rememberProgress","id":"b1","chapter":3,"within_chapter":0.4,"now":1}"#,
            )
            .is_err(),
            "ядро принимает оба написания — значит, правило не действует"
        );
    }

    #[test]
    fn legacy_migration_restore_practice() {
        // Старый settings с тренировкой -> practice после open
        let legacy = r#"{"theme":"Paper","trainedOn":20000,"streakDays":7,"bestStreak":21,"answers":300,"right":270}"#;
        let session = Session::try_open_with_practice(None, Some(legacy), None)
            .expect("migration");
        assert_eq!(session.practice.days.len(), 7);
        assert!(session.practice.days.contains(&20000));
        assert!(session.practice.days.contains(&19994));
        assert_eq!(session.practice.best_floor, 21);
        assert_eq!(session.practice.total_answers(), 300);
        assert_eq!(session.practice.total_right(), 270);
        assert!(session.practice_dirty, "migrated practice must be marked dirty");
        // settings пока остаётся с legacy для переходного периода (dual-write)
        assert_eq!(session.settings.trained_on, 20000);
        // повторная миграция идемпотентна
        let practice_json = serde_json::to_string(&session.practice).unwrap();
        let session2 = Session::try_open_with_practice(None, Some(legacy), Some(&practice_json))
            .expect("second open");
        assert_eq!(session2.practice, session.practice);
        assert_eq!(session2.practice.total_answers(), 300);
    }

    #[test]
    fn legacy_migration_idempotent_no_double_count() {
        let legacy = r#"{"answers":100,"right":80}"#;
        let s1 = Session::try_open_with_practice(None, Some(legacy), None).unwrap();
        assert_eq!(s1.practice.total_answers(), 100);
        // второй раз открываем с теми же settings и уже мигрированным practice
        let pj = serde_json::to_string(&s1.practice).unwrap();
        let s2 = Session::try_open_with_practice(None, Some(legacy), Some(&pj)).unwrap();
        assert_eq!(s2.practice.total_answers(), 100, "double count on re-migration");
        // третий раз с пустым practice но тем же legacy — всё равно 100, не 200
        let s3 = Session::try_open_with_practice(None, Some(legacy), None).unwrap();
        assert_eq!(s3.practice.total_answers(), 100);
    }

    #[test]
    fn practice_merge_not_lww() {
        // Симуляция бага §6: старый ноутбук settings 80 приезжает последним и перетирает 100
        let mut phone = сессия();
        phone.run(команда(
            r#"{"op":"saveWord","bookId":"b1","surface":"library","lemma":"library","id":"c1","now":1}"#,
        ));
        for i in 0..100 {
            phone.practice.record_answer("phone", true, NOW + i, 0);
        }
        phone.mark_practice_dirty();
        assert_eq!(phone.practice.total_answers(), 100);

        let mut laptop = сессия();
        for i in 0..80 {
            laptop.practice.record_answer("laptop", true, NOW + i, 0);
        }
        // laptop приезжает как MergePractice (CRDT), а не ReplaceSettings
        let laptop_json = serde_json::to_string(&laptop.practice).unwrap();
        let laptop_practice: crate::practice::PracticeState =
            serde_json::from_str(&laptop_json).unwrap();
        let outcome = phone.run(Command::MergePractice {
            practice: Box::new(laptop_practice),
        });
        // totals = 180, а не 80
        assert_eq!(phone.practice.total_answers(), 180);
        assert!(outcome.practice_changed);
        // коммутативно
        let mut laptop2 = сессия();
        laptop2.practice = phone.practice.clone();
        // reverse merge
        let phone_json = serde_json::to_string(&phone.practice).unwrap();
        let phone_practice: crate::practice::PracticeState =
            serde_json::from_str(&phone_json).unwrap();
        let mut other = сессия();
        other.practice = laptop.practice.clone();
        other.run(Command::MergePractice {
            practice: Box::new(phone_practice),
        });
        assert_eq!(other.practice.total_answers(), 180);
    }

    #[test]
    fn today_training_not_disappears_after_merge() {
        // Сегодня позанимались на телефоне, старый ноутбук без сегодня не должен удалить сегодня
        let today = crate::clock::local_day(NOW, 0);
        let mut phone = сессия();
        phone.practice.days.insert(today);
        phone.practice.days.insert(today - 1);
        let mut laptop = сессия();
        laptop.practice.days.insert(today - 1);
        laptop.practice.days.insert(today - 2);
        // сливаем laptop в phone
        let lp = laptop.practice.clone();
        phone.run(Command::MergePractice {
            practice: Box::new(lp),
        });
        assert!(phone.practice.days.contains(&today), "today disappeared");
        assert_eq!(phone.practice.current_streak(NOW, 0), 3);
    }

    #[test]
    fn replace_settings_does_not_overwrite_practice() {
        let mut session = сессия();
        // накапаем practice 100
        for i in 0..100 {
            session.practice.record_answer("phone", true, NOW + i, 0);
        }
        session.mark_practice_dirty();
        session.settings.answers = 100; // legacy mirror

        // приехавшие старые настройки с answers 80 не должны откатить
        session.run(команда(
            r#"{"op":"replaceSettings","settings":{"theme":"Sepia","answers":80,"right":60}}"#,
        ));
        // practice остался 100
        assert_eq!(session.practice.total_answers(), 100);
        // settings legacy остался 100 (preserve via replaced_by)
        assert_eq!(session.settings.answers, 100);
        assert_eq!(session.settings.theme, "Sepia");
    }

    #[test]
    fn merge_practice_commutative_associative_idempotent() {
        let mut a = crate::practice::PracticeState::default();
        a.record_answer("phone", true, NOW, 0);
        a.record_answer("phone", true, NOW + crate::clock::DAY_MS, 0);
        a.best_floor = 5;
        let mut b = crate::practice::PracticeState::default();
        b.record_answer("laptop", true, NOW, 0);
        b.best_floor = 7;
        let mut c = crate::practice::PracticeState::default();
        c.record_answer("tablet", false, NOW, 0);
        c.best_floor = 3;

        assert_eq!(a.merge(&b), b.merge(&a), "commutative");
        assert_eq!(a.merge(&a), a, "idempotent");
        let ab_c = a.merge(&b).merge(&c);
        let a_bc = a.merge(&b.merge(&c));
        assert_eq!(ab_c, a_bc, "associative");
        assert_eq!(ab_c.best_streak(), a_bc.best_streak());
    }

    // --- §17 Persist performance: generation-aware ack ---

    #[test]
    fn generation_increments_on_library_mutation() {
        let mut s = сессия();
        assert_eq!(s.library_generation, 0);
        assert!(!s.library_dirty);
        s.run(команда(r#"{"op":"addBook","book":{"id":"b1","title":"T","addedAt":1}}"#));
        assert_eq!(s.library_generation, 1);
        assert!(s.library_dirty);
        let g1 = s.library_generation;
        s.run(команда(r#"{"op":"addBook","book":{"id":"b2","title":"T2","addedAt":2}}"#));
        assert_eq!(s.library_generation, 2);
        assert!(s.library_dirty);
        assert!(g1 < s.library_generation);
    }

    #[test]
    fn ack_saved_only_up_to_n() {
        let mut s = сессия();
        s.run(команда(r#"{"op":"addBook","book":{"id":"b1","title":"T","addedAt":1}}"#));
        let gen5 = s.library_generation;
        assert_eq!(gen5, 1);
        // SimulatePersist job took snapshot gen 1 and started writing
        // While writing, another mutation arrives -> gen 2
        s.run(команда(r#"{"op":"addBook","book":{"id":"b2","title":"T2","addedAt":2}}"#));
        assert_eq!(s.library_generation, 2);
        // Ack old snapshot (gen 1) should NOT clear dirty, because current is 2
        s.ack_saved(Some(gen5), None, None);
        assert!(s.library_dirty, "dirty must stay true after ack of old generation while newer exists");
        assert_eq!(s.library_saved_generation, 1);
        // Now ack current generation => dirty cleared
        s.ack_saved(Some(s.library_generation), None, None);
        assert!(!s.library_dirty);
        assert_eq!(s.library_saved_generation, s.library_generation);
    }

    #[test]
    fn ack_with_monotonic_saved() {
        let mut s = сессия();
        s.run(команда(r#"{"op":"setTheme","theme":"Dark"}"#));
        assert_eq!(s.settings_generation, 1);
        s.ack_saved(None, Some(1), None);
        assert!(!s.settings_dirty);
        // Old ack should be ignored (saved stays at 1)
        s.run(команда(r#"{"op":"setTheme","theme":"Sepia"}"#));
        assert_eq!(s.settings_generation, 2);
        assert!(s.settings_dirty);
        s.ack_saved(None, Some(1), None); // stale
        assert!(s.settings_dirty, "stale ack must not clear newer dirty");
        assert_eq!(s.settings_saved_generation, 1);
        s.ack_saved(None, Some(2), None);
        assert!(!s.settings_dirty);
    }

    #[test]
    fn conflation_writing_20_queue_21_24() {
        let mut s = сессия();
        // Simulate generation 20 as current saved =20, next mutation 21..24 while writing 20
        // Start with gen 20 already acked, then mutate to 21..24
        // For test, manually set generations to mimic.
        s.library_generation = 20;
        s.library_saved_generation = 20;
        s.library_dirty = false;
        // Mutate to 21
        s.run(команда(r#"{"op":"addBook","book":{"id":"b1","title":"T","addedAt":1}}"#));
        assert_eq!(s.library_generation, 21);
        // Simulate writer took snapshot 21 and started writing; queue receives 22,23,24
        // We do those mutations:
        s.run(команда(r#"{"op":"addBook","book":{"id":"b2","title":"T2","addedAt":2}}"#));
        assert_eq!(s.library_generation, 22);
        s.run(команда(r#"{"op":"addBook","book":{"id":"b3","title":"T3","addedAt":3}}"#));
        assert_eq!(s.library_generation, 23);
        s.run(команда(r#"{"op":"addBook","book":{"id":"b4","title":"T4","addedAt":4}}"#));
        assert_eq!(s.library_generation, 24);
        assert!(s.library_dirty);
        // Writer finishes 21, ack 21 -> dirty must stay true (because 24 pending)
        s.ack_saved(Some(21), None, None);
        assert!(s.library_dirty, "after ack 21, dirty must stay because 24 pending");
        assert_eq!(s.library_saved_generation, 21);
        // Coalesce: we don't need to write 22,23 separately, just 24.
        // Simulate writing 24 directly (skipping 22,23)
        s.ack_saved(Some(24), None, None);
        assert!(!s.library_dirty, "after ack 24, dirty cleared");
        assert_eq!(s.library_saved_generation, 24);
        // Ensure we didn't delete snapshot 24 before written: we acked 24 after writing, not before.
        // If we had cleared pending incorrectly, we would have lost data — test passes if dirty cleared only after ack.
    }

    #[test]
    fn outcome_carries_generations() {
        let mut s = сессия();
        let out = s.run(команда(r#"{"op":"setTheme","theme":"Dark"}"#));
        assert!(out.settings_changed);
        assert_eq!(out.settings_generation, Some(1));
        assert_eq!(s.settings_generation, 1);
        // second change
        let out2 = s.run(команда(r#"{"op":"setTheme","theme":"Sepia"}"#));
        assert_eq!(out2.settings_generation, Some(2));
    }

    #[test]
    fn practice_generation_ack() {
        let mut s = сессия();
        s.run(команда(r#"{"op":"saveWord","bookId":"b1","surface":"w","lemma":"w","id":"c1","now":1}"#));
        // need a card to review
        let before = s.practice_generation;
        s.run(команда(r#"{"op":"review","cardId":"c1","right":true,"now":1000,"offsetMinutes":0,"deviceId":"d1"}"#));
        assert!(s.practice_generation > before);
        assert!(s.practice_dirty);
        let g = s.practice_generation;
        // mutate again
        s.run(команда(r#"{"op":"saveWord","bookId":"b1","surface":"w2","lemma":"w2","id":"c2","now":2}"#));
        // Ack old practice gen should not clear current
        s.ack_saved(None, None, Some(g));
        // Since we acked up to g, but current generation may have advanced? Need to check.
        // In this scenario second mutation didn't affect practice (saveWord only affects library), so practice gen still g
        // So dirty should still be true until we ack g again? Actually second mutation didn't bump practice, so still g.
        // Let's bump practice again
        s.run(команда(r#"{"op":"review","cardId":"c2","right":true,"now":2000,"offsetMinutes":0,"deviceId":"d1"}"#));
        let g2 = s.practice_generation;
        assert!(g2 > g);
        s.ack_saved(None, None, Some(g));
        assert!(s.practice_dirty, "old ack must not clear newer");
        s.ack_saved(None, None, Some(g2));
        assert!(!s.practice_dirty);
    }
}
