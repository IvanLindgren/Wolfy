//! Словарь английского: начальная форма, части речи, частотность, уровень.
//!
//! Файл лексикона собирается `tools/build_lexicon.py` из WordNet и корпуса
//! Brown и встраивается прямо в бинарник ядра. Так карточка слова открывается
//! в самолёте: ни сети, ни файла на диске, ни распаковки при старте.
//!
//! Разбор формы («children» → «child», множественное число) живёт в
//! [`morphology`] и повторяет правила Читавука — одно слово обязано
//! разбираться в двух приложениях одинаково.

mod morphology;
mod pos;
mod verbs;

pub use morphology::{analyze, Fact, FormKind, WordAnalysis};
pub use pos::{Cefr, Pos, PosSet};
pub use verbs::{verb_roles, FormSet, IrregularVerbs, VerbForm, VerbRole};

use std::collections::HashMap;
use std::sync::OnceLock;

use crate::error::{CoreError, Result};

/// Собранный генератором словарь. Едет внутри бинарника.
///
/// Только там, где бинарник грузится с диска. В браузере тот же файл
/// приезжает отдельным запросом и кладётся через [`Lexicon::install`]:
/// полтора мегабайта внутри `.wasm` задержали бы первую букву текста, а
/// нужны они не раньше первого тапа по слову.
#[cfg(feature = "embedded-lexicon")]
const EMBEDDED: &str = include_str!("../../data/english_lexicon.tsv");

/// Лексикон, поданный снаружи. Ставится один раз за жизнь процесса.
static INSTALLED: OnceLock<Lexicon> = OnceLock::new();

/// Что лексикон знает о начальной форме слова.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Entry {
    /// Все части речи, в которых слово встречается вне контекста.
    pub pos: PosSet,
    /// Частотность по шкале Zipf: 6 — «the», 4 — обычное книжное слово,
    /// 0 — то, чего в корпусе нет вовсе.
    pub zipf: f32,
    /// Уровень по европейской шкале, выведенный из частотности.
    pub cefr: Cefr,
    /// Часть речи, которой слово чаще всего оказывается в живом тексте.
    ///
    /// [`pos`](Entry::pos) отвечает, чем слово *бывает*: у «green» это и
    /// прилагательное, и существительное, и глагол. Разбору предложения нужен
    /// другой ответ — чем оно *обычно является*, и порядок в наборе об этом не
    /// говорит ничего. Здесь лежит преобладание, посчитанное по размеченному
    /// вручную корпусу Brown.
    ///
    /// `None` у слова, которое в корпусе почти не встречается: выдумывать
    /// преобладание по двум вхождениям хуже, чем не знать его.
    pub dominant: Option<Pos>,
}

/// Неправильная форма: «children» → «child» как существительное.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Irregular {
    pub lemma: &'static str,
    pub pos: Pos,
}

/// Словарь английского языка.
#[derive(Debug)]
pub struct Lexicon {
    words: HashMap<&'static str, Entry>,
    irregular: HashMap<&'static str, Irregular>,
}

impl Lexicon {
    /// Словарь, которым пользуется разбор. Разбирается один раз за жизнь
    /// процесса.
    ///
    /// Разбор строк отдаёт ссылки внутрь исходного текста, а не копии:
    /// семьдесят восемь тысяч отдельных `String` стоили бы нескольких
    /// мегабайт кучи на ровном месте.
    ///
    /// Поданный снаружи лексикон побеждает встроенный: там, где оба есть,
    /// снаружи приезжает более свежий файл.
    pub fn embedded() -> &'static Lexicon {
        if let Some(installed) = INSTALLED.get() {
            return installed;
        }

        #[cfg(feature = "embedded-lexicon")]
        {
            static LEXICON: OnceLock<Lexicon> = OnceLock::new();
            LEXICON.get_or_init(|| {
                // Файл собран генератором и едет в бинарнике: если он не
                // разобрался, сломана сборка, а не ввод пользователя. Пустой
                // словарь честнее падения — приложение откроет книгу, просто
                // без разбора слов, и это будет видно в логе.
                Lexicon::parse(EMBEDDED).unwrap_or_else(|_| Lexicon::empty())
            })
        }

        // Лексикона ещё нет: файл не докачался или его не запрашивали.
        // Пустой словарь — рабочий ответ, а не ошибка: книга откроется и
        // будет читаться, просто без разбора слов, и клиент это увидит по
        // `known: false`.
        #[cfg(not(feature = "embedded-lexicon"))]
        {
            static EMPTY: OnceLock<Lexicon> = OnceLock::new();
            EMPTY.get_or_init(Lexicon::empty)
        }
    }

    /// Пустой словарь: он ничего не знает, но и не падает.
    pub fn empty() -> Lexicon {
        Lexicon {
            words: HashMap::new(),
            irregular: HashMap::new(),
        }
    }

    /// Кладёт лексикон, пришедший файлом извне.
    ///
    /// Отвечает `false`, если лексикон уже стоит: заменить его на ходу
    /// нельзя — разбор раздаёт ссылки внутрь его текста, и вырвать этот текст
    /// из-под уже выданных ссылок значит получить висячую ссылку.
    ///
    /// Текст намеренно утекает в кучу навсегда. Это не потеря памяти, а её
    /// форма: лексикон живёт столько же, сколько процесс, и освобождать его
    /// некогда и незачем.
    pub fn install(text: String) -> bool {
        let leaked: &'static str = Box::leak(text.into_boxed_str());
        let parsed = Lexicon::parse(leaked).unwrap_or_else(|_| Lexicon::empty());
        INSTALLED.set(parsed).is_ok()
    }

    /// Стоит ли уже лексикон, поданный снаружи.
    pub fn installed() -> bool {
        INSTALLED.get().is_some()
    }

    /// Разбирает текст лексикона в формате `tools/build_lexicon.py`.
    ///
    /// Формат построчный:
    ///
    /// ```text
    /// W<TAB>слово<TAB>коды частей речи<TAB>zipf<TAB>уровень[<TAB>преобладающая]
    /// I<TAB>форма<TAB>лемма<TAB>код части речи
    /// ```
    pub fn parse(text: &'static str) -> Result<Lexicon> {
        let mut words = HashMap::with_capacity(80_000);
        let mut irregular = HashMap::with_capacity(4_500);

        for (number, line) in text.lines().enumerate() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.split('\t');
            let malformed =
                || CoreError::Lexicon(format!("строка {}: неполная запись", number + 1));

            match parts.next() {
                Some("W") => {
                    let word = parts.next().ok_or_else(malformed)?;
                    let codes = parts.next().ok_or_else(malformed)?;
                    let zipf = parts.next().ok_or_else(malformed)?;
                    let cefr = parts.next().ok_or_else(malformed)?;
                    // Шестое поле появилось во второй версии формата и есть не
                    // у всех слов. Читаем его необязательным: лексикон, собранный
                    // прошлой версией генератора, обязан продолжать работать.
                    let dominant = parts.next();

                    let mut set = PosSet::EMPTY;
                    for code in codes.bytes() {
                        match Pos::from_code(code) {
                            Some(pos) => set.insert(pos),
                            None => {
                                return Err(CoreError::Lexicon(format!(
                                    "строка {}: неизвестная часть речи «{}»",
                                    number + 1,
                                    code as char
                                )))
                            }
                        }
                    }
                    let zipf = zipf.parse::<f32>().map_err(|_| {
                        CoreError::Lexicon(format!(
                            "строка {}: частотность «{zipf}» не число",
                            number + 1
                        ))
                    })?;
                    let cefr = Cefr::parse(cefr).ok_or_else(|| {
                        CoreError::Lexicon(format!(
                            "строка {}: неизвестный уровень «{cefr}»",
                            number + 1
                        ))
                    })?;

                    let dominant =
                        match dominant {
                            Some(code) => {
                                let pos = code.bytes().next().and_then(Pos::from_code).ok_or_else(
                                    || {
                                        CoreError::Lexicon(format!(
                                        "строка {}: неизвестная преобладающая часть речи «{code}»",
                                        number + 1
                                    ))
                                    },
                                )?;
                                Some(pos)
                            }
                            None => None,
                        };

                    words.insert(
                        word,
                        Entry {
                            pos: set,
                            zipf,
                            cefr,
                            dominant,
                        },
                    );
                }
                Some("I") => {
                    let form = parts.next().ok_or_else(malformed)?;
                    let lemma = parts.next().ok_or_else(malformed)?;
                    let code = parts.next().ok_or_else(malformed)?;

                    let pos = code
                        .bytes()
                        .next()
                        .and_then(Pos::from_code)
                        .ok_or_else(|| {
                            CoreError::Lexicon(format!(
                                "строка {}: неизвестная часть речи «{code}»",
                                number + 1
                            ))
                        })?;
                    irregular.insert(form, Irregular { lemma, pos });
                }
                Some(other) => {
                    return Err(CoreError::Lexicon(format!(
                        "строка {}: неизвестный тип записи «{other}»",
                        number + 1
                    )))
                }
                None => continue,
            }
        }

        Ok(Lexicon { words, irregular })
    }

    /// Известна ли такая начальная форма.
    pub fn entry(&self, word: &str) -> Option<Entry> {
        self.words.get(word).copied()
    }

    /// Неправильная форма, если слово есть в таблице исключений.
    pub fn irregular(&self, form: &str) -> Option<Irregular> {
        self.irregular.get(form).copied()
    }

    /// Сколько начальных форм в словаре — для диагностики и тестов.
    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn встроенный_словарь_разбирается() {
        let lexicon = Lexicon::embedded();
        assert!(
            lexicon.len() > 70_000,
            "встроенный словарь неожиданно мал: {}",
            lexicon.len()
        );
    }

    #[test]
    fn частое_слово_имеет_высокую_частотность_и_низкий_уровень() {
        let entry = Lexicon::embedded()
            .entry("the")
            .expect("«the» обязано быть");
        assert!(entry.pos.contains(Pos::Determiner));
        assert!(entry.zipf > 7.0, "частотность «the»: {}", entry.zipf);
        assert_eq!(entry.cefr, Cefr::A1);
    }

    #[test]
    fn редкое_слово_из_романа_есть_в_словаре() {
        // Слово, ради которого и открывают карточку: в корпусе Brown его нет
        // ни разу, а в книге оно стоит на видном месте.
        let entry = Lexicon::embedded()
            .entry("serendipity")
            .expect("«serendipity» обязано быть в словаре");
        assert!(entry.pos.contains(Pos::Noun));
    }

    #[test]
    fn у_омонима_есть_преобладающее_значение() {
        // Набор частей речи говорит, чем слово бывает, и на «green» их три.
        // Разбору нужен другой ответ: в живом тексте это прилагательное.
        let lexicon = Lexicon::embedded();
        assert_eq!(
            lexicon.entry("green").and_then(|e| e.dominant),
            Some(Pos::Adjective)
        );
        assert_eq!(
            lexicon.entry("book").and_then(|e| e.dominant),
            Some(Pos::Noun)
        );
        assert_eq!(
            lexicon.entry("run").and_then(|e| e.dominant),
            Some(Pos::Verb)
        );
    }

    #[test]
    fn редкое_слово_остаётся_без_преобладания() {
        // Двух вхождений в корпусе мало, чтобы о чём-то говорить, и словарь
        // честно не отвечает вместо того, чтобы угадывать.
        assert_eq!(
            Lexicon::embedded()
                .entry("serendipity")
                .and_then(|e| e.dominant),
            None
        );
    }

    #[test]
    fn лексикон_прошлой_версии_без_шестого_поля_читается() {
        let lexicon = Lexicon::parse(
            "W	book	nv	5.2	A2
",
        )
        .expect("должно разобраться");
        assert_eq!(lexicon.entry("book").and_then(|e| e.dominant), None);
    }

    #[test]
    fn омоним_несёт_несколько_частей_речи() {
        let entry = Lexicon::embedded()
            .entry("book")
            .expect("«book» обязано быть");
        assert!(entry.pos.contains(Pos::Noun));
        assert!(entry.pos.contains(Pos::Verb));
    }

    #[test]
    fn неправильная_форма_ведёт_к_начальной() {
        let lexicon = Lexicon::embedded();
        assert_eq!(
            lexicon.irregular("children"),
            Some(Irregular {
                lemma: "child",
                pos: Pos::Noun
            })
        );
        assert_eq!(
            lexicon.irregular("ran"),
            Some(Irregular {
                lemma: "run",
                pos: Pos::Verb
            })
        );
    }

    #[test]
    fn битая_строка_ломает_разбор_с_понятной_ошибкой() {
        let err = Lexicon::parse("W\tword\tzz\t4.0\tB1").expect_err("должна быть ошибка");
        assert!(
            err.describe().contains("неизвестная часть речи"),
            "неожиданное сообщение: {}",
            err.describe()
        );
    }

    #[test]
    fn комментарии_и_пустые_строки_пропускаются() {
        let lexicon =
            Lexicon::parse("# заголовок\n\nW\tbook\tnv\t5.2\tA2\n").expect("должно разобраться");
        assert_eq!(lexicon.len(), 1);
    }
}
