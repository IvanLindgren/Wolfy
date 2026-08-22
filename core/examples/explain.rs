//! Разбор предложения из командной строки — инструмент для разработки.
//!
//! Печатает разметку и всё, что нашли детекторы. Нужен ровно тогда, когда
//! правило не срабатывает и надо увидеть, каким предложение досталось движку:
//! по одному имени ненайденного правила это не понять.
//!
//! ```text
//! cargo run --example explain -- "She is good at reading."
//! ```

use wolfy_core::grammar::analyze;
use wolfy_core::lexicon::Lexicon;
use wolfy_core::tagger::tag;
use wolfy_core::tokenizer::tokenize;

fn main() {
    let sentence = std::env::args().skip(1).collect::<Vec<_>>().join(" ");
    if sentence.is_empty() {
        eprintln!("укажите предложение");
        return;
    }

    let lexicon = Lexicon::embedded();
    let tokens = tokenize(&sentence);

    println!("разметка:");
    for word in tag(lexicon, &tokens) {
        let forms: Vec<&str> = word
            .verb
            .iter()
            .flat_map(|r| r.forms.iter())
            .map(|f| f.label())
            .collect();
        println!(
            "  {:<12} {:<16} aux={:<8} {}{}",
            word.lower,
            word.pos.label(),
            word.aux.map(|a| format!("{a:?}")).unwrap_or_default(),
            forms.join("/"),
            if word.breaks {
                "  ←граница"
            } else {
                ""
            },
        );
    }

    println!("\nразбор:");
    for finding in analyze(lexicon, &tokens) {
        println!(
            "  {:<28} {} — {}",
            finding.rule, finding.formula, finding.explanation
        );
    }
}
