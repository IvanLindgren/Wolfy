//! Отрезок чтения: сколько текста считается «одним подходом».
//!
//! Открытая книга не имеет видимого конца, и это отдельная трудность — не для
//! всех, но для многих. Полоса прогресса главы отвечает на вопрос «сколько
//! осталось до конца главы», а нужен ответ на другой: «докуда я читаю
//! сейчас». Отрезок его и даёт: у него есть начало, конец и обозримый размер,
//! после которого можно честно остановиться.
//!
//! Правило здесь одно и всё оно про границу. Отрезок **никогда не кончается
//! посреди предложения**: остановка на полуфразе не отдых, а обрыв, к
//! которому потом надо возвращаться и перечитывать. Поэтому цель задаётся в
//! словах — их читатель считает временем, — а конец подтягивается к ближайшей
//! точке.
//!
//! Живёт в ядре, потому что это правило, а не оформление: отрезок, посчитанный
//! в браузере иначе, чем на телефоне, разошёлся бы с закладкой при
//! синхронизации.

use crate::tokenizer::{Sentence, Token, TokenKind};

/// Отрезок чтения в номерах токенов.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Segment {
    /// Первый токен отрезка.
    pub start: usize,
    /// Токен за последним — полуинтервал, как везде в ядре.
    pub end: usize,
    /// Сколько в отрезке слов. По ним интерфейс показывает и время, и остаток.
    pub words: usize,
    /// Сколько предложений вошло в отрезок.
    pub sentences: usize,
    /// Кончился ли вместе с отрезком и сам текст.
    ///
    /// Отличает «отрезок пройден, дальше есть ещё» от «дальше ничего нет»:
    /// в первом случае читателю предлагают следующий подход, во втором
    /// поздравляют с главой.
    pub last: bool,
}

impl Segment {
    /// Пустой отрезок в конце текста: читать нечего.
    fn finished(at: usize) -> Segment {
        Segment {
            start: at,
            end: at,
            words: 0,
            sentences: 0,
            last: true,
        }
    }
}

/// Строит отрезок примерно в `target_words` слов, начиная с токена `from`.
///
/// «Примерно» — потому что граница подтягивается к концу предложения. Отрезок
/// в сорок слов при цели в тридцать честнее отрезка, обрывающегося на
/// «he said and then».
///
/// Цель в ноль слов означает «одно предложение»: минимальный осмысленный
/// подход, ниже которого дробить уже нечего.
pub fn segment(
    tokens: &[Token],
    sentences: &[Sentence],
    from: usize,
    target_words: usize,
) -> Segment {
    if from >= tokens.len() {
        return Segment::finished(tokens.len());
    }

    // Предложение, внутри которого стоит закладка. Отрезок начинается с него
    // целиком: дочитывать чужой хвост — то же перечитывание, от которого
    // отрезок и защищает.
    let first = sentences
        .iter()
        .position(|sentence| sentence.tokens.end > from)
        .unwrap_or(sentences.len());

    if first >= sentences.len() {
        // Хвост без завершённых предложений — например, заголовок без точки.
        // Он и есть весь оставшийся отрезок.
        return Segment {
            start: from,
            end: tokens.len(),
            words: count_words(tokens, from, tokens.len()),
            sentences: 0,
            last: true,
        };
    }

    let start = sentences[first].tokens.start.min(from);
    let mut words = 0;
    let mut taken = 0;
    let mut end = start;

    for sentence in &sentences[first..] {
        words += count_words(tokens, sentence.tokens.start, sentence.tokens.end);
        taken += 1;
        end = sentence.tokens.end;
        if words >= target_words {
            break;
        }
    }

    // Остаток короче половины цели отдельным подходом не делается: подход в
    // три слова — это не подход, а строка, оставшаяся на экране.
    let tail = count_words(tokens, end, tokens.len());
    let last = if tail * 2 <= target_words {
        end = tokens.len();
        words += tail;
        true
    } else {
        end >= tokens.len()
    };

    Segment {
        start,
        end: end.min(tokens.len()),
        words,
        sentences: taken,
        last,
    }
}

/// Сколько слов между двумя токенами. Числа словами не считаются: «1925» глаз
/// проходит мгновенно, и на время чтения оно не влияет.
pub fn count_words(tokens: &[Token], from: usize, to: usize) -> usize {
    let to = to.min(tokens.len());
    if from >= to {
        return 0;
    }
    tokens[from..to]
        .iter()
        .filter(|token| token.kind == TokenKind::Word)
        .count()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tokenizer::{split, tokenize};

    fn разобрать(text: &str) -> (Vec<Token>, Vec<Sentence>) {
        let tokens = tokenize(text);
        let sentences = split(&tokens);
        (tokens, sentences)
    }

    const ТЕКСТ: &str = "One two three four five. Six seven eight nine ten. \
                         Eleven twelve thirteen fourteen fifteen. \
                         Sixteen seventeen eighteen nineteen twenty. \
                         Twenty one twenty two twenty three twenty four.";

    #[test]
    fn отрезок_кончается_на_границе_предложения() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        let segment = segment(&tokens, &sentences, 0, 7);

        // Цель — семь слов; два предложения по пять слов дают десять, и это
        // честнее, чем оборвать второе на середине.
        assert_eq!(segment.sentences, 2);
        assert_eq!(segment.words, 10);
        let end = sentences[1].tokens.end;
        assert_eq!(segment.end, end);
    }

    #[test]
    fn отрезок_начинается_с_начала_предложения() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        // Закладка стоит посреди второго предложения.
        let inside = sentences[1].tokens.start + 2;
        let segment = segment(&tokens, &sentences, inside, 5);
        assert_eq!(segment.start, sentences[1].tokens.start);
    }

    #[test]
    fn короткий_остаток_прирастает_к_отрезку() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        // Цель почти во весь текст: остаток в пять слов отдельным подходом
        // делать бессмысленно.
        let segment = segment(&tokens, &sentences, 0, 20);
        assert!(segment.last, "остаток обязан был прирасти");
        assert_eq!(segment.end, tokens.len());
        // Пять предложений: 5 + 5 + 5 + 5 + 8.
        assert_eq!(segment.words, 28);
    }

    #[test]
    fn отрезки_подряд_покрывают_текст_целиком_и_без_нахлёста() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        let mut at = 0;
        let mut covered = 0;
        let mut guard = 0;

        loop {
            let segment = segment(&tokens, &sentences, at, 5);
            assert!(segment.end > segment.start, "отрезок не двигается вперёд");
            covered += segment.words;
            at = segment.end;
            guard += 1;
            assert!(guard < 50, "отрезки не сходятся");
            if segment.last {
                break;
            }
        }

        assert_eq!(at, tokens.len());
        assert_eq!(covered, count_words(&tokens, 0, tokens.len()));
    }

    #[test]
    fn нулевая_цель_даёт_одно_предложение() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        let segment = segment(&tokens, &sentences, 0, 0);
        assert_eq!(segment.sentences, 1);
    }

    #[test]
    fn конец_текста_даёт_пустой_отрезок() {
        let (tokens, sentences) = разобрать(ТЕКСТ);
        let segment = segment(&tokens, &sentences, tokens.len(), 10);
        assert_eq!(segment.words, 0);
        assert!(segment.last);
    }

    #[test]
    fn текст_без_точек_целиком_один_отрезок() {
        let (tokens, sentences) = разобрать("Chapter One");
        let segment = segment(&tokens, &sentences, 0, 5);
        assert_eq!(segment.end, tokens.len());
        assert!(segment.last);
        assert_eq!(segment.words, 2);
    }

    #[test]
    fn числа_словами_не_считаются() {
        let (tokens, _) = разобрать("In 1925 he wrote 3 books.");
        assert_eq!(count_words(&tokens, 0, tokens.len()), 4);
    }
}
