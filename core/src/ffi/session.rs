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

use crate::grammar::Exercise;
use crate::lexicon::Lexicon;
use crate::library::book::{LibraryBook, LibraryState, Shelf};
use crate::library::{merge, ops};
use crate::settings::AppSettings;
use crate::srs::drill::Drill;
use crate::srs::{chunks, drill, scheduler, training, Card};

/// Библиотека и настройки одного читателя.
#[derive(Debug, Default)]
pub struct Session {
    pub library: LibraryState,
    pub settings: AppSettings,
    /// Менялось ли с последней записи на диск.
    ///
    /// Считает ядро, а не клиент: только оно знает, изменила ли команда хоть
    /// что-нибудь. Повторное сохранение слова, которое уже в колоде, ничего
    /// не меняет — и записывать библиотеку заново из-за него незачем.
    pub library_dirty: bool,
    pub settings_dirty: bool,
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
    /// Решить, заводить ли книгу заново. Ничего не меняет.
    PlanAdd { fingerprint: String },
    AddBook {
        book: Box<LibraryBook>,
    },
    AttachFile {
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
    RemoveWord { book_id: String, lemma: String },
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
    Review {
        card_id: String,
        right: bool,
        now: i64,
        #[serde(default)]
        offset_minutes: i32,
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
    DrillFor { card_id: String },
    /// Задание по правилу, у которого карточки ещё нет.
    RuleDrill { rule: String, card_id: String },
    /// Сходятся ли собранный ответ и ожидаемый.
    SameText { assembled: String, expected: String },
    /// Как приклеить снятую страницу к уже снятым.
    AppendedPage { before: String, page: String },
    /// Когда напомнить о повторении.
    ReminderAt {
        now: i64,
        #[serde(default)]
        offset_minutes: i32,
    },
    /// Книга, к которой стоит вернуться.
    ContinueReading,
    /// Колода книги.
    Deck { book_id: String },

    SetTheme {
        theme: String,
    },
    SetFontScale {
        scale: f32,
    },
    SetLineScale {
        scale: f32,
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
    Migrate { fresh_ids: Vec<String> },
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
}

impl Session {
    /// Открывает сессию на сохранённом состоянии.
    ///
    /// Битую запись ядро не выплёвывает ошибкой, а заменяет пустой: падение
    /// на старте не оставило бы читателю ничего, а так приложение откроется —
    /// книги при этом лежат на диске и добавляются заново.
    pub fn open(library: Option<&str>, settings: Option<&str>) -> Session {
        Session {
            library: library
                .and_then(|text| serde_json::from_str(text).ok())
                .unwrap_or_default(),
            settings: settings
                .and_then(|text| serde_json::from_str(text).ok())
                .unwrap_or_default(),
            library_dirty: false,
            settings_dirty: false,
        }
    }

    /// Выполняет команду.
    pub fn run(&mut self, command: Command) -> Outcome {
        match command {
            Command::PlanAdd { fingerprint } => {
                let (plan, id) = match ops::plan_add(&self.library, &fingerprint) {
                    ops::AddPlan::Known(id) => ("known", Some(id)),
                    ops::AddPlan::Attach(id) => ("attach", Some(id)),
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
            } => {
                let intensity = self.settings.review_intensity();
                let ease = self.settings.ease();
                let next =
                    ops::update_card(&self.library, &card_id, |card| {
                        scheduler::review(card, right, intensity, ease, now)
                    });
                self.change_library(next);

                // Серия дней двигается тем же событием: ответ засчитан один
                // раз и в карточку, и в календарь.
                self.settings = self.settings.with_answer(right, now, offset_minutes);
                self.settings_dirty = true;

                let card = self
                    .library
                    .cards
                    .iter()
                    .find(|card| card.id == card_id)
                    .cloned();
                self.done(Outcome {
                    card,
                    streak: Some(self.settings.streak_days),
                    ..Outcome::default()
                })
            }

            Command::Due { now } => Outcome {
                cards: Some(scheduler::due(&self.library.cards, now)),
                ..Outcome::default()
            },

            Command::DeckStatus { kind, now } => Outcome {
                status: Some(training::status(&self.library.cards, &kind, now, exercises())),
                ..Outcome::default()
            },

            Command::TrainingQueue { kind, now } => Outcome {
                queue: Some(training::queue(&self.library.cards, &kind, now, exercises())),
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
                self.settings_dirty = true;
                self.done(Outcome::default())
            }

            Command::SetFontScale { scale } => {
                self.settings = self.settings.with_font_scale(scale);
                self.settings_dirty = true;
                self.done(Outcome::default())
            }

            Command::SetLineScale { scale } => {
                self.settings = self.settings.with_line_scale(scale);
                self.settings_dirty = true;
                self.done(Outcome::default())
            }

            Command::SetIntensity { intensity } => {
                self.settings.intensity = intensity;
                self.settings_dirty = true;
                self.done(Outcome::default())
            }

            Command::MarkDemoAdded => {
                self.settings.demo_added = true;
                self.settings_dirty = true;
                self.done(Outcome::default())
            }

            Command::ReplaceSettings { settings } => {
                self.settings = self.settings.replaced_by(&settings);
                self.settings_dirty = true;
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
                self.done(Outcome::default())
            }
        }
    }

    /// Записывает новое состояние и запоминает, изменилось ли оно.
    fn change_library(&mut self, next: LibraryState) {
        if next.revision != self.library.revision {
            self.library_dirty = true;
        }
        self.library = next;
    }

    /// Отмечает в ответе, что состояние изменилось.
    fn done(&self, outcome: Outcome) -> Outcome {
        Outcome {
            changed: self.library_dirty || self.settings_dirty,
            library_changed: self.library_dirty,
            settings_changed: self.settings_dirty,
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

#[cfg(test)]
mod tests {
    use super::*;

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
        let session = Session::open(Some("{это не json"), Some("тоже не json"));
        assert!(session.library.books.is_empty());
        assert_eq!(session.settings, AppSettings::default());
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
        session.library_dirty = false;

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
            r#"{"op":"review","cardId":"c1","right":true,"now":1700000000000,"offsetMinutes":180}"#,
        ));

        let card = outcome.card.expect("карточки нет");
        assert_eq!(card.streak, 1, "серия карточки не сдвинулась");
        assert!(card.due_at > NOW, "срок не назначен");
        // Одно событие — один ответ и в карточку, и в календарь.
        assert_eq!(outcome.streak, Some(1));
        assert_eq!(session.settings.answers, 1);
        assert_eq!(session.settings.right, 1);
        assert!(session.settings_dirty);
    }

    #[test]
    fn план_добавления_не_меняет_состояния() {
        let mut session = сессия();
        let outcome = session.run(команда(r#"{"op":"planAdd","fingerprint":"отпечаток"}"#));
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
            r#"{"op":"setIntensity","intensity":"Strong"}"#,
            r#"{"op":"markDemoAdded"}"#,
            r#"{"op":"replaceSettings","settings":{"theme":"Oled"}}"#,
            r#"{"op":"deckStatus","kind":"word","now":1}"#,
            r#"{"op":"trainingQueue","kind":"rule","now":1}"#,
            r#"{"op":"drillFor","cardId":"c1"}"#,
            r#"{"op":"ruleDrill","rule":"present-perfect","cardId":"c3"}"#,
            r#"{"op":"sameText","assembled":"she left","expected":"She left."}"#,
            r#"{"op":"review","cardId":"c1","right":true,"now":1,"offsetMinutes":180}"#,
            r#"{"op":"reminderAt","now":1,"offsetMinutes":180}"#,
            r#"{"op":"due","now":1}"#,
            r#"{"op":"appendedPage","before":"Первая","page":"Вторая"}"#,
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
}
