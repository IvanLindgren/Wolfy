//! Определение части речи в контексте.
//!
//! Словарь отвечает, чем слово бывает, и на половине английского текста этого
//! мало: «book» — и книга, и заказать, «light» — и свет, и лёгкий, и зажечь.
//! Вне предложения выбрать нельзя, и делать вид, что можно, — значит врать
//! читателю в каждой второй карточке.
//!
//! Теггер правиловый, а не обученный, и это осознанный выбор. Статистическая
//! модель дала бы процента три точности сверху и стоила бы десятков мегабайт
//! весов в мобильном пакете, недетерминированности разбора и невозможности
//! объяснить читателю, почему движок решил именно так. Правила же читаются,
//! проверяются по одному и всегда дают один и тот же ответ на один и тот же
//! текст — а для грамматического разбора, который потом показывают человеку
//! как объяснение, повторяемость важнее последнего процента.
//!
//! Где правила молчат, работает преобладание по корпусу
//! ([`Entry::dominant`](crate::lexicon::Entry::dominant)), и только если молчит
//! и оно — берётся первое значение из словаря.

mod function;

pub use function::{modal_base, Aux, AuxForm, Clause};

use crate::lexicon::{verb_roles, Lexicon, Pos, PosSet, VerbForm, VerbRole};
use crate::tokenizer::{Token, TokenKind};

/// Слово предложения вместе со всем, что о нём удалось выяснить.
#[derive(Debug, Clone)]
pub struct Word {
    /// Индекс в векторе токенов, из которого слово пришло. По нему клиент
    /// находит место на странице, чтобы подсветить разбор.
    pub token: usize,
    /// Как слово стоит в тексте, вместе с регистром.
    pub text: String,
    /// Оно же в нижнем регистре — по нему идут все сравнения.
    pub lower: String,
    /// Часть речи, выбранная по контексту.
    pub pos: Pos,
    /// Части речи, которые допускал словарь. Пустой набор — слова нет.
    pub candidates: PosSet,
    /// Часть речи, которой слово чаще всего оказывается в живом тексте.
    pub dominant: Option<Pos>,
    /// Служебная роль: вспомогательный глагол, модальный, «to», отрицание.
    pub aux: Option<Aux>,
    /// Форма служебного глагола: «is» настоящее, «was» прошедшее, «been»
    /// причастие. `None` у слова, которое служебным не является.
    pub aux_form: Option<AuxForm>,
    /// Вводит ли слово придаточное предложение.
    pub clause: Option<Clause>,
    /// Чем слово может быть как форма глагола.
    pub verb: Vec<VerbRole>,
    /// Стоит ли следом запятая, тире или конец предложения.
    ///
    /// Грамматике этого хватает вместо полного дерева разбора: границы
    /// придаточных в английском почти всегда отмечены запятой или союзом.
    pub breaks: bool,
}

impl Word {
    /// Может ли слово быть личной формой глагола — той, что образует время.
    ///
    /// Причастия и герундий сюда не входят: «reading» само по себе времени не
    /// образует, ему нужен «is» или «was» слева.
    pub fn is_finite_verb(&self) -> bool {
        self.aux.is_some_and(|a| a != Aux::To && a != Aux::Not)
            || self.verb.iter().any(|role| {
                role.forms.contains(VerbForm::Base)
                    || role.forms.contains(VerbForm::ThirdPerson)
                    || role.forms.contains(VerbForm::Past)
            })
    }

    /// Может ли слово занимать эту роль как форма глагола.
    pub fn has_form(&self, form: VerbForm) -> bool {
        self.verb.iter().any(|role| role.forms.contains(form))
    }

    /// Начальная форма глагола, если слово им может быть.
    pub fn verb_base(&self) -> Option<&str> {
        self.verb.first().map(|role| role.base.as_ref())
    }
}

/// Размечает предложение.
///
/// На вход идут токены целого предложения — разбор смотрит на соседей, и
/// обрывок фразы разберётся хуже, чем фраза целиком.
pub fn tag(lexicon: &Lexicon, tokens: &[Token]) -> Vec<Word> {
    let mut words = collect(lexicon, tokens);
    resolve(&mut words);
    words
}

/// Первый проход: всё, что видно по одному слову.
fn collect(lexicon: &Lexicon, tokens: &[Token]) -> Vec<Word> {
    let mut words = Vec::new();

    for (index, token) in tokens.iter().enumerate() {
        if token.kind != TokenKind::Word {
            // Знаки препинания словами не становятся, но отмечают границу для
            // предыдущего слова: по запятым находятся придаточные.
            if token.kind == TokenKind::Punctuation {
                if let Some(previous) = words.last_mut() {
                    let Word { breaks, .. } = previous;
                    *breaks = matches!(token.text.as_str(), "," | ";" | ":" | "—" | "–" | "-");
                }
            }
            continue;
        }

        // Книги печатают типографский апостроф: «don’t», «I’ve». Сводим его к
        // прямому здесь и один раз — иначе каждая таблица служебных слов и
        // каждая проверка на «n't» удваивались бы ради одного символа, и
        // где-нибудь про него однажды забыли бы.
        let lower = token.text.to_lowercase().replace('\u{2019}', "'");
        let function = function::lookup(&lower);
        let entry = lexicon.entry(&lower);
        let candidates = entry.map(|e| e.pos).unwrap_or(PosSet::EMPTY);
        let dominant = entry.and_then(|e| e.dominant);

        words.push(Word {
            token: index,
            text: token.text.clone(),
            pos: function
                .map(|f| f.pos)
                .or(dominant)
                .or_else(|| candidates.primary())
                .unwrap_or(Pos::Noun),
            candidates,
            dominant,
            aux: function.and_then(|f| f.aux),
            aux_form: function.map(|f| f.form),
            clause: function::clause_marker(&lower),
            verb: verb_roles(lexicon, &lower),
            lower,
            breaks: false,
        });
    }

    // У последнего слова граница есть всегда: дальше конец предложения.
    if let Some(last) = words.last_mut() {
        last.breaks = true;
    }

    words
}

/// Второй проход: уточнение по соседям.
///
/// Правила разбирают ровно те случаи, где словарь неоднозначен, и каждое
/// объяснимо одной фразой. Порядок неслучаен: чем правило надёжнее, тем раньше
/// оно стоит. Связка «is» перед словом почти не оставляет выбора; окончание
/// «-ly» оставляет чуть больше; преобладание по корпусу — последний довод.
fn resolve(words: &mut [Word]) {
    for index in 0..words.len() {
        // Служебные слова уточнять нечем: их роль и есть их значение.
        //
        // А вот единственное значение в словаре ничего не гарантирует, и
        // соблазн пропускать такие слова обманчив. «reading» словарь знает
        // только существительным, «crossed» — только прилагательным, потому
        // что WordNet держит формы отдельно от лемм. Ровно на них правила и
        // нужны: «was reading» — глагол, что бы ни говорил словарь.
        if words[index].aux.is_some() {
            continue;
        }

        let previous = index.checked_sub(1).map(|i| &words[i]);
        let next = words.get(index + 1);
        let current = &words[index];

        if let Some(pos) = decide(current, previous, next) {
            words[index].pos = pos;
        }
    }
}

/// Часть речи слова по его соседям, или `None`, если правила молчат.
fn decide(word: &Word, previous: Option<&Word>, next: Option<&Word>) -> Option<Pos> {
    // Служебные части речи — закрытый класс, и преобладание по корпусу на них
    // надёжнее любого правила о соседях. Без этой проверки «it» после «if»
    // становилось существительным (WordNet знает «IT» как отрасль), а вместе с
    // ним разваливалось и всё придаточное.
    if let Some(dominant) = word.dominant {
        if matches!(
            dominant,
            Pos::Pronoun | Pos::Determiner | Pos::Conjunction | Pos::Preposition | Pos::Particle
        ) {
            return Some(dominant);
        }
    }

    if let Some(previous) = previous {
        match previous.aux {
            // «to read», «can read», «did read» — после частицы, модального и
            // «do» стоит начальная форма глагола, и другого чтения нет.
            Some(Aux::To) | Some(Aux::Modal) | Some(Aux::Do) if word.has_form(VerbForm::Base) => {
                return Some(Pos::Verb)
            }
            // «is reading», «was broken» — после «be» идёт причастие. Оба
            // причастия сюда годятся: одно даёт продолженное время, другое —
            // страдательный залог, но часть речи у них одна.
            Some(Aux::Be)
                if word.has_form(VerbForm::Gerund) || word.has_form(VerbForm::Participle) =>
            {
                return Some(Pos::Verb)
            }
            // «has read» — после «have» идёт третья форма.
            Some(Aux::Have) if word.has_form(VerbForm::Participle) => return Some(Pos::Verb),
            _ => {}
        }

        // «the book», «a green lamp» — после определителя идёт именная группа.
        // Прилагательное внутри неё узнаётся по тому, что за ним есть чему
        // быть существительным: в «the green lamp» это «lamp», а в «the green»
        // ничего нет, и «green» само становится существительным.
        if previous.pos == Pos::Determiner {
            if word.candidates.contains(Pos::Adjective)
                && next.is_some_and(|n| n.candidates.contains(Pos::Noun))
            {
                return Some(Pos::Adjective);
            }
            if word.candidates.contains(Pos::Noun) {
                return Some(Pos::Noun);
            }
        }

        // «green lamp» — после прилагательного идёт существительное.
        if previous.pos == Pos::Adjective && word.candidates.contains(Pos::Noun) {
            return Some(Pos::Noun);
        }

        // «in the room», «of glass» — после предлога начинается именная группа.
        if previous.pos == Pos::Preposition && word.candidates.contains(Pos::Noun) {
            return Some(Pos::Noun);
        }

        // «she reads», «they book a table» — после местоимения-подлежащего
        // идёт сказуемое. Проверка на личную форму обязательна: в «she read»
        // это глагол, а в «her book» — существительное.
        if previous.pos == Pos::Pronoun && word.is_finite_verb() {
            return Some(Pos::Verb);
        }
    }

    // «quickly», «happily» — наречие узнаётся по окончанию. Правило стоит
    // после контекстных: в «the only way» соседи важнее окончания.
    if word.lower.ends_with("ly") && word.candidates.contains(Pos::Adverb) {
        return Some(Pos::Adverb);
    }

    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tokenizer::tokenize;

    /// Разбор одной строкой: «the/d green/a lamp/n».
    fn parts(sentence: &str) -> String {
        let tokens = tokenize(sentence);
        tag(Lexicon::embedded(), &tokens)
            .iter()
            .map(|w| format!("{}/{}", w.lower, w.pos.code() as char))
            .collect::<Vec<_>>()
            .join(" ")
    }

    #[test]
    fn именная_группа_разбирается_по_определителю() {
        // «green» бывает и существительным, и глаголом, но между «the» и
        // «lamp» ему остаётся одно место — определения.
        assert_eq!(parts("The green lamp."), "the/d green/a lamp/n");
    }

    #[test]
    fn то_же_слово_без_существительного_справа_становится_существительным() {
        // «the green» — это уже лужайка, а не цвет.
        assert_eq!(
            parts("She crossed the green."),
            "she/p crossed/v the/d green/n"
        );
    }

    #[test]
    fn после_модального_и_частицы_стоит_глагол() {
        // «book» по словарю прежде всего существительное — но не здесь.
        assert_eq!(parts("I can book a table."), "i/p can/v book/v a/d table/n");
        assert_eq!(
            parts("I want to book a table."),
            "i/p want/v to/t book/v a/d table/n"
        );
    }

    #[test]
    fn то_же_слово_после_определителя_остаётся_существительным() {
        // Обратная проверка к предыдущей: ложное срабатывание хуже пропуска.
        assert_eq!(parts("I read the book."), "i/p read/v the/d book/n");
    }

    #[test]
    fn причастие_после_связки_остаётся_глаголом() {
        assert_eq!(parts("She was reading."), "she/p was/v reading/v");
        assert_eq!(
            parts("The window was broken."),
            "the/d window/n was/v broken/v"
        );
    }

    #[test]
    fn наречие_узнаётся_по_окончанию() {
        assert_eq!(parts("He walked quickly."), "he/p walked/v quickly/r");
    }

    #[test]
    fn окончание_ly_не_перебивает_соседей() {
        // «only» кончается на «-ly», но между «the» и «way» это определение.
        assert_eq!(parts("The only way."), "the/d only/a way/n");
    }

    #[test]
    fn запятая_отмечает_границу_для_придаточных() {
        let tokens = tokenize("If it rains, we stay.");
        let words = tag(Lexicon::embedded(), &tokens);

        let rains = words.iter().find(|w| w.lower == "rains").expect("«rains»");
        assert!(rains.breaks, "перед придаточным граница обязана быть");
        assert_eq!(words[0].clause, Some(Clause::Condition));
    }
}
