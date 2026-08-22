//! Время и вид: Present Simple, Past Perfect Continuous и остальные двенадцать.
//!
//! Время в английском собирается из трёх независимых признаков, и разбирать
//! его списком из двенадцати образцов — значит переписывать одно и то же
//! двенадцать раз. Здесь признаки читаются по отдельности:
//!
//! * когда — по первому звену цепочки: «is» это настоящее, «was» прошедшее,
//!   «will» будущее;
//! * совершенный ли вид — по наличию служебного «have»;
//! * продолженный ли — по форме на «-ing».
//!
//! Название складывается из них, а не выбирается из таблицы. Поэтому «will
//! have been reading» разбирается тем же кодом, что и «reads», и добавить
//! тринадцатое сочетание, если оно найдётся, нечего.
//!
//! Модальные глаголы, кроме «will» и «shall», времени не образуют: «can read»
//! это не время, а возможность, и разбирает его свой детектор. Молчание здесь
//! не пропуск, а отказ отвечать не на свой вопрос.

use crate::lexicon::{Pos, VerbForm};
use crate::tagger::{modal_base, Aux, AuxForm, Word};

use super::chain::{chains, Chain, Link};
use super::Finding;

/// Когда происходит действие.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Time {
    Present,
    Past,
    Future,
}

pub fn detect(words: &[Word]) -> Vec<Finding> {
    let mut out: Vec<Finding> = chains(words)
        .iter()
        .filter_map(|chain| finding(words, chain))
        .collect();
    out.extend(going_to(words));
    out
}

/// Конструкция «to be going to»: «She is going to read».
///
/// Формально это продолженное время от «go», но смысл у него свой — план или
/// предсказание по признакам, — и читатель ищет в разборе именно его. Признак
/// узкий: слева служебное «be», дальше частица «to» и начальная форма глагола.
/// Последняя проверка отсекает «people going to work»: за «to» там стоит
/// существительное, и это просто направление движения, а не будущее время.
fn going_to(words: &[Word]) -> Vec<Finding> {
    let mut out = Vec::new();

    for index in 1..words.len() {
        if words[index].lower != "going" || !words[index].has_form(VerbForm::Gerund) {
            continue;
        }
        // Слева обязана быть форма be. В вопросе между ними стоит подлежащее
        // («Was he going…»), поэтому смотрим на два слова назад.
        let links_be = (1..=2).any(|back| match index.checked_sub(back) {
            Some(position) => {
                words[position].aux == Some(Aux::Be)
                    && (back == 1 || words[index - 1].pos == Pos::Pronoun)
            }
            None => false,
        });
        if !links_be {
            continue;
        }
        // Не «?»: за одним «going» без продолжения стоит конец предложения,
        // а не конец поиска — во второй половине фразы может быть ещё одно.
        let (Some(next), Some(verb)) = (words.get(index + 1), words.get(index + 2)) else {
            continue;
        };
        if next.aux == Some(Aux::To) && verb.has_form(VerbForm::Base) && verb.pos == Pos::Verb {
            out.push(Finding::new(
                "future-going-to",
                "Конструкция going to",
                "am/is/are + going to + V",
                "Решение или план, который был ещё до момента речи, либо \
                 предсказание по видимым признакам: вот-вот случится",
                words,
                index - 1..index + 3,
            ));
        }
    }

    out
}

fn finding(words: &[Word], chain: &Chain) -> Option<Finding> {
    let time = time(words, chain)?;
    let perfect = chain.is_perfect();
    let continuous = chain.is_continuous();

    let (rule, title, formula) = name(time, perfect, continuous);
    let explanation = explain(time, perfect, continuous, chain.is_negative());

    Some(Finding::new(
        rule,
        title,
        formula,
        explanation,
        words,
        chain.words.clone(),
    ))
}

/// Время всей цепочки несёт её первое звено — в английском только оно и
/// спрягается. У «had been reading» это «had», и потому время прошедшее, чем
/// бы ни кончалась цепочка.
fn time(words: &[Word], chain: &Chain) -> Option<Time> {
    let head = chain.head()?;

    if head.link == Link::Modal {
        // Будущее в английском строится модальным глаголом, и только двумя из
        // них. Остальные образуют не время, а отношение говорящего к действию.
        let word = words.get(head.word)?;
        return matches!(modal_base(&word.lower), "will" | "shall").then_some(Time::Future);
    }

    match head.form {
        AuxForm::Present => Some(Time::Present),
        AuxForm::Past => Some(Time::Past),
        // Начальная форма и причастия времени не несут: это либо повелительное
        // наклонение, либо кусок чужой цепочки, разобранный отдельно.
        AuxForm::Base | AuxForm::Participle | AuxForm::Gerund => None,
    }
}

/// Имя правила, заголовок и формула.
fn name(time: Time, perfect: bool, continuous: bool) -> (&'static str, &'static str, &'static str) {
    match (time, perfect, continuous) {
        (Time::Present, false, false) => ("present-simple", "Present Simple", "V / V-s"),
        (Time::Present, false, true) => (
            "present-continuous",
            "Present Continuous",
            "am/is/are + V-ing",
        ),
        (Time::Present, true, false) => ("present-perfect", "Present Perfect", "have/has + V3"),
        (Time::Present, true, true) => (
            "present-perfect-continuous",
            "Present Perfect Continuous",
            "have/has + been + V-ing",
        ),
        (Time::Past, false, false) => ("past-simple", "Past Simple", "V2"),
        (Time::Past, false, true) => ("past-continuous", "Past Continuous", "was/were + V-ing"),
        (Time::Past, true, false) => ("past-perfect", "Past Perfect", "had + V3"),
        (Time::Past, true, true) => (
            "past-perfect-continuous",
            "Past Perfect Continuous",
            "had + been + V-ing",
        ),
        (Time::Future, false, false) => ("future-simple", "Future Simple", "will + V"),
        (Time::Future, false, true) => {
            ("future-continuous", "Future Continuous", "will be + V-ing")
        }
        (Time::Future, true, false) => ("future-perfect", "Future Perfect", "will have + V3"),
        (Time::Future, true, true) => (
            "future-perfect-continuous",
            "Future Perfect Continuous",
            "will have been + V-ing",
        ),
    }
}

/// Объяснение по-человечески.
///
/// Формулировки живут здесь, а не в интерфейсе: одно и то же правило обязано
/// объясняться одинаково и в карточке, и в справочнике, и в тренировке.
/// И пишутся они так, как объяснил бы человек, а не учебник: «действие
/// началось в прошлом и продолжается сейчас», а не «перфектно-континуальная
/// форма индикатива».
fn explain(time: Time, perfect: bool, continuous: bool, negative: bool) -> String {
    let base = match (time, perfect, continuous) {
        (Time::Present, false, false) => {
            "Обычное положение дел: так бывает всегда, регулярно или вообще"
        }
        (Time::Present, false, true) => {
            "Происходит прямо сейчас или в эти дни, а не вообще и не всегда"
        }
        (Time::Present, true, false) => {
            "Уже случилось, и важен результат, а не когда именно это было"
        }
        (Time::Present, true, true) => "Началось в прошлом и продолжается до сих пор",
        (Time::Past, false, false) => {
            "Случилось и закончилось в прошлом — когда именно, обычно сказано рядом"
        }
        (Time::Past, false, true) => "Длилось в тот момент в прошлом, когда случилось что-то ещё",
        (Time::Past, true, false) => {
            "Случилось раньше другого прошлого события — то есть раньше прошлого"
        }
        (Time::Past, true, true) => {
            "Длилось какое-то время и к тому моменту в прошлом уже шло давно"
        }
        (Time::Future, false, false) => {
            "Произойдёт в будущем: решение принято сейчас или просто так будет"
        }
        (Time::Future, false, true) => {
            "Будет идти в тот самый момент в будущем, а не начнётся и кончится"
        }
        (Time::Future, true, false) => "К названному моменту в будущем действие уже завершится",
        (Time::Future, true, true) => "К названному моменту в будущем будет длиться уже долго",
    };

    if negative {
        format!("{base}. Здесь с отрицанием: действие не происходит")
    } else {
        base.to_string()
    }
}
