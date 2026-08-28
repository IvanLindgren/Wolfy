//! Настройки приложения.
//!
//! Здесь только сами значения и правила перехода между ними: где эти
//! настройки лежат и как попадают на диск — дело клиента. Причина обычная для
//! ядра: серия дней, доля верных ответов и слияние с приехавшими с другого
//! устройства настройками обязаны считаться одинаково на телефоне и на
//! настольной машине, а «где файл» на каждой платформе своё.

use crate::clock::local_day;
use crate::srs::{scheduler, Intensity};
use serde::{Deserialize, Serialize};

/// Тема по умолчанию.
///
/// Имя, а не номер: номер меняется при добавлении новой темы посередине
/// списка, и у читателя, выбравшего сепию, однажды окажется чёрный экран без
/// всякого его участия.
///
/// Какие темы вообще бывают, ядро не знает и знать не должно — это набор
/// красок, а не правило. Незнакомое имя разбирает клиент.
pub const DEFAULT_THEME: &str = "Paper";

/// Пределы множителя размера шрифта читалки.
const FONT_SCALE: (f32, f32) = (0.8, 1.6);

/// Пределы множителя межстрочного интервала.
const LINE_SCALE: (f32, f32) = (0.9, 1.5);

/// Пределы темпа ведущей строки, слов в минуту.
///
/// Ниже сотни ведущая строка тормозит даже медленного читателя и начинает
/// раздражать; выше четырёхсот она обгоняет понимание и превращается в
/// мелькание. Ноль — «выключено» и в пределы не входит.
const PACER_WPM: (i32, i32) = (100, 400);

/// Пределы отрезка чтения в словах.
///
/// Сорок слов — примерно абзац, две тысячи — минут десять чтения. Меньше
/// сорока подход перестаёт быть подходом, больше двух тысяч — перестаёт быть
/// обозримым, а обозримость и есть весь смысл.
const SEGMENT_WORDS: (i32, i32) = (40, 2000);

/// Настройки приложения.
///
/// Имена полей и их запись в JSON совпадают с тем, что писал клиент на
/// Kotlin: настройки уже лежат на устройствах, и переезд логики в ядро не
/// повод просить читателя выбрать тему заново.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    #[serde(default = "default_theme")]
    pub theme: String,
    /// Множитель размера шрифта читалки.
    ///
    /// Размер задан в теме и подобран под газетный набор, но зрение у всех
    /// разное, а менять кегль в теме значило бы ломать пропорции полосы.
    /// Поэтому множитель: он растягивает всё сразу и набор остаётся
    /// согласованным.
    #[serde(default = "one")]
    pub font_scale: f32,
    /// Множитель межстрочного интервала читалки.
    #[serde(default = "one")]
    pub line_scale: f32,
    /// Прошёл ли читатель вступление хотя бы на одном из своих устройств.
    #[serde(default)]
    pub onboarding_seen: bool,
    /// Последняя версия, для которой уже показаны обязательные изменения.
    #[serde(default)]
    pub last_seen_version: String,
    /// Свести согласованные анимации к мгновенным переходам.
    #[serde(default)]
    pub reduce_motion: bool,
    /// Короткие звуки компаньона. Включены по умолчанию, но всегда
    /// отключаются отдельно от анимаций.
    #[serde(default = "yes")]
    pub companion_sounds: bool,
    /// Набирать основу слова полужирным.
    ///
    /// Приём беглого чтения: взгляд цепляется за начало слова, а окончание
    /// достраивает сам. Где именно проходит граница — дело
    /// [`crate::reading::emphasis`], здесь только «включено или нет».
    #[serde(default)]
    pub emphasize_stems: bool,
    /// Прожектор: что оставлять светлым, а что притушить.
    ///
    /// Именем, а не номером, по той же причине, что тема: набор режимов ещё
    /// будет меняться, а у читателя не должен от этого поехать выбор.
    /// Известные значения — `off`, `sentence`, `paragraph`.
    #[serde(default = "default_focus")]
    pub focus_mode: String,
    /// Темп ведущей строки, слов в минуту. Ноль — выключена.
    #[serde(default)]
    pub pacer_wpm: i32,
    /// Размер отрезка чтения в словах. Ноль — отрезки выключены.
    #[serde(default)]
    pub segment_words: i32,
    /// Разделы газеты, которые читателю интересны.
    ///
    /// Пустой список — весь номер: читатель, который ничего не выбирал,
    /// должен увидеть газету, а не приглашение сперва её настроить.
    #[serde(default)]
    pub newspaper_topics: Vec<String>,
    /// Клали ли уже демо-книгу.
    ///
    /// Проверять «библиотека пуста» вместо этого нельзя: читатель, удаливший
    /// все свои книги, получил бы демо обратно — и понял бы это как то, что
    /// приложение не удалило ничего.
    #[serde(default)]
    pub demo_added: bool,
    /// Интенсивность повторений — именем, по той же причине, что и тема.
    #[serde(default = "default_intensity")]
    pub intensity: String,
    /// Местный день последней тренировки.
    ///
    /// День, а не момент: серия считается по календарю читателя. Позанимался
    /// в полночь и в час ночи — это два дня подряд, и спорить с его
    /// календарём приложению не с руки.
    ///
    /// Deprecated по §6: тренировка вынесена в `PracticeState` (CRDT).
    /// Поле оставлено для чтения старых файлов и миграции, новые записи
    /// пишутся через `PracticeState`. LWW-слияние его игнорирует.
    #[serde(default)]
    pub trained_on: i64,
    #[serde(default)]
    pub streak_days: i32,
    /// Лучшая серия.
    ///
    /// Хранится отдельно и никогда не уменьшается: пропущенный день обнуляет
    /// текущую серию, но не отменяет того, что три недели подряд
    /// действительно были.
    ///
    /// Deprecated по §6: см. `trained_on`.
    #[serde(default)]
    pub best_streak: i32,
    /// Сколько ответов дано всего и сколько из них верных.
    ///
    /// Два числа вместо истории ответов: расписание спрашивает у них только
    /// долю верных ([`scheduler::ease`]), а история в тысячу записей ездила
    /// бы между устройствами каждую синхронизацию ради одного дробного числа.
    ///
    /// Deprecated по §6: см. `trained_on`.
    #[serde(default)]
    pub answers: i32,
    #[serde(default)]
    pub right: i32,
}

fn default_theme() -> String {
    DEFAULT_THEME.to_string()
}

/// Прожектор по умолчанию выключен: приём помогает не всем, а навязанный
/// полумрак мешает всем остальным.
fn default_focus() -> String {
    FOCUS_OFF.to_string()
}

/// Прожектор выключен.
pub const FOCUS_OFF: &str = "off";

fn default_intensity() -> String {
    Intensity::Normal.name().to_string()
}

fn one() -> f32 {
    1.0
}

fn yes() -> bool {
    true
}

impl Default for AppSettings {
    fn default() -> Self {
        AppSettings {
            theme: default_theme(),
            font_scale: 1.0,
            line_scale: 1.0,
            onboarding_seen: false,
            last_seen_version: String::new(),
            reduce_motion: false,
            companion_sounds: true,
            emphasize_stems: false,
            focus_mode: default_focus(),
            pacer_wpm: 0,
            segment_words: 0,
            newspaper_topics: Vec::new(),
            demo_added: false,
            intensity: default_intensity(),
            trained_on: 0,
            streak_days: 0,
            best_streak: 0,
            answers: 0,
            right: 0,
        }
    }
}

impl AppSettings {
    /// Интенсивность по имени.
    pub fn review_intensity(&self) -> Intensity {
        Intensity::of(&self.intensity)
    }

    /// Поправка сроков под то, как читатель отвечает на самом деле.
    pub fn ease(&self) -> f32 {
        scheduler::ease(self.answers, self.right)
    }

    /// Заменяет настройки целиком — так они приезжают с другого устройства.
    ///
    /// Признак «клали ли демо-книгу» при этом остаётся местным: он про то,
    /// что происходило на *этом* устройстве, и приезжать ему неоткуда.
    /// То же и с тренировкой по §6: она живёт в `PracticeState` как CRDT,
    /// а не LWW. Старые поля `trained_on`/`streak_days`/`best_streak`/
    /// `answers`/`right` нельзя накатывать поверх локальных — иначе
    /// `answers 100 -> 80` и сегодняшняя тренировка исчезает.
    pub fn replaced_by(&self, incoming: &AppSettings) -> AppSettings {
        AppSettings {
            demo_added: self.demo_added,
            trained_on: self.trained_on,
            streak_days: self.streak_days,
            best_streak: self.best_streak,
            answers: self.answers,
            right: self.right,
            ..incoming.clone()
        }
    }

    /// Есть ли legacy-тренировка, которую нужно мигрировать в `PracticeState`.
    pub fn has_legacy_training(&self) -> bool {
        self.trained_on != 0
            || self.streak_days != 0
            || self.best_streak != 0
            || self.answers != 0
            || self.right != 0
    }

    /// Возвращает копию с обнулёнными legacy-полями тренировки (§6).
    pub fn cleared_legacy(&self) -> AppSettings {
        AppSettings {
            trained_on: 0,
            streak_days: 0,
            best_streak: 0,
            answers: 0,
            right: 0,
            ..self.clone()
        }
    }

    /// Множитель шрифта в допустимых пределах.
    pub fn with_font_scale(&self, scale: f32) -> AppSettings {
        AppSettings {
            font_scale: scale.clamp(FONT_SCALE.0, FONT_SCALE.1),
            ..self.clone()
        }
    }

    /// Множитель интервала в допустимых пределах.
    pub fn with_line_scale(&self, scale: f32) -> AppSettings {
        AppSettings {
            line_scale: scale.clamp(LINE_SCALE.0, LINE_SCALE.1),
            ..self.clone()
        }
    }

    /// Темп ведущей строки в допустимых пределах.
    ///
    /// Ноль проходит как есть — это «выключено», а не «очень медленно».
    pub fn with_pacer(&self, wpm: i32) -> AppSettings {
        AppSettings {
            pacer_wpm: if wpm <= 0 {
                0
            } else {
                wpm.clamp(PACER_WPM.0, PACER_WPM.1)
            },
            ..self.clone()
        }
    }

    /// Отрезок чтения в допустимых пределах. Ноль — выключено.
    pub fn with_segment(&self, words: i32) -> AppSettings {
        AppSettings {
            segment_words: if words <= 0 {
                0
            } else {
                words.clamp(SEGMENT_WORDS.0, SEGMENT_WORDS.1)
            },
            ..self.clone()
        }
    }

    /// Разделы газеты: без пустых, без повторов и не больше, чем их бывает.
    ///
    /// Чистка здесь, а не в интерфейсе: список приезжает с другого устройства
    /// и через сеть, и доверять ему на слово нельзя.
    pub fn with_newspaper_topics(&self, topics: Vec<String>) -> AppSettings {
        let mut clean: Vec<String> = Vec::new();
        for topic in topics {
            let code = topic.trim().to_lowercase();
            if code.is_empty() || code.len() > 32 || clean.contains(&code) {
                continue;
            }
            clean.push(code);
            if clean.len() >= 16 {
                break;
            }
        }
        AppSettings {
            newspaper_topics: clean,
            ..self.clone()
        }
    }

    /// Учитывает ответ тренировки.
    ///
    /// Здесь же продлевается серия дней: она про то, что читатель сегодня
    /// занимался, а «занимался» — это ответил хотя бы раз. Считать серию по
    /// открытию экрана было бы нечестно, а по закрытой колоде — жестоко:
    /// человек, у которого сегодня четыре свободных минуты, серию не теряет.
    pub fn with_answer(&self, right: bool, now: i64, offset_minutes: i32) -> AppSettings {
        let today = local_day(now, offset_minutes);
        let streak = if self.trained_on == 0 {
            // Ноль — это «ещё ни разу», а не первое января семидесятого.
            // Проверяется отдельно, чтобы признак не столкнулся со значением:
            // без этого в самый первый день эпохи серия начиналась бы с нуля.
            1
        } else if self.trained_on == today {
            self.streak_days
        } else if self.trained_on == today - 1 {
            self.streak_days + 1
        } else {
            // Пропуск обрывает серию, и она начинается заново — с сегодняшнего
            // дня, а не с нуля: сегодня-то он занимался.
            1
        };

        AppSettings {
            trained_on: today,
            streak_days: streak,
            best_streak: self.best_streak.max(streak),
            answers: self.answers + 1,
            right: self.right + i32::from(right),
            ..self.clone()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::DAY_MS;

    /// Полдень 14 ноября 2023 по Гринвичу — обычный день, не край эпохи.
    const ПОЛДЕНЬ: i64 = 1_700_000_000_000;

    fn занимался(settings: &AppSettings, дней_назад: i64) -> AppSettings {
        settings.with_answer(true, ПОЛДЕНЬ - дней_назад * DAY_MS, 0)
    }

    #[test]
    fn серия_растёт_день_за_днём() {
        let mut settings = AppSettings::default();
        for дней_назад in (0..3).rev() {
            settings = занимался(&settings, дней_назад);
        }
        assert_eq!(settings.streak_days, 3);
        assert_eq!(settings.answers, 3);
    }

    #[test]
    fn несколько_ответов_за_день_серию_не_умножают() {
        let mut settings = AppSettings::default();
        for _ in 0..5 {
            settings = занимался(&settings, 0);
        }
        assert_eq!(settings.streak_days, 1, "серия выросла за один день");
        assert_eq!(settings.answers, 5);
    }

    #[test]
    fn пропуск_обрывает_серию_но_не_обнуляет_лучшую() {
        let mut settings = AppSettings::default();
        for дней_назад in (5..10).rev() {
            settings = занимался(&settings, дней_назад);
        }
        assert_eq!(settings.streak_days, 5);

        // Через несколько пустых дней серия начинается заново — с единицы, а
        // не с нуля: сегодня-то он занимался.
        let после = занимался(&settings, 0);
        assert_eq!(после.streak_days, 1);
        assert_eq!(после.best_streak, 5, "лучшая серия забылась");
    }

    #[test]
    fn верные_ответы_считаются_отдельно_от_всех() {
        let settings = AppSettings::default()
            .with_answer(true, ПОЛДЕНЬ, 0)
            .with_answer(false, ПОЛДЕНЬ, 0)
            .with_answer(true, ПОЛДЕНЬ, 0);
        assert_eq!(settings.answers, 3);
        assert_eq!(settings.right, 2);
    }

    #[test]
    fn поправка_берётся_из_расписания() {
        let settings = AppSettings {
            answers: 100,
            right: 100,
            ..Default::default()
        };
        assert_eq!(settings.ease(), scheduler::ease(100, 100));
        assert!(settings.ease() > 1.0);
    }

    #[test]
    fn множители_не_выходят_за_пределы() {
        let settings = AppSettings::default();
        assert_eq!(settings.with_font_scale(9.0).font_scale, FONT_SCALE.1);
        assert_eq!(settings.with_font_scale(0.1).font_scale, FONT_SCALE.0);
        assert_eq!(settings.with_line_scale(9.0).line_scale, LINE_SCALE.1);
        assert_eq!(settings.with_line_scale(0.1).line_scale, LINE_SCALE.0);
    }

    #[test]
    fn приехавшие_настройки_не_приносят_демо_книгу_обратно() {
        let местные = AppSettings {
            demo_added: true,
            ..Default::default()
        };
        let приехавшие = AppSettings {
            theme: "Sepia".to_string(),
            demo_added: false,
            ..Default::default()
        };

        let слитые = местные.replaced_by(&приехавшие);
        assert_eq!(
            слитые.theme, "Sepia",
            "тема с другого устройства не приехала"
        );
        assert!(слитые.demo_added, "демо-книга приедет второй раз");
    }

    #[test]
    fn незнакомая_интенсивность_читается_как_средняя() {
        let settings = AppSettings {
            intensity: "Ferocious".to_string(),
            ..Default::default()
        };
        assert_eq!(settings.review_intensity(), Intensity::Normal);
    }

    /// Запись обязана совпасть с той, что писал клиент на Kotlin.
    ///
    /// Иначе первый же запуск после переезда встретит читателя настройками по
    /// умолчанию: тема сбита, серия потеряна.
    #[test]
    fn читаются_настройки_записанные_клиентом() {
        let сохранённое = r#"{
            "theme": "Sepia",
            "fontScale": 1.2,
            "lineScale": 1.1,
            "demoAdded": true,
            "intensity": "Strong",
            "trainedOn": 20000,
            "streakDays": 7,
            "bestStreak": 21,
            "answers": 300,
            "right": 270
        }"#;
        let settings: AppSettings =
            serde_json::from_str(сохранённое).expect("настройки клиента не читаются");

        assert_eq!(settings.theme, "Sepia");
        assert_eq!(settings.streak_days, 7);
        assert_eq!(settings.best_streak, 21);
        assert_eq!(settings.review_intensity(), Intensity::Strong);

        // И обратно — теми же именами.
        let json = serde_json::to_string(&settings).expect("настройки не пишутся");
        assert!(
            json.contains("\"fontScale\""),
            "поле переименовалось: {json}"
        );
        assert!(
            json.contains("\"demoAdded\""),
            "поле переименовалось: {json}"
        );
    }

    #[test]
    fn неполная_запись_добирается_умолчаниями() {
        let settings: AppSettings =
            serde_json::from_str(r#"{"theme":"Oled"}"#).expect("огрызок не читается");
        assert_eq!(settings.theme, "Oled");
        assert_eq!(settings.font_scale, 1.0);
        assert_eq!(settings.review_intensity(), Intensity::Normal);
    }
}

#[cfg(test)]
mod reading_tests {
    use super::*;

    // Настройки уже лежат на устройствах. Новое поле обязано читаться из
    // старого файла, а не превращать его в «настроек нет».
    #[test]
    fn старый_файл_читается_с_умолчаниями() {
        let old = r#"{"theme":"Sepia","fontScale":1.2,"lineScale":1.1,
                      "onboardingSeen":true,"reduceMotion":false,
                      "demoAdded":true,"intensity":"normal"}"#;
        let settings: AppSettings = serde_json::from_str(old).expect("старый файл");

        assert_eq!(settings.theme, "Sepia");
        assert!(!settings.emphasize_stems);
        assert_eq!(settings.focus_mode, FOCUS_OFF);
        assert_eq!(settings.pacer_wpm, 0);
        assert_eq!(settings.segment_words, 0);
        assert!(settings.newspaper_topics.is_empty());
        assert!(settings.companion_sounds, "старый файл должен получить звуки по умолчанию");
    }

    #[test]
    fn темп_держится_в_пределах_и_выключается_нулём() {
        let settings = AppSettings::default();
        assert_eq!(settings.with_pacer(0).pacer_wpm, 0);
        assert_eq!(settings.with_pacer(-5).pacer_wpm, 0);
        assert_eq!(settings.with_pacer(10).pacer_wpm, 100);
        assert_eq!(settings.with_pacer(10_000).pacer_wpm, 400);
        assert_eq!(settings.with_pacer(220).pacer_wpm, 220);
    }

    #[test]
    fn отрезок_держится_в_пределах_и_выключается_нулём() {
        let settings = AppSettings::default();
        assert_eq!(settings.with_segment(0).segment_words, 0);
        assert_eq!(settings.with_segment(5).segment_words, 40);
        assert_eq!(settings.with_segment(99_999).segment_words, 2000);
        assert_eq!(settings.with_segment(300).segment_words, 300);
    }

    #[test]
    fn разделы_газеты_чистятся_от_мусора() {
        let settings = AppSettings::default().with_newspaper_topics(vec![
            " World ".to_string(),
            "world".to_string(),
            String::new(),
            "SPORT".to_string(),
            "x".repeat(64),
        ]);
        assert_eq!(settings.newspaper_topics, vec!["world", "sport"]);
    }

    #[test]
    fn разделов_газеты_не_больше_шестнадцати() {
        let many: Vec<String> = (0..40).map(|at| format!("topic{at}")).collect();
        let settings = AppSettings::default().with_newspaper_topics(many);
        assert_eq!(settings.newspaper_topics.len(), 16);
    }

    // Настройки чтения — местное дело каждого читателя, но не каждого
    // устройства: включив жирную основу на телефоне, он ждёт её и в браузере.
    #[test]
    fn настройки_чтения_приезжают_с_другого_устройства() {
        let here = AppSettings::default();
        let there = AppSettings {
            emphasize_stems: true,
            focus_mode: "sentence".to_string(),
            pacer_wpm: 220,
            segment_words: 300,
            newspaper_topics: vec!["world".to_string()],
            ..AppSettings::default()
        };

        let merged = here.replaced_by(&there);
        assert!(merged.emphasize_stems);
        assert_eq!(merged.focus_mode, "sentence");
        assert_eq!(merged.pacer_wpm, 220);
        assert_eq!(merged.segment_words, 300);
        assert_eq!(merged.newspaper_topics, vec!["world"]);
    }
}
