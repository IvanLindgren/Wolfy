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
    Syntax,
    Lexicon,
}

impl Topic {
    pub fn title(self) -> &'static str {
        match self {
            Topic::Tenses => "Времена",
            Topic::Voice => "Залог",
            Topic::Modals => "Модальные глаголы",
            Topic::Verbals => "Неличные формы",
            Topic::Conditionals => "Условные предложения",
            Topic::Syntax => "Сложные конструкции",
            Topic::Lexicon => "Слова и сочетания",
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
            Topic::Syntax => "syntax",
            Topic::Lexicon => "lexicon",
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
pub(super) const ENTRIES: [Entry; 57] = [
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
        rule: "future-going-to",
        topic: Topic::Tenses,
        example: "She is going to read the book.",
        translation: "Она собирается прочитать книгу.",
        usage: "Решение или план, принятый заранее, либо предсказание по \
                видимым признакам: «look at those clouds — it is going to rain».",
        gap: "is going to read",
        wrong: &["reads", "will read", "read"],
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
        rule: "modal-deduction",
        topic: Topic::Modals,
        example: "It must be John.",
        translation: "Это, должно быть, Джон.",
        usage: "Вывод о настоящем: уверенный («must be»), невозможный \
                («can't be») или осторожный («might be»). Про обязанность — \
                отдельная история",
        gap: "must be",
        wrong: &["should be", "will be", "is"],
    },
    Entry {
        rule: "modal-equivalent",
        topic: Topic::Modals,
        example: "She has to work on Sundays.",
        translation: "Ей приходится работать по воскресеньям.",
        usage: "Обязанность, которая спрягается: have to работает во всех \
                временах, где «must» бессилен. Ещё — be able to (умеет), \
                be allowed to (можно), be supposed to (положено)",
        gap: "has to work",
        wrong: &["works", "is working", "has worked"],
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
        rule: "perfect-infinitive",
        topic: Topic::Verbals,
        example: "She is glad to have finished the work.",
        translation: "Она рада, что закончила работу.",
        usage: "Действие поставлено раньше другого момента: рада, потому что \
                уже закончила. Часто после «would like» в сожалениях о прошлом",
        gap: "to have finished",
        wrong: &["to finish", "finished", "to finishing"],
    },
    Entry {
        rule: "passive-infinitive",
        topic: Topic::Verbals,
        example: "The work needs to be done today.",
        translation: "Работу нужно сделать сегодня.",
        usage: "Действие направлено на подлежащее: работа не сделает сама — \
                её сделают. После need, must, wants to",
        gap: "be done",
        wrong: &["do", "doing", "did"],
    },
    Entry {
        rule: "continuous-infinitive",
        topic: Topic::Verbals,
        example: "He seems to be working hard.",
        translation: "Кажется, он усердно работает.",
        usage: "Действие идёт прямо сейчас или в тот момент, о котором речь. \
                После seem, appear, pretend",
        gap: "be working",
        wrong: &["work", "worked", "working"],
    },
    Entry {
        rule: "bare-infinitive",
        topic: Topic::Verbals,
        example: "He made me laugh.",
        translation: "Он рассмешил меня.",
        usage: "После make, let и глаголов восприятия «to» не нужно: \
                «made me laugh», «saw him leave». По-русски разницы незаметно",
        gap: "made me laugh",
        wrong: &["wanted to laugh", "made us happy", "began to laugh"],
    },
    Entry {
        rule: "gerund-verb",
        topic: Topic::Verbals,
        example: "He avoided answering my question.",
        translation: "Он избегал отвечать на мой вопрос.",
        usage: "После enjoy, avoid, mind, suggest, keep глагол стоит только в \
                форме -ing. С инфинитивом смысл ломается или меняется",
        gap: "answering",
        wrong: &["the answer", "to answer", "answer"],
    },
    Entry {
        rule: "perfect-participle",
        topic: Topic::Verbals,
        example: "Having finished work, he went home.",
        translation: "Закончив работу, он пошёл домой.",
        usage: "Одно действие завершилось раньше другого. По-русски — \
                деепричастие совершенного вида: «закончив», а не «заканчивая»",
        gap: "Having finished",
        wrong: &["When he finished", "Before finishing", "While finishing"],
    },
    Entry {
        rule: "participle-clause",
        topic: Topic::Verbals,
        example: "Walking down the street, she met an old friend.",
        translation: "Гуляя по улице, она встретила старого друга.",
        usage: "Оборот с формой -ing и запятой: действие идёт параллельно \
                главному или объясняет его",
        gap: "Walking down the street",
        wrong: &["In the evening", "During the walk", "After dinner"],
    },
    Entry {
        rule: "present-participle",
        topic: Topic::Verbals,
        example: "The man standing there is my brother.",
        translation: "Человек, стоящий там, — мой брат.",
        usage: "Форма -ing на месте определения: «человек, который стоит». \
                Запятых нет, придаточное можно заменить на who is standing",
        gap: "standing",
        wrong: &["who stood", "over", "sat"],
    },
    Entry {
        rule: "past-participle",
        topic: Topic::Verbals,
        example: "The broken glass lay on the floor.",
        translation: "Разбитое стекло лежало на полу.",
        usage: "Третья форма на месте определения со страдательным смыслом: \
                стекло разбили. Иногда это уже прилагательное",
        gap: "broken",
        wrong: &["empty", "clean", "wet"],
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
    Entry {
        rule: "conditional-inversion",
        topic: Topic::Conditionals,
        example: "Had we known earlier, we would have acted.",
        translation: "Знаи мы раньше, мы бы подействовали.",
        usage: "«If» опущено, а вспомогательный глагол встал перед \
                подлежащим: книжный, слегка торжественный стиль",
        gap: "would have acted",
        wrong: &["act", "will act", "are acting"],
    },
    Entry {
        rule: "subjunctive-mood",
        topic: Topic::Conditionals,
        example: "It is important that he be present.",
        translation: "Важно, чтобы он присутствовал.",
        usage: "После слов требования и предложения глагол стоит в начальной \
                форме: «that he be», «that she go». Это не опечатка",
        gap: "be present",
        wrong: &["is present", "was present", "were present"],
    },
    Entry {
        rule: "wish-present",
        topic: Topic::Conditionals,
        example: "I wish I knew the answer.",
        translation: "Хотел бы я знать ответ.",
        usage: "Сожаление о настоящем: прошедшее время после wish говорит, \
                что сейчас всё наоборот — ответа я не знаю",
        gap: "wish",
        wrong: &["hope", "believe", "am glad"],
    },
    Entry {
        rule: "wish-past",
        topic: Topic::Conditionals,
        example: "She wishes she had studied harder.",
        translation: "Она жалеет, что не училась усерднее.",
        usage: "Сожаление о прошлом: предпрошедшее время показывает, что \
                изменить уже ничего нельзя",
        gap: "wishes",
        wrong: &["hopes", "says", "thinks"],
    },
    Entry {
        rule: "wish-would",
        topic: Topic::Conditionals,
        example: "I wish you would stop smoking.",
        translation: "Хотелось бы, чтобы ты бросил курить.",
        usage: "Недовольство чужим поведением: повлиять не можем, поэтому \
                «will» здесь превращается в «would»",
        gap: "wish",
        wrong: &["hope", "doubt", "see"],
    },
    Entry {
        rule: "complex-object",
        topic: Topic::Syntax,
        example: "I want you to stay here.",
        translation: "Я хочу, чтобы ты остался здесь.",
        usage: "После want, expect и подобных: два действия с разными \
                исполнителями. По-русски — придаточное с «чтобы»",
        gap: "you to stay",
        wrong: &["you stay", "to stay", "you staying"],
    },
    Entry {
        rule: "causative",
        topic: Topic::Syntax,
        example: "I had my car repaired yesterday.",
        translation: "Вчера мне починили машину.",
        usage: "have/get + объект + V3: действие совершил нанятый кто-то. \
                «Мне починили», а не «я починил»",
        gap: "my car repaired",
        wrong: &["a good rest", "lunch early", "two meetings"],
    },
    Entry {
        rule: "complex-subject",
        topic: Topic::Syntax,
        example: "She seems to know the answer.",
        translation: "Кажется, она знает ответ.",
        usage: "Три семейства: чужое мнение («is said to»), впечатление \
                («seems to»), вероятность («is likely to») — все про одно \
                подлежащее без придаточного",
        gap: "seems to know",
        wrong: &["knew", "knows", "is knowing"],
    },
    Entry {
        rule: "emphatic-do",
        topic: Topic::Syntax,
        example: "He does love you.",
        translation: "Он действительно тебя любит.",
        usage: "Вспомогательный do в утвердительном предложении = ударение. \
                Спор, заверение, противопоставление",
        gap: "does love",
        wrong: &["loves", "loved", "loving"],
    },
    Entry {
        rule: "inversion-negative",
        topic: Topic::Syntax,
        example: "Never have I seen such a mess.",
        translation: "Никогда я ещё не видел такого беспорядка.",
        usage: "Отрицательное наречие в начале фразы переворачивает порядок \
                слов, как в вопросе. Книжная эмфаза",
        gap: "Never have I seen",
        wrong: &["I have never seen", "We had never seen", "Never mind"],
    },
    Entry {
        rule: "inversion-place",
        topic: Topic::Syntax,
        example: "Here comes the bus.",
        translation: "Вот и идёт автобус.",
        usage: "Наречие места первым словом выталкивает подлежащее в конец. \
                Динамика: событие разворачивается на глазах",
        gap: "comes the bus",
        wrong: &["the bus stops", "he comes", "we are"],
    },
    Entry {
        rule: "inversion-echo",
        topic: Topic::Syntax,
        example: "So do I.",
        translation: "И я тоже.",
        usage: "Короткий отклик: So/Neither + служебный глагол + местоимение. \
                Глагол повторяет время первого предложения",
        gap: "do I",
        wrong: &["I do", "they do", "it seems"],
    },
    Entry {
        rule: "cleft-it",
        topic: Topic::Syntax,
        example: "It was John who broke the window.",
        translation: "Это Джон разбил окно.",
        usage: "Рамка «It is/was … that/who» выделяет слово голосом, которого \
                на письме нет: разбил именно Джон",
        gap: "It was John",
        wrong: &["He was John", "There was John", "That was John"],
    },
    Entry {
        rule: "cleft-what",
        topic: Topic::Syntax,
        example: "What I need is a cup of coffee.",
        translation: "Что мне нужно, так это чашка кофе.",
        usage: "Фраза собрана вокруг главного слова: сначала сказано, чего \
                хочется, потом названо само оно",
        gap: "What I need",
        wrong: &["Coffee", "This book", "Every morning"],
    },
    Entry {
        rule: "reported-speech",
        topic: Topic::Syntax,
        example: "He said he would come soon.",
        translation: "Он сказал, что скоро придёт.",
        usage: "Чужие слова, переданные позже: времена уходят на шаг назад. \
                Чужое «I will come» становится «he would come»",
        gap: "said",
        wrong: &["hopes", "says", "believes"],
    },
    Entry {
        rule: "relative-defining",
        topic: Topic::Syntax,
        example: "The book that changed my life was cheap.",
        translation: "Книга, изменившая мою жизнь, стоила дёшево.",
        usage: "Придаточное без запятой определяет существительное: без него \
                непонятно, о какой книге речь. Выбросить нельзя",
        gap: "book that changed",
        wrong: &["with pictures", "without pictures", "of poems"],
    },
    Entry {
        rule: "relative-nondefining",
        topic: Topic::Syntax,
        example: "My brother, who lives in Rome, is a doctor.",
        translation: "Мой брат, живущий в Риме, врач.",
        usage: "Запятая делает придаточное попутной подробностью: брат и так \
                один, а Рим — бонусная информация",
        gap: "who lives in Rome",
        wrong: &["living in Rome", "from Rome", "my old friend"],
    },
    Entry {
        rule: "purpose-clause",
        topic: Topic::Syntax,
        example: "He stood up in order to see better.",
        translation: "Он встал, чтобы лучше видеть.",
        usage: "Цель действия: in order to, so as to, so that. После первых \
                двух стоит начальная форма глагола",
        gap: "in order to",
        wrong: &["to", "because", "and"],
    },
    Entry {
        rule: "reason-clause",
        topic: Topic::Syntax,
        example: "The game was canceled because of the rain.",
        translation: "Игру отменили из-за дождя.",
        usage: "Причина: потому что (because + предложение) или из-за \
                (because of + существительное). Разница видна сразу",
        gap: "because of",
        wrong: &["despite", "during", "before"],
    },
    Entry {
        rule: "concession-clause",
        topic: Topic::Syntax,
        example: "Although it rained, we went out.",
        translation: "Хотя шёл дождь, мы вышли.",
        usage: "Уступка: although, though, whereas, despite. Факт признаётся, \
                но вывод он не меняет",
        gap: "Although",
        wrong: &["Because", "When", "If"],
    },
    Entry {
        rule: "comparison-as-as",
        topic: Topic::Lexicon,
        example: "She is as tall as her mother.",
        translation: "Она такого же роста, как её мама.",
        usage: "Рамка as … as означает равенство; с отрицанием not so … as — \
                неравенство",
        gap: "as tall as",
        wrong: &["very tall", "taller than", "so tall"],
    },
    Entry {
        rule: "comparison-the-more",
        topic: Topic::Lexicon,
        example: "The more you read, the more you know.",
        translation: "Чем больше читаешь, тем больше знаешь.",
        usage: "Пропорция: обе части растут вместе. По-русски — «чем … , \
                тем …»",
        gap: "The more",
        wrong: &["More often", "The best", "Often"],
    },
    Entry {
        rule: "phrasal-verb",
        topic: Topic::Lexicon,
        example: "She gave up smoking last year.",
        translation: "В прошлом году она бросила курить.",
        usage: "Смысл собирается из глагола и предлога вместе: give up это \
                «сдаться», а не «дать вверх». Разделяемые допускают \
                местоимение внутри: turn it on",
        gap: "gave up",
        wrong: &["stopped", "enjoyed", "postponed"],
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
            vec![
                "tenses",
                "voice",
                "modals",
                "verbals",
                "conditionals",
                "syntax",
                "lexicon"
            ]
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
