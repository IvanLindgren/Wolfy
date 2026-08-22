//! Глагольная цепочка: «had been reading», «will not have finished».
//!
//! Это общий фундамент почти всех детекторов, и потому он не детектор сам по
//! себе: он ничего не объясняет читателю, а только находит в предложении
//! группу сказуемого и раскладывает её на части. Времена, залог и модальность
//! читаются потом с одной и той же разложенной цепочки, каждое своим правилом
//! и независимо от остальных.
//!
//! Порядок частей в английском жёсткий и потому разбирается без грамматики
//! шире регулярной: модальный, затем «have», затем «be», затем смысловой
//! глагол. Между ними встают наречия («has never been reading»), а отрицание
//! стоит после первого служебного слова — оба случая цепочку не разрывают.

use std::ops::Range;

use crate::lexicon::VerbForm;
use crate::tagger::{Aux, AuxForm, Word};

/// Роль слова в цепочке.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Link {
    /// Модальный глагол: «will», «can», «would».
    Modal,
    /// «have» в совершенных временах.
    Have,
    /// «be» в продолженных временах и в страдательном залоге.
    Be,
    /// «do» в вопросе, отрицании и усилении.
    Do,
    /// Смысловой глагол — тот, ради которого цепочка и существует.
    Main,
}

/// Часть цепочки.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Part {
    /// Индекс слова в размеченном предложении.
    pub word: usize,
    pub link: Link,
    /// Форма: «is» — настоящее, «been» — причастие, «reading» — герундий.
    pub form: AuxForm,
}

/// Группа сказуемого целиком.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Chain {
    pub parts: Vec<Part>,
    /// Индекс слова «not» или «n't», если оно есть.
    pub negation: Option<usize>,
    /// Слова предложения, занятые цепочкой, — полуинтервал.
    pub words: Range<usize>,
}

impl Chain {
    /// Служебные части в порядке следования.
    pub fn auxiliaries(&self) -> impl Iterator<Item = &Part> {
        self.parts.iter().filter(|p| p.link != Link::Main)
    }

    /// Смысловой глагол, если он в цепочке есть.
    ///
    /// Его может не быть: в «She is tired» цепочка состоит из одной связки, и
    /// это не изъян разбора, а устройство предложения.
    pub fn main(&self) -> Option<&Part> {
        self.parts.iter().find(|p| p.link == Link::Main)
    }

    /// Первая часть — та, что несёт время всей цепочки.
    pub fn head(&self) -> Option<&Part> {
        self.parts.first()
    }

    /// Есть ли в цепочке служебное слово этого рода.
    pub fn has(&self, link: Link) -> bool {
        self.parts.iter().any(|p| p.link == link)
    }

    pub fn is_negative(&self) -> bool {
        self.negation.is_some()
    }

    /// Совершенный вид: в цепочке есть служебное «have».
    ///
    /// «have» в роли смыслового глагола («I have a book») сюда не попадает —
    /// звеном он в этом случае и не становится.
    pub fn is_perfect(&self) -> bool {
        self.has(Link::Have)
    }

    /// Продолженный вид.
    ///
    /// Обычно его видно по смысловому глаголу в форме на «-ing». Но в
    /// страдательном залоге смысловой глагол занят причастием («is being
    /// read»), и вид несёт служебное «being» — поэтому проверок две.
    pub fn is_continuous(&self) -> bool {
        self.main().is_some_and(|m| m.form == AuxForm::Gerund)
            || self
                .auxiliaries()
                .any(|p| p.link == Link::Be && p.form == AuxForm::Gerund)
    }

    /// Служебное «be», делающее залог страдательным.
    ///
    /// Признак один: смысловой глагол стоит в третьей форме, а прямо перед ним
    /// «be» в любом виде. Совершенное время сюда не попадает — там перед
    /// третьей формой стоит «have»: «has finished» это не залог.
    pub fn passive_be(&self) -> Option<&Part> {
        let main = self.main()?;
        if main.form != AuxForm::Participle {
            return None;
        }
        let position = self.parts.iter().position(|p| p.word == main.word)?;
        let previous = self.parts.get(position.checked_sub(1)?)?;
        (previous.link == Link::Be).then_some(previous)
    }

    pub fn is_passive(&self) -> bool {
        self.passive_be().is_some()
    }
}

/// Находит все глагольные цепочки предложения.
///
/// Проход один и слева направо; найденная цепочка сдвигает начало поиска за
/// свой конец, поэтому время работы линейно по длине предложения — правило
/// слоя запрещает детекторам быть квадратичными, и нарушить его проще всего
/// именно здесь.
pub fn chains(words: &[Word]) -> Vec<Chain> {
    let mut out = Vec::new();
    let mut index = 0;

    while index < words.len() {
        match chain_at(words, index) {
            Some(chain) => {
                index = chain.words.end;
                out.push(chain);
            }
            None => index += 1,
        }
    }

    out
}

/// Собирает цепочку, начинающуюся с этого слова, если она там есть.
fn chain_at(words: &[Word], start: usize) -> Option<Chain> {
    // После частицы «to» стоит инфинитив, а не сказуемое: «a way to spend an
    // evening» это не настоящее простое время. Разбирает инфинитив свой
    // детектор, и цепочке здесь делать нечего.
    if start > 0 && words[start - 1].aux == Some(Aux::To) {
        return None;
    }

    let mut parts: Vec<Part> = Vec::new();
    let mut negation = None;
    let mut index = start;
    let mut end = start;

    while index < words.len() {
        let word = &words[index];

        // Цепочка начинается только с того, что разбор счёл глаголом.
        // Без этой проверки «the book» в «She will read the book» становилось
        // вторым сказуемым: «book» бывает глаголом, и словаря тут мало —
        // ответ даёт определитель слева, который уже разобрал теггер.
        if parts.is_empty() && word.pos != crate::lexicon::Pos::Verb {
            break;
        }

        // Отрицание и наречия внутри цепочки её не разрывают: «has never
        // been», «will not have finished», «is quietly reading».
        if word.aux == Some(Aux::Not) {
            if parts.is_empty() {
                break;
            }
            negation = Some(index);
            index += 1;
            continue;
        }
        if !parts.is_empty() && word.pos == crate::lexicon::Pos::Adverb {
            index += 1;
            continue;
        }

        // Служебный глагол — очередное звено. Но только пока смысловой не
        // найден: в «is reading a book» второе «reading» уже не звено.
        if let (Some(aux), Some(form)) = (word.aux, word.aux_form) {
            let link = match aux {
                Aux::Modal => Link::Modal,
                Aux::Have => Link::Have,
                Aux::Be => Link::Be,
                Aux::Do => Link::Do,
                Aux::To | Aux::Not => break,
            };
            // Второй модальный подряд невозможен, и цепочка на нём кончается:
            // «will can» это не английский, а склеенные предложения.
            if link == Link::Modal && !parts.is_empty() {
                break;
            }
            // Отрицание бывает и зашито в служебное слово: «won't», «didn't»,
            // «cannot». Цепочка запоминает его так же, как отдельное «not».
            if negation.is_none() && (word.lower.ends_with("n't") || word.lower == "cannot") {
                negation = Some(index);
            }
            parts.push(Part {
                word: index,
                link,
                form,
            });
            index += 1;
            end = index;
            continue;
        }

        // Смысловой глагол. Он же конец цепочки.
        if let Some(form) = main_form(word, parts.last()) {
            parts.push(Part {
                word: index,
                link: Link::Main,
                form,
            });
            end = index + 1;
            break;
        }

        break;
    }

    if parts.is_empty() {
        return None;
    }

    // Служебный глагол без смыслового служебным и не был: в «I have a book»
    // это сказуемое, а не совершенное время, и «If I had money» — прошедшее
    // простое, а не предпрошедшее. Разница видна только по тому, нашёлся ли
    // за ним смысловой глагол, — поэтому решается здесь, а не при разборе.
    if !parts.iter().any(|p| p.link == Link::Main) {
        if let Some(last) = parts.last_mut() {
            last.link = Link::Main;
        }
    }

    Some(Chain {
        parts,
        negation,
        words: start..end,
    })
}

/// Форма смыслового глагола в этом месте цепочки.
///
/// Роль слова выбирается по тому, что стоит слева, а не по словарю: «read»
/// сам по себе годится на все три формы сразу, и без предыдущего звена выбрать
/// нельзя. После «have» это причастие, после «be» — герундий или причастие,
/// после модального — начальная форма, а в начале цепочки — личная форма.
fn main_form(word: &Word, previous: Option<&Part>) -> Option<AuxForm> {
    let Some(previous) = previous else {
        // Цепочка начинается со смыслового глагола: «She reads», «He came».
        // Годится только личная форма — причастие само сказуемым не бывает.
        if word.has_form(VerbForm::Past) {
            return Some(AuxForm::Past);
        }
        if word.has_form(VerbForm::ThirdPerson) || word.has_form(VerbForm::Base) {
            return Some(AuxForm::Present);
        }
        return None;
    };

    match previous.link {
        Link::Modal | Link::Do => word.has_form(VerbForm::Base).then_some(AuxForm::Base),
        Link::Have => word
            .has_form(VerbForm::Participle)
            .then_some(AuxForm::Participle),
        Link::Be => {
            if word.has_form(VerbForm::Gerund) {
                Some(AuxForm::Gerund)
            } else if word.has_form(VerbForm::Participle) {
                Some(AuxForm::Participle)
            } else {
                None
            }
        }
        Link::Main => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexicon::Lexicon;
    use crate::tagger::tag;
    use crate::tokenizer::tokenize;

    /// Цепочки предложения одной строкой: «Modal Have Be Main».
    fn shape(sentence: &str) -> String {
        let tokens = tokenize(sentence);
        let words = tag(Lexicon::embedded(), &tokens);
        chains(&words)
            .iter()
            .map(|chain| {
                chain
                    .parts
                    .iter()
                    .map(|part| format!("{:?}({})", part.link, words[part.word].lower))
                    .collect::<Vec<_>>()
                    .join(" ")
            })
            .collect::<Vec<_>>()
            .join(" | ")
    }

    #[test]
    fn длинная_цепочка_разбирается_целиком() {
        assert_eq!(
            shape("She will have been reading."),
            "Modal(will) Have(have) Be(been) Main(reading)"
        );
    }

    #[test]
    fn наречие_и_отрицание_цепочку_не_рвут() {
        assert_eq!(
            shape("She has never been reading."),
            "Have(has) Be(been) Main(reading)"
        );
        assert_eq!(shape("She will not go."), "Modal(will) Main(go)");
    }

    #[test]
    fn отрицание_запоминается() {
        let tokens = tokenize("She will not go.");
        let words = tag(Lexicon::embedded(), &tokens);
        let chains = chains(&words);

        assert!(chains[0].is_negative());
    }

    #[test]
    fn служебный_глагол_без_смыслового_становится_смысловым() {
        // «I have a book» — это сказуемое, а не совершенное время, и «If I had
        // money» — прошедшее простое, а не предпрошедшее. Разница видна только
        // по тому, нашёлся ли за служебным глаголом смысловой.
        assert_eq!(shape("I have a book."), "Main(have)");
        assert_eq!(shape("She is tired."), "Be(is) Main(tired)");
    }

    #[test]
    fn существительное_цепочку_не_начинает() {
        // «book» бывает глаголом, но после определителя это дополнение.
        // Без проверки «the book» становилось вторым сказуемым фразы.
        assert_eq!(shape("She will read the book."), "Modal(will) Main(read)");
    }

    #[test]
    fn инфинитив_сказуемым_не_становится() {
        assert_eq!(shape("A good way to spend an evening."), "");
    }

    #[test]
    fn страдательный_залог_отличается_от_совершенного_вида() {
        // Перед третьей формой стоит «be» — залог; стоит «have» — вид.
        let passive = tokenize("The window was broken.");
        let perfect = tokenize("She has finished.");
        let lexicon = Lexicon::embedded();

        assert!(chains(&tag(lexicon, &passive))[0].is_passive());
        assert!(!chains(&tag(lexicon, &perfect))[0].is_passive());
    }
}
