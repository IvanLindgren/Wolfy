//! Микро-упражнения по грамматике.
//!
//! Собраны из того же справочника и теми же детекторами: правильный ответ —
//! это то, что движок находит в примере, а не то, что кто-то записал рядом.
//! Поэтому упражнение не может разойтись с разбором, который читатель видел в
//! книге минуту назад, — а разошедшись, оно учило бы неправде.
//!
//! Заданий два рода, и они смотрят в разные стороны.
//!
//! [`Task::Form`] — узнать правило по названию и поставить нужную форму. Так
//! проверяется то, ради чего правило и учат: сказать самому.
//!
//! [`Task::Name`] — увидеть готовую фразу и назвать, что в ней происходит. Это
//! ровно то, что делает читалка, когда читатель тыкает в предложение; здесь он
//! делает это сам, а Wolfy проверяет.

use crate::lexicon::Lexicon;

use super::reference::{article, Entry, Topic, ENTRIES};

/// Чего требует упражнение.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Task {
    /// Поставить форму: правило названо, в предложении пропуск.
    Form,
    /// Назвать правило: предложение целиком, названия в вариантах.
    Name,
}

impl Task {
    /// Устойчивое имя для клиента.
    pub fn code(self) -> &'static str {
        match self {
            Task::Form => "form",
            Task::Name => "name",
        }
    }
}

/// Готовое упражнение.
#[derive(Debug, Clone)]
pub struct Exercise {
    /// Правило, которое тренируется. По нему упражнение связано с карточкой
    /// колоды и со статьёй справочника.
    pub rule: &'static str,
    pub topic: Topic,
    pub task: Task,
    /// Предложение. В задании на форму на месте конструкции стоит `___`.
    pub sentence: String,
    pub translation: &'static str,
    /// Что спрашивают. Для формы — название правила, для названия — пусто:
    /// вопрос там один и тот же, и повторять его в данных незачем.
    pub question: &'static str,
    pub options: Vec<String>,
    /// Номер верного варианта в [`Self::options`].
    pub answer: usize,
    /// Что показать после ответа: формула и объяснение движка.
    pub formula: &'static str,
    pub explanation: String,
}

/// Пропуск в предложении.
pub const GAP: &str = "___";

/// Все упражнения справочника.
///
/// Их ровно вдвое больше статей: каждое правило спрашивается и на форму, и на
/// узнавание. Порядок — порядок освоения, как и у самих статей; перемешивает
/// колода, а не генератор.
pub fn exercises(lexicon: &Lexicon) -> Vec<Exercise> {
    let titles = titles(lexicon);
    let mut out = Vec::with_capacity(ENTRIES.len() * 2);

    for entry in ENTRIES.iter() {
        if let Some(exercise) = form(lexicon, entry) {
            out.push(exercise);
        }
        if let Some(exercise) = name(lexicon, entry, &titles) {
            out.push(exercise);
        }
    }

    out
}

/// Упражнения одного правила.
pub fn exercises_for(lexicon: &Lexicon, rule: &str) -> Vec<Exercise> {
    exercises(lexicon)
        .into_iter()
        .filter(|e| e.rule == rule)
        .collect()
}

/// Задание на форму: правило названо, конструкция закрыта.
fn form(lexicon: &Lexicon, entry: &Entry) -> Option<Exercise> {
    let article = article(lexicon, entry)?;
    let sentence = blank(entry)?;

    let mut options = vec![entry.gap.to_string()];
    options.extend(entry.wrong.iter().map(|w| w.to_string()));
    let answer = place(&mut options, 0, entry.rule, Task::Form);

    Some(Exercise {
        rule: entry.rule,
        topic: entry.topic,
        task: Task::Form,
        sentence,
        translation: entry.translation,
        question: article.title,
        options,
        answer,
        formula: article.formula,
        explanation: article.explanation,
    })
}

/// Задание на узнавание: фраза целиком, назвать правило.
fn name(
    lexicon: &Lexicon,
    entry: &Entry,
    titles: &[(&'static str, Topic, &'static str)],
) -> Option<Exercise> {
    let article = article(lexicon, entry)?;

    let mut options = vec![article.title.to_string()];
    // Сначала соседи по разделу: спутать Present Perfect с Past Perfect —
    // настоящая ошибка, а спутать его с герундием невозможно, и такой вариант
    // просто сокращает выбор до трёх.
    for near in [true, false] {
        for (rule, topic, title) in titles {
            if options.len() >= 4 {
                break;
            }
            if *rule == entry.rule || (*topic == entry.topic) != near {
                continue;
            }
            if !options.iter().any(|o| o == title) {
                options.push(title.to_string());
            }
        }
    }
    if options.len() < 4 {
        return None;
    }

    let answer = place(&mut options, 0, entry.rule, Task::Name);

    Some(Exercise {
        rule: entry.rule,
        topic: entry.topic,
        task: Task::Name,
        sentence: entry.example.to_string(),
        translation: entry.translation,
        question: "",
        options,
        answer,
        formula: article.formula,
        explanation: article.explanation,
    })
}

/// Названия всех правил — из них берутся варианты для задания на узнавание.
fn titles(lexicon: &Lexicon) -> Vec<(&'static str, Topic, &'static str)> {
    ENTRIES
        .iter()
        .filter_map(|entry| article(lexicon, entry).map(|a| (entry.rule, entry.topic, a.title)))
        .collect()
}

/// Закрывает конструкцию в примере.
///
/// Пропуск ищется как подстрока, и её отсутствие — не «пустой ответ», а
/// сломанные данные: об этом говорит тест, а здесь упражнение просто не
/// собирается, чтобы читатель не увидел предложение без пропуска.
fn blank(entry: &Entry) -> Option<String> {
    let at = entry.example.find(entry.gap)?;
    let mut sentence = String::with_capacity(entry.example.len());
    sentence.push_str(&entry.example[..at]);
    sentence.push_str(GAP);
    sentence.push_str(&entry.example[at + entry.gap.len()..]);
    Some(sentence)
}

/// Ставит верный вариант на своё место.
///
/// Не случайно и не всегда первым: первый вариант читатель через десяток
/// упражнений начнёт выбирать не глядя, а случайный означал бы, что одно и то
/// же упражнение при повторе выглядит иначе — и запоминается не правило, а
/// расположение кнопки. Поэтому место считается от имени правила: у каждого
/// упражнения оно своё и всегда одно и то же.
fn place(options: &mut [String], from: usize, rule: &str, task: Task) -> usize {
    let to = slot(rule, task, options.len());
    options.swap(from, to);
    to
}

/// Номер места по имени правила — простая устойчивая свёртка.
fn slot(rule: &str, task: Task, count: usize) -> usize {
    let mut hash: u32 = 2_166_136_261;
    for byte in rule.bytes().chain(task.code().bytes()) {
        hash ^= byte as u32;
        hash = hash.wrapping_mul(16_777_619);
    }
    (hash as usize) % count.max(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grammar::analyze;
    use crate::tokenizer::tokenize;
    use std::collections::HashSet;

    /// Пример с подставленным вариантом.
    fn filled(entry: &Entry, option: &str) -> String {
        entry.example.replacen(entry.gap, option, 1)
    }

    #[test]
    fn верный_ответ_подтверждается_движком() {
        // Главная проверка. Ответ упражнения — не запись рядом с примером, а
        // то, что находит в предложении сам разбор.
        let lexicon = Lexicon::embedded();
        for entry in ENTRIES.iter() {
            let sentence = filled(entry, entry.gap);
            assert_eq!(
                sentence, entry.example,
                "пропуск не из примера: {}",
                entry.rule
            );

            let found = analyze(lexicon, &tokenize(&sentence))
                .into_iter()
                .any(|f| f.rule == entry.rule);
            assert!(
                found,
                "правило не сработало на своём примере: {}",
                entry.rule
            );
        }
    }

    #[test]
    fn неверный_вариант_действительно_неверен() {
        // Вариант, при котором правило всё равно срабатывает, — не ошибка
        // читателя, а ошибка данных: он ответил верно, а ему сказали «нет».
        let lexicon = Lexicon::embedded();
        for entry in ENTRIES.iter() {
            for option in entry.wrong {
                let sentence = filled(entry, option);
                let found = analyze(lexicon, &tokenize(&sentence))
                    .into_iter()
                    .any(|f| f.rule == entry.rule);
                assert!(!found, "«{option}» тоже даёт {}: {sentence}", entry.rule);
            }
        }
    }

    #[test]
    fn у_каждого_правила_два_упражнения() {
        let all = exercises(Lexicon::embedded());
        assert_eq!(all.len(), ENTRIES.len() * 2);

        for entry in ENTRIES.iter() {
            let mine = exercises_for(Lexicon::embedded(), entry.rule);
            let tasks: Vec<Task> = mine.iter().map(|e| e.task).collect();
            assert_eq!(
                tasks,
                vec![Task::Form, Task::Name],
                "правило {}",
                entry.rule
            );
        }
    }

    #[test]
    fn варианты_различны_и_ответ_на_месте() {
        for exercise in exercises(Lexicon::embedded()) {
            assert_eq!(exercise.options.len(), 4, "правило {}", exercise.rule);

            let unique: HashSet<&String> = exercise.options.iter().collect();
            assert_eq!(unique.len(), 4, "повтор в вариантах: {}", exercise.rule);

            assert!(exercise.answer < exercise.options.len());
        }
    }

    #[test]
    fn задание_на_форму_показывает_пропуск() {
        let all = exercises(Lexicon::embedded());
        let form = all
            .iter()
            .find(|e| e.rule == "present-perfect" && e.task == Task::Form)
            .expect("упражнение на форму");

        assert_eq!(form.sentence, "She ___ the book.");
        assert_eq!(form.question, "Present Perfect");
        assert_eq!(form.options[form.answer], "has read");
    }

    #[test]
    fn задание_на_узнавание_показывает_фразу_целиком() {
        let all = exercises(Lexicon::embedded());
        let name = all
            .iter()
            .find(|e| e.rule == "present-perfect" && e.task == Task::Name)
            .expect("упражнение на узнавание");

        assert_eq!(name.sentence, "She has read the book.");
        assert!(!name.sentence.contains(GAP));
        assert_eq!(name.options[name.answer], "Present Perfect");
    }

    #[test]
    fn варианты_узнавания_берутся_из_своего_раздела() {
        // Спутать два условных — настоящая ошибка. Спутать условное с
        // герундием нельзя, и такой вариант сокращает выбор до трёх.
        let all = exercises(Lexicon::embedded());
        let name = all
            .iter()
            .find(|e| e.rule == "conditional-second" && e.task == Task::Name)
            .expect("упражнение на узнавание");

        let conditionals: Vec<&String> = name
            .options
            .iter()
            .filter(|o| o.contains("услови") || o.contains("Услови"))
            .collect();
        assert_eq!(conditionals.len(), 4, "варианты: {:?}", name.options);
    }

    #[test]
    fn место_ответа_не_прыгает_между_запусками() {
        // Иначе повтор того же упражнения запоминался бы расположением кнопки.
        let first = exercises(Lexicon::embedded());
        let second = exercises(Lexicon::embedded());
        for (a, b) in first.iter().zip(second.iter()) {
            assert_eq!(a.answer, b.answer);
            assert_eq!(a.options, b.options);
        }
    }

    #[test]
    fn ответы_стоят_не_на_одном_месте() {
        let all = exercises(Lexicon::embedded());
        let places: HashSet<usize> = all.iter().map(|e| e.answer).collect();
        assert!(places.len() >= 3, "ответы сгрудились: {places:?}");
    }
}
