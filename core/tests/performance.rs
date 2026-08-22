//! Пороги скорости ядра.
//!
//! Обещание продукта — карточка всплывает мгновенно, а книга открывается на
//! первой странице без ожидания. Обещание проверяется здесь, а не на глаз.
//!
//! Замеры имеют смысл только в release: в отладочной сборке Rust медленнее в
//! десятки раз, и порог в ней означал бы случайное число. Поэтому в debug тест
//! честно ничего не утверждает, а CI гоняет `cargo test --release`.

use std::time::Instant;

use wolfy_core::lexicon::{analyze, Lexicon};
use wolfy_core::tokenizer::{split, tokenize};

/// Кусок прозы, из которого собираются тексты нужного объёма.
const SAMPLE: &str = "The library smelled of dust, leather and old paper. Evelyn pushed \
the heavy door and stepped into the quiet hall. By the tall window stood a table where a \
serendipity of bookmarks lay scattered, notes left by readers long gone. She had been \
reading since dawn, and the margins were filled with her neat handwriting. Every \
unfamiliar word was underlined in pencil, waiting to be saved to her dictionary.";

/// Собирает текст примерно на `words` слов.
fn текст(words: usize) -> String {
    let per_sample = SAMPLE.split_whitespace().count();
    let repeats = words.div_ceil(per_sample);
    let mut out = String::with_capacity(repeats * SAMPLE.len() + repeats);
    for _ in 0..repeats {
        out.push_str(SAMPLE);
        out.push(' ');
    }
    out
}

/// Пропускается ли проверка порога — в отладочной сборке она бессмысленна.
fn только_замер() -> bool {
    cfg!(debug_assertions)
}

#[test]
fn токенизация_ста_тысяч_слов_укладывается_в_секунду() {
    let text = текст(100_000);

    let started = Instant::now();
    let tokens = tokenize(&text);
    let elapsed = started.elapsed();

    assert!(tokens.len() > 100_000, "разбор потерял токены");
    println!("токенизация 100 000 слов: {elapsed:?}");

    if !только_замер() {
        assert!(
            elapsed.as_millis() < 1_000,
            "токенизация 100 000 слов заняла {elapsed:?} — страница будет открываться с задержкой"
        );
    }
}

#[test]
fn разбор_слова_укладывается_в_пятнадцать_миллисекунд() {
    // Порог из правил слоя: столько ждёт карточка, всплывая по тапу.
    let lexicon = Lexicon::embedded();
    let words = [
        "serendipity",
        "children",
        "making",
        "unhurried",
        "had",
        "reading",
        "bookplate",
        "zzzqx",
    ];

    // Первое обращение прогревает словарь: разбирается он один раз за жизнь
    // процесса, и включать эту цену в замер тапа неправильно.
    let _ = analyze(lexicon, "warmup");

    for word in words {
        let started = Instant::now();
        let analysis = analyze(lexicon, word);
        let elapsed = started.elapsed();
        println!("разбор «{word}»: {elapsed:?} → {}", analysis.lemma);

        if !только_замер() {
            assert!(
                elapsed.as_millis() < 15,
                "разбор «{word}» занял {elapsed:?} — карточка будет заметно тормозить"
            );
        }
    }
}

#[test]
fn разбивка_на_предложения_не_квадратична() {
    // Детектор границ смотрит вперёд и назад, и легко написать его так, что
    // на длинной главе он начнёт захлёбываться. Проверяем, что вдесятеро
    // больший текст обрабатывается не более чем вдвадцатеро дольше.
    let малый = tokenize(&текст(10_000));
    let большой = tokenize(&текст(100_000));

    let started = Instant::now();
    let _ = split(&малый);
    let на_малом = started.elapsed();

    let started = Instant::now();
    let sentences = split(&большой);
    let на_большом = started.elapsed();

    assert!(!sentences.is_empty(), "предложения не нашлись");
    println!("предложения: {на_малом:?} → {на_большом:?}");

    if !только_замер() && на_малом.as_micros() > 0 {
        let рост = на_большом.as_micros() / на_малом.as_micros().max(1);
        assert!(
            рост < 20,
            "рост времени в {рост} раз при десятикратном тексте — разбивка квадратична"
        );
    }
}
