//! Справочник грамматики.
//!
//! Собран не отдельным текстом, а из самих детекторов: у статьи хранится
//! только пример, а название, формула и объяснение берутся ровно оттуда,
//! откуда их получает читатель, ткнувший в предложение в книге.
//!
//! Это не экономия. Справочник, написанный отдельно, расходится с движком на
//! второй же правке — кто-то уточнит формулировку в одном месте и забудет о
//! другом, — и читатель увидит два разных объяснения одного правила. Здесь
//! разойтись нечему по устройству: если детектор изменит формулировку,
//! справочник изменится вместе с ним, а если правило перестанет срабатывать на
//! своём же примере, об этом скажет тест.
//!
//! Порядок статей — порядок освоения, а не алфавит: сперва времена, потом
//! залог, модальные, неличные формы и условные.

use crate::lexicon::Lexicon;
use crate::tokenizer::tokenize;

use super::{analyze, Finding};

/// Раздел справочника.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Topic {
    Tenses,
    Voice,
    Modals,
    Verbals,
    Conditionals,
}

impl Topic {
    pub fn title(self) -> &'static str {
        match self {
            Topic::Tenses => "Времена",
            Topic::Voice => "Залог",
            Topic::Modals => "Модальные глаголы",
            Topic::Verbals => "Неличные формы",
            Topic::Conditionals => "Условные предложения",
        }
    }

    /// Устойчивое имя для клиента — по нему он группирует статьи.
    pub fn code(self) -> &'static str {
        match self {
            Topic::Tenses => "tenses",
            Topic::Voice => "voice",
            Topic::Modals => "modals",
            Topic::Verbals => "verbals",
            Topic::Conditionals => "conditionals",
        }
    }
}

/// Статья справочника до того, как её объяснит движок.
#[derive(Debug, Clone, Copy)]
pub struct Entry {
    pub rule: &'static str,
    pub topic: Topic,
    /// Пример, на котором правило обязано сработать.
    pub example: &'static str,
    /// Перевод примера. Единственное, чего движок сам не знает: он разбирает
    /// грамматику, а не переводит.
    pub translation: &'static str,
    /// Кусок примера, который закрывают в упражнении на форму.
    ///
    /// Задан строкой, а не индексами: пример правят руками, а индексы после
    /// правки молча уезжают и закрывают не то слово.
    pub gap: &'static str,
    /// Три неверных варианта к этому пропуску.
    ///
    /// Написаны руками, и это единственное в справочнике, что движок не
    /// выводит сам: правильный ответ он знает, а вот чем его правдоподобно
    /// подменить — нет. Зато проверяет: тест подставляет каждый вариант в
    /// пример и требует, чтобы правило перестало срабатывать.
    pub wrong: &'static [&'static str],
    /// Когда правило уместно — то, чего нет в объяснении разбора, потому что
    /// там читатель уже смотрит на готовое предложение, а здесь выбирает.
    pub usage: &'static str,
}

/// Готовая статья: пример вместе с разбором от движка.
#[derive(Debug, Clone)]
pub struct Article {
    pub rule: &'static str,
    pub topic: Topic,
    pub title: &'static str,
    pub formula: &'static str,
    pub explanation: String,
    pub example: &'static str,
    pub translation: &'static str,
    pub usage: &'static str,
}

/// Все статьи, разобранные движком.
///
/// Разбор считается на месте, а не заранее: двадцать с небольшим предложений
/// разбираются за доли миллисекунды, а хранить копию объяснений значило бы
/// снова завести второй источник правды.
pub fn articles(lexicon: &Lexicon) -> Vec<Article> {
    ENTRIES
        .iter()
        .filter_map(|entry| article(lexicon, entry))
        .collect()
}

pub(super) fn article(lexicon: &Lexicon, entry: &Entry) -> Option<Article> {
    let finding = explain(lexicon, entry)?;
    Some(Article {
        rule: entry.rule,
        topic: entry.topic,
        title: finding.title,
        formula: finding.formula,
        explanation: finding.explanation,
        example: entry.example,
        translation: entry.translation,
        usage: entry.usage,
    })
}

/// Разбор примера тем же движком, что работает в читалке.
fn explain(lexicon: &Lexicon, entry: &Entry) -> Option<Finding> {
    analyze(lexicon, &tokenize(entry.example))
        .into_iter()
        .find(|finding| finding.rule == entry.rule)
}

/// Примеры справочника.
///
/// Каждый обязан срабатывать на своём правиле — это проверяется тестом. Если
/// правило переписали и оно перестало узнавать собственный пример, справочник
/// не «немного устареет»: тест не пройдёт.
pub(super) const ENTRIES: [Entry; 22] = [
    Entry {
        rule: "present-simple",
        topic: Topic::Tenses,
        example: "She reads every evening.",
        translation: "Она читает каждый вечер.",
        usage: "Привычки, расписания и то, что верно вообще: «я работаю здесь», \
                «поезд уходит в шесть».",
        gap: "reads",
        wrong: &["is reading", "has read", "read"],
    },
    Entry {
        rule: "present-continuous",
        topic: Topic::Tenses,
        example: "She is reading a book.",
        translation: "Она сейчас читает книгу.",
        usage: "То, что происходит в эту минуту или в эти дни. Ещё — раздражение: \
                «he is always losing his keys».",
        gap: "is reading",
        wrong: &["reads", "has read", "was reading"],
    },
    Entry {
        rule: "present-perfect",
        topic: Topic::Tenses,
        example: "She has read the book.",
        translation: "Она прочитала книгу.",
        usage: "Когда важен результат, а не время: «я уже поел». Со словом \
                «вчера» не сочетается — там нужен Past Simple.",
        gap: "has read",
        wrong: &["reads", "is reading", "had read"],
    },
    Entry {
        rule: "present-perfect-continuous",
        topic: Topic::Tenses,
        example: "She has been reading all morning.",
        translation: "Она читает всё утро.",
        usage: "Началось в прошлом и всё ещё идёт. Часто с «for» и «since»: \
                «for two hours», «since morning».",
        gap: "has been reading",
        wrong: &["has read", "is reading", "had been reading"],
    },
    Entry {
        rule: "past-simple",
        topic: Topic::Tenses,
        example: "She read the book yesterday.",
        translation: "Она прочитала книгу вчера.",
        usage: "Законченное действие в законченном прошлом. Основное время \
                повествования: рассказы и романы написаны им.",
        gap: "read",
        wrong: &["reads", "has read", "will read"],
    },
    Entry {
        rule: "past-continuous",
        topic: Topic::Tenses,
        example: "She was reading when he came.",
        translation: "Она читала, когда он вошёл.",
        usage: "Фон для другого события: одно длилось, второе случилось.",
        gap: "was reading",
        wrong: &["is reading", "read", "has been reading"],
    },
    Entry {
        rule: "past-perfect",
        topic: Topic::Tenses,
        example: "She had read the book before he came.",
        translation: "Она прочитала книгу до того, как он пришёл.",
        usage: "Прошлое раньше прошлого. Нужно только когда порядок событий \
                иначе непонятен.",
        gap: "had read",
        wrong: &["has read", "read", "was reading"],
    },
    Entry {
        rule: "past-perfect-continuous",
        topic: Topic::Tenses,
        example: "She had been reading all morning.",
        translation: "Она читала всё утро — до того момента.",
        usage: "Длилось до какого-то момента в прошлом и к нему уже шло давно.",
        gap: "had been reading",
        wrong: &["has been reading", "had read", "was reading"],
    },
    Entry {
        rule: "future-simple",
        topic: Topic::Tenses,
        example: "She will read the book.",
        translation: "Она прочитает книгу.",
        usage: "Решение, принятое сейчас, обещание или предсказание. Про планы, \
                составленные заранее, говорят «going to».",
        gap: "will read",
        wrong: &["reads", "has read", "will have read"],
    },
    Entry {
        rule: "future-continuous",
        topic: Topic::Tenses,
        example: "She will be reading at noon.",
        translation: "В полдень она будет читать.",
        usage: "Будет идти в названный момент. Ещё — вежливый вопрос о планах: \
                «will you be using the car?»",
        gap: "will be reading",
        wrong: &["will read", "is reading", "will have read"],
    },
    Entry {
        rule: "future-perfect",
        topic: Topic::Tenses,
        example: "She will have read the book by then.",
        translation: "К тому времени она уже прочитает книгу.",
        usage: "К названному сроку действие закончится. Почти всегда со словом \
                «by»: «by Friday», «by then».",
        gap: "will have read",
        wrong: &["will read", "has read", "will be reading"],
    },
    Entry {
        rule: "future-perfect-continuous",
        topic: Topic::Tenses,
        example: "She will have been reading for an hour.",
        translation: "К тому моменту она будет читать уже час.",
        usage: "Самое редкое из времён. Встречается там, где важна длительность \
                к будущему сроку.",
        gap: "will have been reading",
        wrong: &["will have read", "has been reading", "will be reading"],
    },
    Entry {
        rule: "passive-voice",
        topic: Topic::Voice,
        example: "The window was broken by the wind.",
        translation: "Окно разбило ветром.",
        usage: "Когда важнее действие, чем тот, кто его совершил, или когда \
                деятель неизвестен. «by» называет его, если нужно.",
        gap: "was broken",
        wrong: &["broke", "has broken", "was breaking"],
    },
    Entry {
        rule: "modal-verb",
        topic: Topic::Modals,
        example: "You must wait here.",
        translation: "Вам нужно подождать здесь.",
        usage: "Отношение говорящего к действию: возможно оно, обязательно, \
                желательно или всего лишь вероятно.",
        gap: "must wait",
        wrong: &["must have waited", "waited", "are waiting"],
    },
    Entry {
        rule: "modal-perfect",
        topic: Topic::Modals,
        example: "He must have left already.",
        translation: "Должно быть, он уже ушёл.",
        usage: "Догадка, упрёк или сожаление о прошлом. Что именно — зависит от \
                модального: «must have» это уверенность, «should have» — упрёк.",
        gap: "must have left",
        wrong: &["must leave", "has left", "left"],
    },
    Entry {
        rule: "infinitive",
        topic: Topic::Verbals,
        example: "She wants to read the book.",
        translation: "Она хочет прочитать книгу.",
        usage: "После want, decide, hope, promise и десятка других глаголов. \
                Какие требуют инфинитива, а какие герундия — приходится помнить.",
        gap: "to read",
        wrong: &["reading", "reads", "read"],
    },
    Entry {
        rule: "gerund",
        topic: Topic::Verbals,
        example: "She is good at reading.",
        translation: "Она хорошо читает.",
        usage: "После предлога всегда стоит форма на «-ing», а не инфинитив. \
                Это правило без исключений — редкость в английском.",
        gap: "reading",
        wrong: &["read", "to read", "reads"],
    },
    Entry {
        rule: "conditional-zero",
        topic: Topic::Conditionals,
        example: "If it rains, the streets get wet.",
        translation: "Если идёт дождь, улицы становятся мокрыми.",
        usage: "Общие истины и законы природы: всегда так, когда условие \
                выполняется. «if» здесь можно заменить на «when».",
        gap: "get",
        wrong: &["will get", "would get", "got"],
    },
    Entry {
        rule: "conditional-first",
        topic: Topic::Conditionals,
        example: "If it rains, we will stay home.",
        translation: "Если пойдёт дождь, мы останемся дома.",
        usage: "Реальное условие в будущем. После «if» стоит настоящее время, \
                хотя речь о будущем, — «will» там не ставят.",
        gap: "will stay",
        wrong: &["stay", "would stay", "stayed"],
    },
    Entry {
        rule: "conditional-second",
        topic: Topic::Conditionals,
        example: "If I had money, I would buy the house.",
        translation: "Будь у меня деньги, я бы купил этот дом.",
        usage: "Про настоящее, которого нет. Прошедшее время здесь не о прошлом: \
                оно и означает нереальность.",
        gap: "would buy",
        wrong: &["will buy", "bought", "would have bought"],
    },
    Entry {
        rule: "conditional-third",
        topic: Topic::Conditionals,
        example: "If she had asked, I would have helped her.",
        translation: "Если бы она попросила, я бы ей помог.",
        usage: "Сожаление о прошлом: этого не случилось, и изменить уже нечего.",
        gap: "would have helped",
        wrong: &["would help", "will help", "helped"],
    },
    Entry {
        rule: "conditional-mixed",
        topic: Topic::Conditionals,
        example: "If she had asked, I would be there now.",
        translation: "Попроси она тогда, я был бы там сейчас.",
        usage: "Условие о прошлом, следствие о настоящем. Смешивается ровно так, \
                как в жизни: тогда не сделал — сейчас расхлёбываю.",
        gap: "would be",
        wrong: &["would have been", "will be", "was"],
    },
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn каждая_статья_разбирается_своим_же_правилом() {
        // Главная проверка справочника. Если правило переписали и оно
        // перестало узнавать собственный пример, справочник не «немного
        // устареет» — тест не пройдёт.
        let lexicon = Lexicon::embedded();
        let missing: Vec<&str> = ENTRIES
            .iter()
            .filter(|entry| explain(lexicon, entry).is_none())
            .map(|entry| entry.rule)
            .collect();

        assert!(
            missing.is_empty(),
            "правила не сработали на своих примерах: {}",
            missing.join(", ")
        );
    }

    #[test]
    fn статья_несёт_объяснение_движка() {
        let articles = articles(Lexicon::embedded());
        let perfect = articles
            .iter()
            .find(|a| a.rule == "present-perfect")
            .expect("статья про Present Perfect");

        assert_eq!(perfect.title, "Present Perfect");
        assert_eq!(perfect.formula, "have/has + V3");
        assert!(!perfect.explanation.is_empty());
        assert_eq!(perfect.topic, Topic::Tenses);
    }

    #[test]
    fn разделы_идут_в_порядке_освоения() {
        // Не алфавит: читатель осваивает времена раньше условных, и справочник
        // обязан лежать в том же порядке, что и его путь.
        let articles = articles(Lexicon::embedded());
        let topics: Vec<&str> =
            articles
                .iter()
                .map(|a| a.topic.code())
                .fold(Vec::new(), |mut seen, code| {
                    if seen.last() != Some(&code) {
                        seen.push(code);
                    }
                    seen
                });

        assert_eq!(
            topics,
            vec!["tenses", "voice", "modals", "verbals", "conditionals"]
        );
    }

    #[test]
    fn у_каждого_правила_движка_есть_статья() {
        // Правило, которое движок находит в книге, но не может объяснить в
        // справочнике, — это тупик: читатель нажал «подробнее» и не нашёл
        // ничего.
        let lexicon = Lexicon::embedded();
        let mut found: Vec<&str> = Vec::new();

        for entry in ENTRIES {
            for finding in analyze(lexicon, &tokenize(entry.example)) {
                if !found.contains(&finding.rule) {
                    found.push(finding.rule);
                }
            }
        }

        let described: Vec<&str> = ENTRIES.iter().map(|e| e.rule).collect();
        let orphans: Vec<&&str> = found
            .iter()
            .filter(|rule| !described.contains(rule))
            .collect();

        assert!(orphans.is_empty(), "правила без статьи: {orphans:?}");
    }
}
