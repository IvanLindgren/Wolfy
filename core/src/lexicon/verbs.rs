//! Формы глагола: какая именно перед нами — вторая, третья или обычная.
//!
//! Морфология из [`super::morphology`] отвечает на вопрос «от какого слова эта
//! форма». Грамматическому движку этого мало: чтобы отличить Past Simple от
//! Present Perfect, нужно знать, что «went» — вторая форма, а «gone» — третья.
//! WordNet такого не хранит, поэтому таблица собирается отдельно
//! (`tools/build_irregular_verbs.py`) и едет в бинарнике рядом с лексиконом.
//!
//! Одна и та же запись часто занимает несколько ролей сразу, и это не изъян
//! таблицы, а свойство языка: «read» — и начальная форма, и вторая, и третья;
//! «lay» — начальная форма одного глагола и вторая форма другого. Поэтому
//! ответ здесь всегда множество, а выбор из него делает разбор предложения,
//! которому видны соседи.

use std::borrow::Cow;
use std::collections::HashMap;
use std::sync::OnceLock;

use super::{Lexicon, Pos};

/// Собранная генератором таблица. Едет внутри бинарника.
const EMBEDDED: &str = include_str!("../../data/irregular_verbs.tsv");

/// Роль формы глагола.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum VerbForm {
    /// Начальная форма: «go», «read». Она же инфинитив без «to».
    Base,
    /// Третье лицо единственного числа: «goes», «reads».
    ThirdPerson,
    /// Вторая форма — прошедшее время: «went», «read».
    Past,
    /// Третья форма — причастие прошедшего времени: «gone», «read».
    Participle,
    /// Причастие настоящего времени, оно же герундий: «going», «reading».
    Gerund,
}

impl VerbForm {
    /// Название для разбора. Русское — разбор читают глазами.
    pub fn label(self) -> &'static str {
        match self {
            VerbForm::Base => "начальная форма",
            VerbForm::ThirdPerson => "3-е лицо единственного числа",
            VerbForm::Past => "прошедшее время",
            VerbForm::Participle => "причастие прошедшего времени",
            VerbForm::Gerund => "причастие настоящего времени",
        }
    }
}

/// Набор ролей одной записи — битовая маска.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct FormSet(u8);

impl FormSet {
    pub const EMPTY: FormSet = FormSet(0);

    const fn bit(form: VerbForm) -> u8 {
        1 << match form {
            VerbForm::Base => 0,
            VerbForm::ThirdPerson => 1,
            VerbForm::Past => 2,
            VerbForm::Participle => 3,
            VerbForm::Gerund => 4,
        }
    }

    pub const fn of(form: VerbForm) -> FormSet {
        FormSet(Self::bit(form))
    }

    pub fn insert(&mut self, form: VerbForm) {
        self.0 |= Self::bit(form);
    }

    pub fn contains(self, form: VerbForm) -> bool {
        self.0 & Self::bit(form) != 0
    }

    pub fn is_empty(self) -> bool {
        self.0 == 0
    }

    /// Роли по порядку объявления [`VerbForm`].
    pub fn iter(self) -> impl Iterator<Item = VerbForm> {
        const ALL: [VerbForm; 5] = [
            VerbForm::Base,
            VerbForm::ThirdPerson,
            VerbForm::Past,
            VerbForm::Participle,
            VerbForm::Gerund,
        ];
        ALL.into_iter().filter(move |f| self.contains(*f))
    }
}

/// Чем слово может быть как форма глагола.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerbRole {
    /// Начальная форма глагола: у «went» это «go».
    pub base: Cow<'static, str>,
    /// Роли, которые эта форма может занимать.
    pub forms: FormSet,
    /// Правильная форма или из таблицы исключений.
    pub irregular: bool,
}

/// Таблица неправильных глаголов.
///
/// Индекс идёт от формы к глаголу, а не наоборот: спрашивают всегда «что это
/// за слово», и обратный порядок означал бы перебор всей таблицы на каждое
/// слово страницы.
#[derive(Debug, Default)]
pub struct IrregularVerbs {
    by_form: HashMap<&'static str, Vec<(&'static str, FormSet)>>,
}

impl IrregularVerbs {
    /// Встроенная таблица. Разбирается один раз за жизнь процесса.
    pub fn embedded() -> &'static IrregularVerbs {
        static TABLE: OnceLock<IrregularVerbs> = OnceLock::new();
        // Файл собран генератором и едет в бинарнике: если он не разобрался,
        // сломана сборка, а не ввод пользователя. Пустая таблица честнее
        // падения — читалка откроет книгу, просто без разбора времён.
        TABLE.get_or_init(|| IrregularVerbs::parse(EMBEDDED))
    }

    /// Разбирает таблицу в формате `tools/build_irregular_verbs.py`:
    /// `основа<TAB>вторая форма<TAB>третья форма`, варианты через `|`.
    pub fn parse(text: &'static str) -> IrregularVerbs {
        let mut by_form: HashMap<&'static str, Vec<(&'static str, FormSet)>> =
            HashMap::with_capacity(700);

        let mut add = |form: &'static str, base: &'static str, role: VerbForm| {
            let roles = by_form.entry(form).or_default();
            match roles.iter_mut().find(|(known, _)| *known == base) {
                Some((_, set)) => set.insert(role),
                None => roles.push((base, FormSet::of(role))),
            }
        };

        for line in text.lines() {
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.split('\t');
            let (Some(base), Some(past), Some(participle)) =
                (parts.next(), parts.next(), parts.next())
            else {
                continue;
            };

            add(base, base, VerbForm::Base);
            for form in past.split('|').filter(|f| !f.is_empty()) {
                add(form, base, VerbForm::Past);
            }
            for form in participle.split('|').filter(|f| !f.is_empty()) {
                add(form, base, VerbForm::Participle);
            }
        }

        IrregularVerbs { by_form }
    }

    /// Роли слова по таблице исключений.
    fn lookup(&self, word: &str) -> &[(&'static str, FormSet)] {
        self.by_form.get(word).map(Vec::as_slice).unwrap_or(&[])
    }

    /// Сколько форм в таблице — для диагностики и тестов.
    pub fn len(&self) -> usize {
        self.by_form.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_form.is_empty()
    }
}

/// Чем слово может быть как форма глагола.
///
/// Ответ множественный и остаётся таким: «read» без соседей — это и начальная
/// форма, и прошедшее время, и причастие, и никакой словарь этого не решит.
/// Выбор делает разбор предложения, которому видно, что стоит рядом.
///
/// Пустой ответ значит «глаголом быть не может»: так отсеиваются
/// существительные, у которых просто похожее окончание.
pub fn verb_roles(lexicon: &Lexicon, word: &str) -> Vec<VerbRole> {
    let lower = word.to_lowercase();
    let mut roles: Vec<VerbRole> = IrregularVerbs::embedded()
        .lookup(&lower)
        .iter()
        .map(|(base, forms)| VerbRole {
            base: Cow::Borrowed(*base),
            forms: *forms,
            irregular: true,
        })
        .collect();

    // Начальная форма проверяется всегда, даже когда слово нашлось в таблице:
    // «read» стоит там как вторая и третья форма, но остаётся и первой.
    if is_verb(lexicon, &lower) {
        merge(&mut roles, lower.clone(), VerbForm::Base, false);
    }

    for (suffix, role) in REGULAR {
        let Some(stem) = lower.strip_suffix(suffix) else {
            continue;
        };
        if stem.len() < 2 {
            continue;
        }
        // «-ss» это не окончание: «pass» не третье лицо от «pas».
        if suffix == "s" && lower.ends_with("ss") {
            continue;
        }
        for base in super::morphology::verb_stems(suffix, stem) {
            if !is_verb(lexicon, &base) {
                continue;
            }
            merge(&mut roles, base.clone(), role, false);
            // Вторая форма правильного глагола совпадает с третьей: «walked» —
            // и «шёл», и «пройденный». Различает их только то, что стоит слева,
            // поэтому обе роли остаются на слове.
            if role == VerbForm::Past {
                merge(&mut roles, base, VerbForm::Participle, false);
            }
            break;
        }
    }

    roles
}

/// Правильные окончания и роли, которые они дают.
const REGULAR: [(&str, VerbForm); 3] = [
    ("s", VerbForm::ThirdPerson),
    ("ing", VerbForm::Gerund),
    ("ed", VerbForm::Past),
];

fn is_verb(lexicon: &Lexicon, word: &str) -> bool {
    lexicon
        .entry(word)
        .is_some_and(|entry| entry.pos.contains(Pos::Verb))
}

/// Добавляет роль, не заводя второй записи для того же глагола.
fn merge(roles: &mut Vec<VerbRole>, base: String, role: VerbForm, irregular: bool) {
    if let Some(existing) = roles.iter_mut().find(|r| r.base == base) {
        existing.forms.insert(role);
        return;
    }
    roles.push(VerbRole {
        base: Cow::Owned(base),
        forms: FormSet::of(role),
        irregular,
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Роли слова одной строкой — так провал теста читается без отладчика.
    fn roles(word: &str) -> String {
        let mut out: Vec<String> = verb_roles(Lexicon::embedded(), word)
            .iter()
            .map(|role| {
                let forms: Vec<&str> = role.forms.iter().map(|f| f.label()).collect();
                format!("{}: {}", role.base, forms.join(", "))
            })
            .collect();
        out.sort();
        out.join(" | ")
    }

    #[test]
    fn таблица_разбирается_и_не_пуста() {
        let table = IrregularVerbs::embedded();
        assert!(table.len() > 600, "форм в таблице: {}", table.len());
    }

    #[test]
    fn вторая_и_третья_формы_различаются() {
        // Ради этого таблица и собиралась: без различия «went» и «gone»
        // Past Simple неотличим от Present Perfect.
        assert_eq!(roles("went"), "go: прошедшее время");
        assert_eq!(roles("gone"), "go: причастие прошедшего времени");
    }

    #[test]
    fn одинаковые_на_вид_формы_несут_все_роли_сразу() {
        // «read» пишется одинаково во всех трёх формах, и словарь тут выбрать
        // не может — выбирает разбор предложения, которому видны соседи.
        let read = roles("read");
        assert!(read.contains("начальная форма"), "{read}");
        assert!(read.contains("прошедшее время"), "{read}");
        assert!(read.contains("причастие прошедшего времени"), "{read}");
    }

    #[test]
    fn правильный_глагол_разбирается_по_окончанию() {
        assert_eq!(
            roles("walked"),
            "walk: прошедшее время, причастие прошедшего времени"
        );
        assert_eq!(roles("walks"), "walk: 3-е лицо единственного числа");
        assert_eq!(roles("walking"), "walk: причастие настоящего времени");
    }

    #[test]
    fn орфография_отыгрывается_назад() {
        assert_eq!(
            roles("stopped"),
            "stop: прошедшее время, причастие прошедшего времени"
        );
        assert_eq!(roles("making"), "make: причастие настоящего времени");
        assert_eq!(
            roles("carried"),
            "carry: прошедшее время, причастие прошедшего времени"
        );
    }

    #[test]
    fn вспомогательные_глаголы_на_месте() {
        assert_eq!(roles("was"), "be: прошедшее время");
        assert_eq!(roles("been"), "be: причастие прошедшего времени");
        assert_eq!(roles("had"), "have: прошедшее время, причастие прошедшего времени");
        assert_eq!(roles("done"), "do: причастие прошедшего времени");
    }

    #[test]
    fn окончание_не_отрезается_от_чего_попало() {
        // Ложное срабатывание хуже пропуска. «bed» и «glass» в словаре есть и
        // как глаголы — но именно как начальные формы: разложить их в «b» +
        // «-ed» и «glas» + «-s» разбор не имеет права.
        assert_eq!(roles("bed"), "bed: начальная форма");
        assert_eq!(roles("glass"), "glass: начальная форма");
        // А «ceiling» глаголом не бывает вовсе — и причастием от «ceil» не
        // становится, хотя окончание подходит.
        assert_eq!(roles("ceiling"), "");
    }

    #[test]
    fn одна_форма_принадлежит_двум_глаголам() {
        // «lay» — начальная форма «класть» и прошедшее от «лежать». Обе
        // возможности остаются: выбрать может только контекст.
        let lay = roles("lay");
        assert!(lay.contains("lay: начальная форма"), "{lay}");
        assert!(lay.contains("lie: прошедшее время"), "{lay}");
    }
}
