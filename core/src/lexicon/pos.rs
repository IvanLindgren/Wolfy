//! Части речи и уровни CEFR.

use std::fmt;

/// Часть речи. Набор совпадает с кодами генератора лексикона
/// (`tools/build_lexicon.py`) и с universal tagset, чтобы разбор Wolfy и
/// Читавука не разъезжался.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Pos {
    Noun,
    Verb,
    Adjective,
    Adverb,
    Pronoun,
    Determiner,
    Preposition,
    Conjunction,
    Particle,
    Numeral,
}

impl Pos {
    /// Разбирает однобуквенный код из файла лексикона.
    pub fn from_code(code: u8) -> Option<Self> {
        Some(match code {
            b'n' => Pos::Noun,
            b'v' => Pos::Verb,
            b'a' => Pos::Adjective,
            b'r' => Pos::Adverb,
            b'p' => Pos::Pronoun,
            b'd' => Pos::Determiner,
            b'i' => Pos::Preposition,
            b'c' => Pos::Conjunction,
            b't' => Pos::Particle,
            b'm' => Pos::Numeral,
            _ => return None,
        })
    }

    /// Тот же код обратно — для FFI и отладочных дампов.
    pub fn code(self) -> u8 {
        match self {
            Pos::Noun => b'n',
            Pos::Verb => b'v',
            Pos::Adjective => b'a',
            Pos::Adverb => b'r',
            Pos::Pronoun => b'p',
            Pos::Determiner => b'd',
            Pos::Preposition => b'i',
            Pos::Conjunction => b'c',
            Pos::Particle => b't',
            Pos::Numeral => b'm',
        }
    }

    /// Имя universal tagset для внешнего контракта и цветовой схемы клиента.
    pub fn tag(self) -> &'static str {
        match self {
            Pos::Noun => "NOUN",
            Pos::Verb => "VERB",
            Pos::Adjective => "ADJ",
            Pos::Adverb => "ADV",
            Pos::Pronoun => "PRON",
            Pos::Determiner => "DET",
            Pos::Preposition => "ADP",
            Pos::Conjunction => "CONJ",
            Pos::Particle => "PRT",
            Pos::Numeral => "NUM",
        }
    }

    /// Название для интерфейса. Русское, потому что интерфейс русский, а
    /// разбор читают глазами, а не парсером.
    pub fn label(self) -> &'static str {
        match self {
            Pos::Noun => "существительное",
            Pos::Verb => "глагол",
            Pos::Adjective => "прилагательное",
            Pos::Adverb => "наречие",
            Pos::Pronoun => "местоимение",
            Pos::Determiner => "определитель",
            Pos::Preposition => "предлог",
            Pos::Conjunction => "союз",
            Pos::Particle => "частица",
            Pos::Numeral => "числительное",
        }
    }
}

impl fmt::Display for Pos {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

/// Набор частей речи одного слова — битовая маска.
///
/// Маска, а не `Vec`: у «read» их две, у «set» — три, а запись в лексиконе
/// должна оставаться маленькой, потому что таких записей семьдесят восемь
/// тысяч и все они живут в памяти телефона.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PosSet(u16);

impl PosSet {
    pub const EMPTY: PosSet = PosSet(0);

    fn bit(pos: Pos) -> u16 {
        1 << match pos {
            Pos::Noun => 0,
            Pos::Verb => 1,
            Pos::Adjective => 2,
            Pos::Adverb => 3,
            Pos::Pronoun => 4,
            Pos::Determiner => 5,
            Pos::Preposition => 6,
            Pos::Conjunction => 7,
            Pos::Particle => 8,
            Pos::Numeral => 9,
        }
    }

    pub fn insert(&mut self, pos: Pos) {
        self.0 |= Self::bit(pos);
    }

    pub fn contains(self, pos: Pos) -> bool {
        self.0 & Self::bit(pos) != 0
    }

    pub fn is_empty(self) -> bool {
        self.0 == 0
    }

    /// Части речи по порядку объявления `Pos`.
    pub fn iter(self) -> impl Iterator<Item = Pos> {
        const ALL: [Pos; 10] = [
            Pos::Noun,
            Pos::Verb,
            Pos::Adjective,
            Pos::Adverb,
            Pos::Pronoun,
            Pos::Determiner,
            Pos::Preposition,
            Pos::Conjunction,
            Pos::Particle,
            Pos::Numeral,
        ];
        ALL.into_iter().filter(move |p| self.contains(*p))
    }

    /// Самая вероятная часть речи вне контекста.
    ///
    /// Порядок неслучайный: у омонима «book» существительное встречается чаще
    /// глагола, у «run» — наоборот, но угадывать по одному слову невозможно,
    /// поэтому берём знаменательные раньше служебных и не притворяемся, что
    /// знаем больше. Контекст уточнит теггер.
    pub fn primary(self) -> Option<Pos> {
        self.iter().next()
    }
}

/// Уровень слова по европейской шкале.
///
/// Выведен из частотности генератором лексикона, официальной разметкой не
/// является — см. `cefr_level` в `tools/build_lexicon.py`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Cefr {
    A1,
    A2,
    B1,
    B2,
    C1,
    C2,
}

impl Cefr {
    pub fn parse(text: &str) -> Option<Self> {
        Some(match text {
            "A1" => Cefr::A1,
            "A2" => Cefr::A2,
            "B1" => Cefr::B1,
            "B2" => Cefr::B2,
            "C1" => Cefr::C1,
            "C2" => Cefr::C2,
            _ => return None,
        })
    }

    pub fn label(self) -> &'static str {
        match self {
            Cefr::A1 => "A1",
            Cefr::A2 => "A2",
            Cefr::B1 => "B1",
            Cefr::B2 => "B2",
            Cefr::C1 => "C1",
            Cefr::C2 => "C2",
        }
    }
}

impl fmt::Display for Cefr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn набор_частей_речи_хранит_несколько_значений() {
        let mut set = PosSet::EMPTY;
        set.insert(Pos::Noun);
        set.insert(Pos::Verb);

        assert!(set.contains(Pos::Noun));
        assert!(set.contains(Pos::Verb));
        assert!(!set.contains(Pos::Adjective));
        assert_eq!(set.iter().collect::<Vec<_>>(), vec![Pos::Noun, Pos::Verb]);
    }

    #[test]
    fn коды_частей_речи_обратимы() {
        for pos in PosSet(u16::MAX).iter() {
            assert_eq!(Pos::from_code(pos.code()), Some(pos));
        }
    }

    #[test]
    fn пустой_набор_не_имеет_основной_части_речи() {
        assert_eq!(PosSet::EMPTY.primary(), None);
    }
}
