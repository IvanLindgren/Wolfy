//! Разбор грамматики на корпусе примеров.
//!
//! Примеры лежат отдельными файлами в `corpus/`, а не в коде тестов, и на то
//! есть причина. Корпус растёт вместе с правилами и живёт дольше любого из
//! них: правило можно переписать целиком, а предложение, на котором оно
//! обязано срабатывать, остаётся тем же. Держать такие примеры в исходниках
//! значит переписывать их при каждой перестройке детектора.
//!
//! Формат построчный:
//!
//! ```text
//! present-perfect<TAB>She has read the book.
//! !present-perfect<TAB>She read the book yesterday.
//! ```
//!
//! Строка с восклицательным знаком — пример, на котором правило сработать не
//! должно. Таких строк в корпусе не меньше, чем обычных, и это главное: ложное
//! срабатывание хуже пропуска, потому что читатель, увидевший неверный разбор,
//! перестаёт верить и верному.

use std::fs;
use std::path::Path;

use wolfy_core::grammar::analyze;
use wolfy_core::lexicon::Lexicon;
use wolfy_core::tokenizer::tokenize;

/// Одна строка корпуса.
struct Example {
    file: String,
    line: usize,
    rule: String,
    /// Обязано сработать или обязано промолчать.
    expected: bool,
    sentence: String,
}

fn corpus() -> Vec<Example> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/corpus");
    let mut out = Vec::new();

    let entries = fs::read_dir(&dir).expect("каталог корпуса");
    let mut files: Vec<_> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "tsv"))
        .collect();
    files.sort();

    assert!(!files.is_empty(), "корпус пуст: {}", dir.display());

    for path in files {
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("?")
            .to_string();
        let text = fs::read_to_string(&path).expect("файл корпуса");

        for (number, line) in text.lines().enumerate() {
            let line = line.trim_end();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let (rule, sentence) = line
                .split_once('\t')
                .unwrap_or_else(|| panic!("{}:{}: строка без табуляции: {line}", name, number + 1));
            let (expected, rule) = match rule.strip_prefix('!') {
                Some(rest) => (false, rest),
                None => (true, rule),
            };
            out.push(Example {
                file: name.clone(),
                line: number + 1,
                rule: rule.to_string(),
                expected,
                sentence: sentence.to_string(),
            });
        }
    }

    out
}

#[test]
fn корпус_разбирается_как_ожидается() {
    let lexicon = Lexicon::embedded();
    let mut failures = Vec::new();

    for example in corpus() {
        let findings = analyze(lexicon, &tokenize(&example.sentence));
        let fired = findings.iter().any(|f| f.rule == example.rule);

        if fired != example.expected {
            let actual: Vec<&str> = findings.iter().map(|f| f.rule).collect();
            failures.push(format!(
                "{}:{}: «{}»\n    ожидалось: {}{}\n    нашлось:   {}",
                example.file,
                example.line,
                example.sentence,
                if example.expected { "" } else { "НЕ " },
                example.rule,
                if actual.is_empty() {
                    "ничего".to_string()
                } else {
                    actual.join(", ")
                },
            ));
        }
    }

    assert!(
        failures.is_empty(),
        "не сошлось примеров: {}\n\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[test]
fn у_каждого_разбора_есть_объяснение_и_формула() {
    // Правило без формулы не запоминается, а без объяснения бесполезно.
    let lexicon = Lexicon::embedded();

    for example in corpus() {
        for finding in analyze(lexicon, &tokenize(&example.sentence)) {
            assert!(
                !finding.formula.is_empty(),
                "{}: правило {} без формулы",
                example.sentence,
                finding.rule
            );
            assert!(
                finding.explanation.chars().count() > 20,
                "{}: правило {} объяснено слишком коротко",
                example.sentence,
                finding.rule
            );
        }
    }
}

#[test]
fn разбор_укладывается_в_пятнадцать_миллисекунд() {
    // Требование слоя, и замеряется оно, а не ощущается: карточка открывается
    // по касанию, и разбор предложения стоит между касанием и кадром.
    let lexicon = Lexicon::embedded();
    let sentence = "If she had not been reading the book that the librarian had \
                    recommended, she would have gone home before the rain started.";
    let tokens = tokenize(sentence);

    // Прогрев: первый вызов разбирает встроенные словари.
    analyze(lexicon, &tokens);

    let start = std::time::Instant::now();
    for _ in 0..100 {
        analyze(lexicon, &tokens);
    }
    let each = start.elapsed() / 100;

    assert!(
        each.as_millis() < 15,
        "разбор предложения занял {each:?}, а обязан укладываться в 15 мс"
    );
}
