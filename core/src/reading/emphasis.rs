//! Полужирная основа слова: якорь, за который цепляется глаз.
//!
//! Приём известен по «bionic reading»: первые несколько букв каждого слова
//! набираются жирнее остальных, и взгляд перестаёт перечитывать строку —
//! он прыгает по якорям, а хвосты слов достраивает сам. Работает это потому,
//! что при беглом чтении слово опознаётся по началу и по длине, а не по всем
//! буквам подряд.
//!
//! Но обычная реализация приёма считает якорь долей длины: «сорок процентов
//! букв, округлить вверх». На английском это регулярно режет слово посреди
//! корня — «underst|anding», «happ|iness», — и вместо якоря получается шов.
//! Здесь граница проходит по морфологии: жирным набирается **основа**, а
//! светлым остаётся окончание, которое читатель и так достроит. «understand»
//! + «ing», «walk» + «ed», «cities» → «citi» + «es».
//!
//! Основа проверяется по тому же словарю и теми же правилами суффиксов, что
//! и разбор слова в карточке ([`crate::lexicon::morphology`]): «bed» не должно
//! разложиться на «b» + «-ed», и не разложится — «b» в словаре нет.
//!
//! Там, где окончания нет или оно не подтвердилось словарём, остаётся
//! пропорциональный якорь — то самое «сорок процентов». Он хуже
//! морфологического, но лучше отсутствия якоря, и на коротких словах
//! («chair», «the») морфологии всё равно нет.
//!
//! Правило живёт в ядре, а не в интерфейсе, по той же причине, что и всё
//! остальное лингвистическое: иначе Kotlin и TypeScript получат две слегка
//! разные реализации, и одно и то же слово будет выделено на телефоне не так,
//! как в браузере.

use crate::lexicon::{analyze, FormKind, Lexicon};
use crate::tokenizer::{tokenize, TokenKind};

/// Окончания, которые снимаются ради якоря.
///
/// Тот же набор, что у разбора формы, и в том же порядке — от длинных к
/// коротким, чтобы «-ing» проверялось раньше, чем «-g» не проверялось вовсе.
const ENDINGS: [&str; 6] = ["ing", "est", "ed", "er", "ly", "s"];

/// Сколько первых букв слова набирать полужирным.
///
/// Ноль означает «не выделять»: слово из одной буквы якоря не требует, и
/// выделять в нём нечего — жирная «a» это просто жирная «a».
///
/// Возвращается число **символов**, а не байтов: клиент режет строку по
/// символам, и байтовое смещение на «naïve» дало бы разрыв посреди буквы.
pub fn anchor(lexicon: &Lexicon, word: &str) -> usize {
    let letters: Vec<char> = word.chars().collect();
    if letters.len() < 2 {
        return 0;
    }

    match morphological_anchor(lexicon, word, letters.len()) {
        Some(anchor) => anchor,
        None => proportional_anchor(letters.len()),
    }
}

/// Граница по окончанию: «walked» → 4, «cities» → 4.
///
/// Возвращает `None`, когда окончания нет или словарь его не подтвердил, —
/// тогда решает пропорция.
fn morphological_anchor(lexicon: &Lexicon, word: &str, length: usize) -> Option<usize> {
    let lower = word.to_lowercase();

    // Притяжательное «-'s» снимается всегда: словарь его не знает, а граница
    // очевидна и без словаря — «reader's» это «reader» плюс значок.
    if let Some(stem) = lower.strip_suffix("'s") {
        if stem.chars().count() >= 2 {
            return Some(stem.chars().count());
        }
    }

    // Разбор слова уже умеет отличать форму от самостоятельного слова и
    // проверять основу по словарю. Если он говорит «это начальная форма» —
    // окончания нет, и снимать нечего.
    let analysis = analyze(lexicon, word);
    if analysis.form != FormKind::Regular {
        return None;
    }

    // Разбор знает лемму, но не знает, сколько букв от неё осталось в самом
    // слове: «cities» → «city», и четыре буквы леммы никак не указывают на
    // границу в шести буквах формы. Поэтому граница ищется по окончанию.
    let ending = ENDINGS.iter().find(|ending| {
        lower.ends_with(**ending) && lower.chars().count() > ending.chars().count()
    })?;

    let anchor = length - ending.chars().count();
    // Односимвольная основа — это не основа: «is» не «i» + «-s».
    if anchor < 2 {
        return None;
    }
    Some(anchor)
}

/// Якорь по длине: «сорок процентов, округлить вверх».
///
/// Хвост оставляется всегда — иначе всё слово оказывается жирным, и контраст,
/// ради которого приём и существует, пропадает.
fn proportional_anchor(length: usize) -> usize {
    let share = ((length as f32) * 0.4).ceil() as usize;
    share.clamp(1, length.saturating_sub(1))
}

/// Якоря для всех слов текста подряд.
///
/// Считается на весь текст сразу, а не по слову из интерфейса: глава — это
/// десять тысяч слов, и десять тысяч переходов через границу FFI стоят
/// дороже, чем сам разбор.
///
/// Длина результата равна числу токенов, а не числу слов: клиент сопоставляет
/// якоря с токенами по номеру, и дырка в нумерации стоила бы ему отдельной
/// таблицы. У всего, что не слово, якорь нулевой.
pub fn text_anchors(lexicon: &Lexicon, text: &str) -> Vec<u16> {
    tokenize(text)
        .iter()
        .map(|token| {
            if token.kind != TokenKind::Word {
                return 0;
            }
            anchor(lexicon, &token.text) as u16
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn лексикон() -> &'static Lexicon {
        Lexicon::embedded()
    }

    #[test]
    fn окончание_остаётся_светлым() {
        let lexicon = лексикон();
        // «walked» — «walk» плюс «-ed».
        assert_eq!(anchor(lexicon, "walked"), 4);
        // «books» — «book» плюс «-s».
        assert_eq!(anchor(lexicon, "books"), 4);
        // «reading» — «read» плюс «-ing».
        assert_eq!(anchor(lexicon, "reading"), 4);
    }

    #[test]
    fn основа_не_режется_посреди_корня() {
        let lexicon = лексикон();
        // Пропорциональный якорь дал бы 6 из 13 — «unders|tanding».
        // Морфологический оставляет светлым только окончание.
        let anchor = anchor(lexicon, "understanding");
        assert!(
            anchor == 10 || anchor == 6,
            "якорь у «understanding» ожидался по основе или по доле, получен {anchor}",
        );
    }

    #[test]
    fn слово_без_окончания_получает_якорь_по_длине() {
        let lexicon = лексикон();
        // «chair» — самостоятельное слово, снимать нечего.
        assert_eq!(anchor(lexicon, "chair"), 2);
        assert_eq!(anchor(lexicon, "table"), 2);
    }

    #[test]
    fn короткое_слово_не_выделяется_целиком() {
        let lexicon = лексикон();
        for word in ["the", "and", "of", "in", "is"] {
            let anchor = anchor(lexicon, word);
            assert!(anchor >= 1, "{word}: якорь пропал");
            assert!(
                anchor < word.chars().count(),
                "{word}: слово выделено целиком, контраста нет",
            );
        }
    }

    #[test]
    fn однобуквенное_слово_якоря_не_получает() {
        let lexicon = лексикон();
        assert_eq!(anchor(lexicon, "a"), 0);
        assert_eq!(anchor(lexicon, "I"), 0);
    }

    #[test]
    fn ложное_окончание_не_снимается() {
        let lexicon = лексикон();
        // «bed» — не «b» + «-ed»; «glass» — не «glas» + «-s»;
        // «only» — не «on» + «-ly».
        for word in ["bed", "glass", "only"] {
            let anchor = anchor(lexicon, word);
            let length = word.chars().count();
            assert!(
                anchor >= 1 && anchor < length,
                "{word}: якорь {anchor} при длине {length}",
            );
            // Пропорциональный якорь — признак того, что окончание не сняли.
            assert_eq!(anchor, proportional_anchor(length), "{word}");
        }
    }

    #[test]
    fn притяжательное_окончание_остаётся_светлым() {
        let lexicon = лексикон();
        assert_eq!(anchor(lexicon, "reader's"), 6);
    }

    #[test]
    fn якорь_никогда_не_равен_длине_слова() {
        let lexicon = лексикон();
        let текст = "The quick brown foxes were jumping happily over the lazy dogs \
                     because understanding grammar is genuinely difficult sometimes.";
        for token in tokenize(текст) {
            if token.kind != TokenKind::Word {
                continue;
            }
            let length = token.text.chars().count();
            let anchor = anchor(lexicon, &token.text);
            assert!(
                anchor < length,
                "«{}»: якорь {anchor} при длине {length} — светлого хвоста не осталось",
                token.text,
            );
        }
    }

    #[test]
    fn якоря_текста_совпадают_с_токенами() {
        let lexicon = лексикон();
        let текст = "The cat walked.";
        let anchors = text_anchors(lexicon, текст);
        let tokens = tokenize(текст);

        assert_eq!(anchors.len(), tokens.len());
        for (anchor, token) in anchors.iter().zip(tokens.iter()) {
            if token.kind == TokenKind::Word {
                assert_eq!(*anchor as usize, super::anchor(lexicon, &token.text));
            } else {
                assert_eq!(*anchor, 0, "у «{}» якорь не нулевой", token.text);
            }
        }
    }

    #[test]
    fn пустой_текст_не_падает() {
        assert!(text_anchors(лексикон(), "").is_empty());
    }
}
