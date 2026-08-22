//! Служебные слова, о которых словарю верить нельзя.
//!
//! Лексикон собран из WordNet и корпуса Brown, и на знаменательных словах он
//! точен. На служебных — нет, и промахивается он там систематически: «may» для
//! него месяц май, «might» — могущество, «must» — сусло, «can» — жестянка. Это
//! не изъян сборки: в словаре эти значения действительно есть, просто в живом
//! тексте они встречаются в сотни раз реже модального глагола.
//!
//! Хуже другое: «is», «was», «has», «did» в лексиконе отсутствуют вовсе —
//! WordNet держит их в таблице исключений, а не среди начальных форм. Без
//! своей таблицы грамматический движок не опознал бы ни одного времени.
//!
//! Список закрытый и в языке не растёт: новых модальных глаголов и артиклей не
//! появляется. Поэтому он задан прямо здесь, а не собирается генератором.

use crate::lexicon::Pos;

/// Служебная роль слова — то, ради чего движок его вообще замечает.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Aux {
    /// Формы «be»: связка, страдательный залог, продолженные времена.
    Be,
    /// Формы «have»: совершенные времена.
    Have,
    /// Формы «do»: вопрос, отрицание, усиление.
    Do,
    /// Модальный глагол: «can», «must», «would».
    Modal,
    /// Частица «to» перед инфинитивом.
    To,
    /// Отрицание: «not», «n't».
    Not,
}

/// Форма служебного глагола.
///
/// Отдельно от [`VerbForm`](crate::lexicon::VerbForm), потому что отвечает на
/// другой вопрос. Тот описывает, чем форма может быть в принципе; этот —
/// какая она у конкретного вспомогательного слова, и ответ здесь всегда один.
/// «is» и «was» различаются только этим, а от различия зависит, Present
/// Perfect перед читателем или Past Perfect.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuxForm {
    /// Настоящее время: «is», «have», «does», «will».
    Present,
    /// Прошедшее: «was», «had», «did», «would».
    Past,
    /// Начальная форма: «be», сюда же «to have».
    Base,
    /// Причастие прошедшего времени: «been», «done», «had».
    Participle,
    /// Причастие настоящего времени: «being», «having».
    Gerund,
}

/// Что закрытый список знает о слове.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FunctionWord {
    pub pos: Pos,
    pub aux: Option<Aux>,
    /// Начальная форма — «is» → «be». Нужна, чтобы движок сравнивал глаголы,
    /// а не их написания.
    ///
    /// `None` у слова, которое само себе начальная форма: у модальных других
    /// форм и не бывает, в том и разница между «can» и обычным глаголом.
    pub lemma: Option<&'static str>,
    pub form: AuxForm,
}

/// Ищет слово в закрытом списке.
///
/// Проверять надо до словаря: смысл таблицы в том, чтобы перебить его ответ.
///
/// Слово приходит в нижнем регистре и с прямым апострофом — приводит его к
/// такому виду теггер, до всякого разбора.
pub fn lookup(word: &str) -> Option<FunctionWord> {
    use AuxForm::*;

    let (pos, aux, lemma, form) = match word {
        // Модальные. Своих неличных форм у них нет, спряжения тоже — потому
        // они и не глаголы в обычном смысле, а отдельный класс.
        // Настоящее и прошедшее у модальных различается только формой, и это
        // различие несёт смысл: «will» это будущее, «would» — условное.
        "can" | "will" | "shall" | "may" | "must" | "ought" => {
            (Pos::Verb, Some(Aux::Modal), None, Present)
        }
        "could" | "would" | "should" | "might" => (Pos::Verb, Some(Aux::Modal), None, Past),

        // Сокращения живут одним токеном: токенизатор хранит апостроф внутри
        // слова. Отрицание здесь зашито в само слово, и цепочка запоминает его
        // по окончанию «n't» — отдельного «not» при этом не придёт. В таблицу
        // идут только те сокращения, где отрицание не меняет роль слова;
        // «need» без «n't» сюда не попало сознательно: чаще он смысловой
        // глагол («I need water»), чем модальный.
        "won't" => (Pos::Verb, Some(Aux::Modal), None, Present),
        "can't" | "cannot" => (Pos::Verb, Some(Aux::Modal), None, Present),
        "couldn't" | "shouldn't" | "wouldn't" | "mightn't" => {
            (Pos::Verb, Some(Aux::Modal), None, Past)
        }
        "mustn't" | "needn't" => (Pos::Verb, Some(Aux::Modal), None, Present),
        "shan't" => (Pos::Verb, Some(Aux::Modal), None, Present),
        "don't" | "doesn't" => (Pos::Verb, Some(Aux::Do), Some("do"), Present),
        "didn't" => (Pos::Verb, Some(Aux::Do), Some("do"), Past),
        "isn't" | "aren't" => (Pos::Verb, Some(Aux::Be), Some("be"), Present),
        "wasn't" | "weren't" => (Pos::Verb, Some(Aux::Be), Some("be"), Past),
        "hasn't" | "haven't" => (Pos::Verb, Some(Aux::Have), Some("have"), Present),
        "hadn't" => (Pos::Verb, Some(Aux::Have), Some("have"), Past),

        // Формы «be». Их восемь, и это единственный глагол английского с таким
        // числом форм — поэтому список, а не правило.
        "be" => (Pos::Verb, Some(Aux::Be), Some("be"), Base),
        "am" | "is" | "are" => (Pos::Verb, Some(Aux::Be), Some("be"), Present),
        "was" | "were" => (Pos::Verb, Some(Aux::Be), Some("be"), Past),
        "been" => (Pos::Verb, Some(Aux::Be), Some("be"), Participle),
        "being" => (Pos::Verb, Some(Aux::Be), Some("be"), Gerund),

        "have" | "has" => (Pos::Verb, Some(Aux::Have), Some("have"), Present),
        // «had» бывает и сказуемым в прошедшем, и причастием в «had had».
        // Различает их место в цепочке, а не само слово, — здесь прошедшее,
        // а разбор цепочки при нужде прочтёт его иначе.
        "had" => (Pos::Verb, Some(Aux::Have), Some("have"), Past),
        "having" => (Pos::Verb, Some(Aux::Have), Some("have"), Gerund),

        "do" | "does" => (Pos::Verb, Some(Aux::Do), Some("do"), Present),
        "did" => (Pos::Verb, Some(Aux::Do), Some("do"), Past),
        "doing" => (Pos::Verb, Some(Aux::Do), Some("do"), Gerund),

        // «to» перед глаголом — частица инфинитива, перед существительным —
        // предлог. Разделить их может только разбор, поэтому здесь частица, а
        // предлог восстанавливается по соседям.
        "to" => (Pos::Particle, Some(Aux::To), None, Base),

        "not" | "n't" | "nt" => (Pos::Adverb, Some(Aux::Not), Some("not"), Base),

        _ => return None,
    };

    Some(FunctionWord {
        pos,
        aux,
        lemma,
        form,
    })
}

/// Тип придаточного, который вводит слово.
///
/// Отдельно от [`Aux`], потому что это про устройство предложения, а не про
/// глагольную цепочку: по этим словам находятся границы придаточных, без
/// которых условные предложения не разобрать.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Clause {
    /// Условие: «if», «unless».
    Condition,
    /// Время: «when», «before», «after», «until».
    Time,
    /// Причина, уступка и прочее: «because», «although».
    Other,
}

/// Модальный глагол без отрицания: «won't» → «will», «cannot» → «can».
///
/// Отрицание меняет смысл фразы, но не то, какое это правило: «won't come» —
/// то же будущее время, что «will come», а «shouldn't have» — тот же упрёк.
/// Детекторы спрашивают про правило, поэтому спрашивают отсюда, а не сверяют
/// написание: иначе каждый из них пришлось бы учить всем сокращениям по
/// отдельности, и каждый забыл бы своё.
pub fn modal_base(word: &str) -> &str {
    match word {
        "won't" => "will",
        "shan't" => "shall",
        "can't" | "cannot" => "can",
        "'ll" => "will",
        other => other.strip_suffix("n't").unwrap_or(other),
    }
}

/// Вводит ли слово придаточное предложение.
pub fn clause_marker(word: &str) -> Option<Clause> {
    Some(match word {
        "if" | "unless" | "provided" | "providing" | "suppose" | "supposing" => Clause::Condition,
        "when" | "whenever" | "while" | "before" | "after" | "until" | "till" | "once"
        | "since" => Clause::Time,
        "because" | "although" | "though" | "whereas" | "whether" | "so" => Clause::Other,
        _ => return None,
    })
}
