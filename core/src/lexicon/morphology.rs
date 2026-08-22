//! Разбор формы слова: «children» → «child», множественное число.
//!
//! Задача узкая и важная: читатель ткнул в «had been reading» и должен увидеть
//! не «нет такого слова», а «read, причастие настоящего времени». Поэтому
//! разбор идёт по цепочке — точное совпадение, таблица исключений, правила
//! суффиксов, — и на каждом шаге проверяет, что получившаяся основа реально
//! есть в словаре. Без этой проверки «bed» разложилось бы в «b» + «-ed».
//!
//! Правила намеренно повторяют `internal/english/english.go` Читавука: одно и
//! то же слово обязано разбираться в двух приложениях одинаково.

use super::{Cefr, Lexicon, Pos, PosSet};

/// Как слово соотносится со своей начальной формой.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FormKind {
    /// Слово и есть начальная форма: «book».
    Lemma,
    /// Форма построена по правилу: «books», «walked», «making».
    Regular,
    /// Форма из таблицы исключений: «children», «ran», «better».
    Irregular,
    /// Слова нет в словаре и оно не сводится к известной основе.
    Unknown,
}

/// Факт о форме, показанный человеку: «Число: множественное».
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fact {
    pub label: &'static str,
    pub value: String,
}

impl Fact {
    fn new(label: &'static str, value: impl Into<String>) -> Self {
        Fact {
            label,
            value: value.into(),
        }
    }
}

/// Разбор одного слова для карточки.
#[derive(Debug, Clone, PartialEq)]
pub struct WordAnalysis {
    /// Слово ровно так, как оно стоит в тексте, — вместе с регистром.
    pub surface: String,
    /// Начальная форма. Для неизвестного слова совпадает с приведённым к
    /// нижнему регистру `surface`.
    pub lemma: String,
    /// Части речи начальной формы вне контекста.
    pub pos: PosSet,
    pub form: FormKind,
    /// Что показать в разборе: число, время, степень сравнения.
    pub facts: Vec<Fact>,
    /// Частотность по шкале Zipf; 0.0 у неизвестного слова.
    pub zipf: f32,
    /// Уровень; у неизвестного слова — C2, потому что незнакомое слово скорее
    /// редкое, чем частое.
    pub cefr: Cefr,
}

impl WordAnalysis {
    fn unknown(surface: &str) -> Self {
        WordAnalysis {
            surface: surface.to_string(),
            lemma: surface.to_lowercase(),
            pos: PosSet::EMPTY,
            form: FormKind::Unknown,
            facts: Vec::new(),
            zipf: 0.0,
            cefr: Cefr::C2,
        }
    }

    /// Нашлось ли слово в словаре.
    pub fn is_known(&self) -> bool {
        self.form != FormKind::Unknown
    }
}

/// Разбирает слово по словарю.
///
/// Слово проверяется сразу с двух сторон: как готовая начальная форма и как
/// форма чего-то другого. Обычно срабатывает ровно одна сторона. Когда
/// срабатывают обе — «saw» это и «пила», и прошедшее от «see» — выбор
/// делается по частотности, а проигравший разбор уходит в факты карточки.
/// Подробности в [`analyze_stem`].
pub fn analyze(lexicon: &Lexicon, surface: &str) -> WordAnalysis {
    let trimmed = surface.trim_matches(|c: char| !c.is_alphanumeric() && c != '\'');
    if trimmed.is_empty() {
        return WordAnalysis::unknown(surface);
    }
    let lower = trimmed.to_lowercase();

    // Притяжательная форма снимается первой: «reader's» — это «reader»
    // плюс отдельный факт, а не неизвестное слово.
    let (stem, possessive) = strip_possessive(&lower);

    let mut analysis = analyze_stem(lexicon, surface, stem);
    if possessive && analysis.is_known() {
        analysis
            .facts
            .push(Fact::new("Форма", "притяжательная, окончание -'s"));
    }
    analysis
}

/// Снимает притяжательное окончание: «reader's» → «reader», «readers'» →
/// «readers».
fn strip_possessive(word: &str) -> (&str, bool) {
    if let Some(stem) = word.strip_suffix("'s") {
        if !stem.is_empty() {
            return (stem, true);
        }
    }
    if let Some(stem) = word.strip_suffix('\'') {
        if stem.ends_with('s') && stem.len() > 1 {
            return (stem, true);
        }
    }
    (word, false)
}

fn analyze_stem(lexicon: &Lexicon, surface: &str, lower: &str) -> WordAnalysis {
    let as_lemma = lexicon.entry(lower).map(|entry| WordAnalysis {
        surface: surface.to_string(),
        lemma: lower.to_string(),
        pos: entry.pos,
        form: FormKind::Lemma,
        facts: Vec::new(),
        zipf: entry.zipf,
        cefr: entry.cefr,
    });

    let as_form = analyze_irregular(lexicon, surface, lower)
        .or_else(|| analyze_regular(lexicon, surface, lower));

    match (as_lemma, as_form) {
        // Обычный случай: слово либо словарное, либо форма.
        (Some(lemma), None) => lemma,
        (None, Some(form)) => form,
        (None, None) => WordAnalysis::unknown(surface),

        // Слово годится и туда, и сюда: «making» — и существительное
        // «изготовление», и причастие от «make»; «saw» — и «пила», и прошедшее
        // от «see». Без контекста однозначного ответа нет, поэтому выбираем по
        // частотности: если начальная форма встречается не реже самого слова,
        // читатель почти наверняка смотрит на форму, а не на редкий омоним.
        // Проигравший вариант не выбрасывается — он уходит в факты, чтобы
        // карточка не врала об однозначности.
        (Some(lemma), Some(form)) => {
            if form.zipf >= lemma.zipf {
                let mut chosen = form;
                chosen.facts.push(Fact::new(
                    "Ещё значение",
                    format!("бывает и самостоятельным словом ({})", pos_list(lemma.pos)),
                ));
                chosen
            } else {
                let mut chosen = lemma;
                chosen.facts.push(Fact::new(
                    "Ещё разбор",
                    format!("может быть формой слова «{}»", form.lemma),
                ));
                chosen
            }
        }
    }
}

/// Разбор по таблице исключений: «children», «ran», «better».
fn analyze_irregular(lexicon: &Lexicon, surface: &str, lower: &str) -> Option<WordAnalysis> {
    let irregular = lexicon.irregular(lower)?;
    let entry = lexicon.entry(irregular.lemma)?;
    Some(WordAnalysis {
        surface: surface.to_string(),
        lemma: irregular.lemma.to_string(),
        pos: entry.pos,
        form: FormKind::Irregular,
        facts: irregular_facts(irregular.pos, irregular.lemma),
        zipf: entry.zipf,
        cefr: entry.cefr,
    })
}

/// Перечисление частей речи через запятую — для фактов карточки.
fn pos_list(set: PosSet) -> String {
    let mut out = String::new();
    for pos in set.iter() {
        if !out.is_empty() {
            out.push_str(", ");
        }
        out.push_str(pos.label());
    }
    out
}

/// Объяснение для формы из таблицы исключений.
///
/// Таблица знает часть речи, но не знает, какая именно это форма: «ran» и
/// «run» (причастие) лежат в ней одинаково. Поэтому формулировка честно
/// перечисляет варианты вместо того, чтобы угадывать.
fn irregular_facts(pos: Pos, lemma: &str) -> Vec<Fact> {
    let mut facts = vec![Fact::new("Форма", format!("неправильная, от «{lemma}»"))];
    match pos {
        Pos::Noun => facts.push(Fact::new("Число", "множественное")),
        Pos::Verb => facts.push(Fact::new("Форма глагола", "прошедшее время или причастие")),
        Pos::Adjective | Pos::Adverb => {
            facts.push(Fact::new("Степень", "сравнительная или превосходная"))
        }
        _ => {}
    }
    facts
}

/// Правила регулярных окончаний.
///
/// Каждое правило предлагает основы-кандидаты, и разбор принимается только
/// если кандидат нашёлся в словаре с подходящей частью речи. Проверка части
/// речи важна не меньше самого поиска: без неё «bed» разложилось бы в «b»,
/// а «ring» — в «r».
fn analyze_regular(lexicon: &Lexicon, surface: &str, lower: &str) -> Option<WordAnalysis> {
    struct Rule {
        suffix: &'static str,
        /// Части речи, в которых основа обязана быть известна.
        expects: &'static [Pos],
        /// Сколько букв обязано остаться в основе.
        ///
        /// Обычно двух достаточно — «goes» → «go», «dies» → «die». Исключение
        /// одно, и оно про «-ly»: двухбуквенных прилагательных, от которых
        /// образуются наречия, попросту не бывает, а вот ложных срабатываний
        /// на них хватает — «only» иначе разобралось бы как наречие от «on».
        min_stem: usize,
    }

    const RULES: [Rule; 6] = [
        Rule {
            suffix: "s",
            expects: &[Pos::Noun, Pos::Verb],
            min_stem: 2,
        },
        Rule {
            suffix: "ing",
            expects: &[Pos::Verb],
            min_stem: 2,
        },
        Rule {
            suffix: "ed",
            expects: &[Pos::Verb],
            min_stem: 2,
        },
        Rule {
            suffix: "est",
            expects: &[Pos::Adjective, Pos::Adverb],
            min_stem: 2,
        },
        Rule {
            suffix: "er",
            expects: &[Pos::Adjective, Pos::Adverb],
            min_stem: 2,
        },
        Rule {
            suffix: "ly",
            expects: &[Pos::Adjective],
            min_stem: 3,
        },
    ];

    for rule in RULES {
        if !lower.ends_with(rule.suffix) {
            continue;
        }
        let stem = &lower[..lower.len() - rule.suffix.len()];
        if stem.len() < rule.min_stem {
            continue;
        }
        if rule.suffix == "s" && lower.ends_with("ss") {
            continue; // «glass» — не множественное от «glas»
        }

        // Кандидатов у одной основы бывает несколько, и первый подошедший —
        // не обязательно верный: у «does» это «doe», самка оленя, а нужен
        // вспомогательный глагол «do». Побеждает самый частотный: у настоящей
        // основы частотность заведомо выше, чем у случайного совпадения.
        let mut best: Option<(String, crate::lexicon::Entry, Pos)> = None;
        for candidate in candidates(rule.suffix, stem) {
            let Some(entry) = lexicon.entry(&candidate) else {
                continue;
            };
            let Some(pos) = rule
                .expects
                .iter()
                .copied()
                .find(|p| entry.pos.contains(*p))
            else {
                continue;
            };
            if best
                .as_ref()
                .is_none_or(|(_, best, _)| entry.zipf > best.zipf)
            {
                best = Some((candidate, entry, pos));
            }
        }

        if let Some((lemma, entry, pos)) = best {
            return Some(WordAnalysis {
                surface: surface.to_string(),
                lemma,
                pos: entry.pos,
                form: FormKind::Regular,
                facts: regular_facts(rule.suffix, pos, entry.pos),
                zipf: entry.zipf,
                cefr: entry.cefr,
            });
        }
    }

    None
}

/// Основы-кандидаты для отрезанного окончания.
///
/// Английская орфография при склеивании окончания меняет основу тремя
/// способами, и все три надо отыграть назад: возвращённая немая «e»
/// («making» → «make»), удвоенная согласная («stopped» → «stop») и «y»,
/// ставшая «i» («carried» → «carry»).
fn candidates(suffix: &str, stem: &str) -> Vec<String> {
    let mut out = vec![stem.to_string()];

    if suffix == "s" {
        if let Some(without_e) = stem.strip_suffix('e') {
            // Окончание «-es» после шипящих и после гласной: «boxes» → «box»,
            // «watches» → «watch», «goes» → «go», «does» → «do». Лишние
            // кандидаты не опасны — каждый проверяется по словарю, и «hous»
            // от «houses» просто не найдётся.
            out.push(without_e.to_string());
            // «cities» → «city».
            if let Some(base) = without_e.strip_suffix('i') {
                out.push(format!("{base}y"));
            }
            // «wolves» → «wolf», «knives» → «knife».
            if let Some(base) = without_e.strip_suffix('v') {
                out.push(format!("{base}f"));
                out.push(format!("{base}fe"));
            }
        }
    } else {
        // Немая «e», снятая перед окончанием: «make» + «ing» → «making».
        out.push(format!("{stem}e"));

        // Удвоенная согласная: «stop» + «ed» → «stopped».
        let bytes = stem.as_bytes();
        if bytes.len() >= 2 {
            let last = bytes[bytes.len() - 1];
            if last == bytes[bytes.len() - 2] && !b"aeiou".contains(&last) {
                out.push(stem[..stem.len() - 1].to_string());
            }
        }

        // «y», ставшая «i»: «carry» + «ed» → «carried», «happy» + «ly» →
        // «happily».
        if let Some(base) = stem.strip_suffix('i') {
            out.push(format!("{base}y"));
        }
    }

    out
}

/// Объяснение для регулярной формы.
///
/// Одно и то же окончание значит разное в зависимости от части речи: «-s» на
/// существительном — множественное число, на глаголе — третье лицо. У
/// омонима вроде «books» верны оба разбора, и карточка показывает оба.
fn regular_facts(suffix: &str, matched: Pos, all: PosSet) -> Vec<Fact> {
    let mut facts = Vec::new();
    match suffix {
        "s" => {
            if all.contains(Pos::Noun) {
                facts.push(Fact::new("Число", "множественное, окончание -s"));
            }
            if all.contains(Pos::Verb) {
                facts.push(Fact::new(
                    "Лицо",
                    "3-е лицо единственного числа, окончание -s",
                ));
            }
        }
        "ing" => facts.push(Fact::new(
            "Форма глагола",
            "причастие настоящего времени или герундий, окончание -ing",
        )),
        "ed" => facts.push(Fact::new(
            "Форма глагола",
            "прошедшее время или причастие прошедшего времени, окончание -ed",
        )),
        "er" => facts.push(Fact::new("Степень", "сравнительная, окончание -er")),
        "est" => facts.push(Fact::new("Степень", "превосходная, окончание -est")),
        "ly" => facts.push(Fact::new(
            "Образование",
            "наречие от прилагательного, суффикс -ly",
        )),
        _ => {}
    }
    let _ = matched;
    facts
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::Lexicon;

    fn разбор(word: &str) -> WordAnalysis {
        analyze(Lexicon::embedded(), word)
    }

    #[test]
    fn начальная_форма_узнаётся_как_есть() {
        let a = разбор("book");
        assert_eq!(a.lemma, "book");
        assert_eq!(a.form, FormKind::Lemma);
        assert!(a.pos.contains(Pos::Noun));
        assert!(a.facts.is_empty());
    }

    #[test]
    fn регистр_не_мешает_разбору() {
        assert_eq!(разбор("Book").lemma, "book");
        assert_eq!(разбор("BOOK").lemma, "book");
        // Слово в тексте сохраняется как есть: карточка показывает его так,
        // как оно стоит на странице.
        assert_eq!(разбор("Book").surface, "Book");
    }

    #[test]
    fn множественное_число_сводится_к_единственному() {
        let a = разбор("books");
        assert_eq!(a.lemma, "book");
        assert_eq!(a.form, FormKind::Regular);
        assert!(a.facts.iter().any(|f| f.value.contains("множественное")));
    }

    #[test]
    fn немая_е_возвращается() {
        assert_eq!(разбор("making").lemma, "make");
        assert_eq!(разбор("liked").lemma, "like");
        assert_eq!(разбор("nicer").lemma, "nice");
    }

    #[test]
    fn удвоенная_согласная_снимается() {
        assert_eq!(разбор("stopped").lemma, "stop");
        assert_eq!(разбор("running").lemma, "run");
        assert_eq!(разбор("bigger").lemma, "big");
    }

    #[test]
    fn игрек_ставший_и_возвращается() {
        assert_eq!(разбор("carried").lemma, "carry");
        assert_eq!(разбор("cities").lemma, "city");
        assert_eq!(разбор("happily").lemma, "happy");
    }

    #[test]
    fn шипящие_берут_окончание_es() {
        assert_eq!(разбор("boxes").lemma, "box");
        assert_eq!(разбор("watches").lemma, "watch");
    }

    #[test]
    fn неправильные_формы_идут_из_таблицы() {
        let a = разбор("children");
        assert_eq!(a.lemma, "child");
        assert_eq!(a.form, FormKind::Irregular);

        assert_eq!(разбор("ran").lemma, "run");
        assert_eq!(разбор("been").lemma, "be");
        assert_eq!(разбор("wolves").lemma, "wolf");
    }

    #[test]
    fn притяжательная_форма_снимается() {
        let a = разбор("reader's");
        assert_eq!(a.lemma, "reader");
        assert!(a.facts.iter().any(|f| f.value.contains("притяжательная")));
    }

    #[test]
    fn слово_не_разбирается_на_обрывки() {
        // Главный риск правил суффиксов: «bed» не должно стать «b» + «-ed»,
        // «ring» — «r» + «-ing», «glass» — множественным от «glas».
        assert_eq!(разбор("bed").lemma, "bed");
        assert_eq!(разбор("bed").form, FormKind::Lemma);
        assert_eq!(разбор("ring").lemma, "ring");
        assert_eq!(разбор("ring").form, FormKind::Lemma);
        assert_eq!(разбор("glass").lemma, "glass");
        assert_eq!(разбор("glass").form, FormKind::Lemma);
    }

    #[test]
    fn омоним_разбирается_как_форма_если_начальная_форма_не_реже() {
        // «making» и «stopped» есть в WordNet самостоятельными словами, но в
        // книге это почти всегда формы «make» и «stop».
        let a = разбор("making");
        assert_eq!(a.lemma, "make");
        assert_eq!(a.form, FormKind::Regular);
        // Проигравший разбор не пропадает — иначе карточка врала бы об
        // однозначности.
        assert!(a.facts.iter().any(|f| f.label == "Ещё значение"));

        assert_eq!(разбор("stopped").lemma, "stop");
        assert_eq!(разбор("happily").lemma, "happy");

        // «saw» — прошедшее от «see» чаще, чем инструмент «пила».
        assert_eq!(разбор("saw").lemma, "see");
    }

    #[test]
    fn частая_лемма_не_проигрывает_надуманной_форме() {
        // Ловушка правила «-ly»: «only» не наречие от «on», хотя формально
        // разбирается именно так. Основа короче трёх букв — сигнал ложного
        // срабатывания.
        let a = разбор("only");
        assert_eq!(a.lemma, "only");
        assert_eq!(a.form, FormKind::Lemma);
    }

    #[test]
    fn немая_е_снимается_и_после_гласной() {
        // «-es» приклеивается не только к шипящим: «goes» → «go».
        assert_eq!(разбор("goes").lemma, "go");
        assert_eq!(разбор("does").lemma, "do");
        // При этом «houses» не должно свестись к несуществующему «hous».
        assert_eq!(разбор("houses").lemma, "house");
    }

    #[test]
    fn выдуманное_слово_остаётся_неизвестным() {
        let a = разбор("zzzqx");
        assert_eq!(a.form, FormKind::Unknown);
        assert!(!a.is_known());
        assert_eq!(a.cefr, Cefr::C2);
    }

    #[test]
    fn знаки_препинания_вокруг_слова_отбрасываются() {
        assert_eq!(разбор("«book»").lemma, "book");
        assert_eq!(разбор("book,").lemma, "book");
        assert_eq!(разбор("—").form, FormKind::Unknown);
    }
}
