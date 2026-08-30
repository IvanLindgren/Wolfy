/**
 * Формы, которыми разговаривают ядро и веб.
 *
 * **Имена полей совпадают с именами в ядре и на сервере до буквы.** `bookId`
 * в одном месте и `book_id` в другом — это баг протокола, а не мелочь: он не
 * ломает сборку, он молча теряет данные, и находится через неделю по жалобе
 * «книга не помнит страницу».
 *
 * Второе правило границы: **незнакомые поля игнорируются**. Новое поле в
 * ядре не должно ломать задеплоенную веб-версию, поэтому здесь нет ни одной
 * проверки формы во время выполнения — только типы, которые компилятор
 * проверяет на нашей стороне.
 *
 * Источники истины: `core/src/ffi/dto.rs` (ответы разбора),
 * `core/src/ffi/session.rs` (команды и `Outcome`), `core/src/library/book.rs`,
 * `core/src/srs/*`, `core/src/settings.rs`.
 */

// --- Разбор слова -----------------------------------------------------------

/** Одна строка морфологического разбора: «множественное число», «-ed». */
export interface Fact {
  label: string
  value: string
}

/** Части речи в universal tagset — так их читает и сервер, и ядро. */
export type PosTag =
  | 'NOUN'
  | 'VERB'
  | 'ADJ'
  | 'ADV'
  | 'PRON'
  | 'DET'
  | 'ADP'
  | 'CONJ'
  | 'PART'
  | 'PRT'
  | 'NUM'
  | 'X'

/** Как слово соотносится со своей начальной формой. */
export type FormKind = 'lemma' | 'regular' | 'irregular' | 'unknown'

export interface WordAnalysis {
  /** Слово так, как оно стоит в тексте. */
  surface: string
  lemma: string
  /** Части речи начальной формы. */
  pos: PosTag[]
  /**
   * Часть речи, по которой слово разобралось: у «glowed» это `VERB`, хотя
   * лемма «glow» бывает и существительным. Пусто у начальной формы.
   */
  matchedPos?: PosTag
  /**
   * Часть речи, которой слово чаще всего оказывается в живом тексте: у
   * «green» это прилагательное. Берётся, когда разбор формы ничего не уточнил.
   */
  dominantPos?: PosTag
  form: FormKind
  facts: Fact[]
  /** Частотность по шкале Zipf: 6 — «the», 4 — обычное книжное слово. */
  zipf: number
  cefr: string
  /** Нашлось ли слово в словаре. */
  known: boolean
}

// --- Токены и предложения ---------------------------------------------------

export type TokenKind = 'word' | 'number' | 'punctuation' | 'space'

/**
 * Токен с позицией.
 *
 * Позиции — в единицах UTF-16, то есть ровно в тех индексах, которыми
 * оперируют строки JavaScript. Резать текст по ним можно напрямую.
 */
export interface Token {
  kind: TokenKind
  start: number
  end: number
  text: string
}

export interface Sentence {
  start: number
  end: number
  /** Индексы токенов этого предложения — полуинтервал `[firstToken, lastToken)`. */
  firstToken: number
  lastToken: number
  text: string
}

export interface TokenizedText {
  tokens: Token[]
  sentences: Sentence[]
}

/** Компактный токен — без дублирования текста, только смещения UTF-16. */
export interface CompactToken {
  kind: TokenKind
  start: number
  end: number
}

export interface CompactSentence {
  start: number
  end: number
  firstToken: number
  lastToken: number
}

/**
 * Глагольная цепочка главы в смещениях UTF-16.
 *
 * Приезжает вместе с главой, чтобы тап по служебному глаголу расширялся без
 * похода в разбор на каждое касание.
 */
export interface CompactChain {
  start: number
  end: number
  /** Начало смыслового глагола: по нему отличают связку от самого глагола. */
  mainStart: number
}

/** Глава с компактными токенами — один тяжёлый переход (§15). */
export interface PreparedChapter {
  title: string | null
  blocks: Block[]
  tokens: CompactToken[]
  sentences: CompactSentence[]
  chains?: CompactChain[]
}

/**
 * Отрезок чтения: докуда честно читать за один подход.
 *
 * Считает ядро, потому что граница обязана совпадать между устройствами:
 * отрезок, посчитанный в браузере иначе, чем на телефоне, разошёлся бы с
 * закладкой при синхронизации.
 */
export interface ReadingSegment {
  start: number
  /** Полуинтервал: `end` не входит. */
  end: number
  /** Сколько в отрезке слов. По ним считается и время, и остаток. */
  words: number
  sentences: number
  /** Кончился ли вместе с отрезком и сам текст главы. */
  last: boolean
}

/** Граф предложения (из Rust, §16). */
export interface GraphWord {
  text: string
  tag: string | null
}

export interface GraphLink {
  from: number
  to: number
  label: string
}

export interface InspectResult {
  word: WordAnalysis
  tokens: CompactToken[]
  sentences: CompactSentence[]
  findings: Finding[]
  chunks: Chunk[]
  markers: Marker[]
  parts: ContextPart[]
  graphWords: GraphWord[]
  graphLinks: GraphLink[]
}

// --- Разбор предложения -----------------------------------------------------

export interface Finding {
  /** Устойчивое имя правила: `present-perfect`. По нему открывается справка. */
  rule: string
  title: string
  /** Схема формулы: «have/has + V3». */
  formula: string
  explanation: string
  /** Токены, к которым относится разбор, — полуинтервал. */
  start: number
  end: number
}

export type RoleName =
  | 'subject'
  | 'predicate'
  | 'object'
  | 'complement'
  | 'adverbial'
  | 'connector'

/** Синтаксическая роль группы слов. */
export interface Chunk {
  role: RoleName
  title: string
  /** Часть речи, чьим цветом красить группу. */
  tint: PosTag
  start: number
  end: number
  /** Главное слово группы — от него и к нему ведут стрелки графа. */
  head: number
}

/**
 * Грамматический маркер: вспомогательный глагол, окончание, частица, предлог.
 *
 * Именно они, а не «род», несут грамматику английского, и подсвечиваются
 * отдельным слоем: маркер подчёркивается, а не заливается — заливка спорит
 * с чтением.
 */
export interface Marker {
  token: number
  /** Границы внутри самого слова: у «-ed» это две последние буквы. */
  from: number
  to: number
  kind: 'auxiliary' | 'ending' | 'particle' | 'preposition'
  rule: string
  note: string
}

/** Часть речи слова, выбранная теггером по всему предложению. */
export interface ContextPart {
  /** Индекс в массиве токенов предложения. */
  token: number
  pos: PosTag
}

export interface Grammar {
  /** Необязательно для совместимости с уже закешированной старой WASM-сборкой. */
  parts?: ContextPart[]
  findings: Finding[]
  chunks: Chunk[]
  markers: Marker[]
}

// --- Справочник и упражнения ------------------------------------------------

export type TopicCode =
  | 'tenses'
  | 'voice'
  | 'modals'
  | 'verbals'
  | 'conditionals'
  | 'syntax'
  | 'lexicon'

export interface Article {
  rule: string
  topic: TopicCode
  topicTitle: string
  title: string
  formula: string
  explanation: string
  example: string
  translation: string
  /** Когда правило уместно — того, чего нет в разборе готовой фразы. */
  usage: string
}

export interface Reference {
  articles: Article[]
}

export interface Exercise {
  rule: string
  topic: TopicCode
  /** `form` — поставить форму, `name` — назвать правило. */
  task: string
  /** Предложение. В задании на форму на месте конструкции стоит `___`. */
  sentence: string
  translation: string
  question: string
  options: string[]
  answer: number
  formula: string
  explanation: string
}

export interface Exercises {
  exercises: Exercise[]
}

// --- Книга ------------------------------------------------------------------

export interface ChapterInfo {
  title: string | null
}

export interface BookMetadata {
  title: string | null
  author: string | null
  language: string | null
  /** Путь к обложке внутри книги — достаётся отдельным вызовом. */
  cover: string | null
  chapters: ChapterInfo[]
}

export type BlockKind =
  | 'heading'
  | 'paragraph'
  | 'quote'
  | 'listItem'
  | 'image'
  | 'divider'

export interface Block {
  kind: BlockKind
  text?: string
  /** Уровень заголовка: 1 — часть, 2 — глава. */
  level?: number
  path?: string
  alt?: string
}

export interface Chapter {
  title: string | null
  blocks: Block[]
}

// --- Библиотека -------------------------------------------------------------

export interface Progress {
  chapter: number
  /** Доля прочитанного внутри главы, от нуля до единицы. */
  withinChapter: number
  /** Когда книгу открывали в последний раз. Ноль — ни разу. */
  openedAt: number
}

export interface LibraryBook {
  id: string
  /**
   * Файл внутри хранилища. Пустой путь — книга известна по синхронизации, но
   * файла на этом устройстве ещё нет. Авторизованная синхронизация может
   * восстановить его из защищённого хранилища сервера.
   */
  path: string
  title: string
  author: string | null
  format: string
  /** Отпечаток содержимого: по нему один файл на двух устройствах — одна книга. */
  sourceKey: string
  addedAt: number
  chapters: number
  progress: Progress
  shelf: string | null
  rev: number
  dirty: boolean
  deleted: boolean
}

export interface Shelf {
  name: string
  createdAt: number
}

export type CardKind = 'word' | 'phrase' | 'rule'

export interface Card {
  id: string
  bookId: string
  kind: CardKind
  surface: string
  lemma: string
  translation: string
  context: string
  pos: string
  cefr: string
  /** Прочность: падает при уверенном знании, растёт при ошибке. Ноль — выучено. */
  hp: number
  streak: number
  intervalDays: number
  dueAt: number
  reviewedAt: number
  addedAt: number
  rev: number
  dirty: boolean
  deleted: boolean
}

export interface LibraryState {
  books: LibraryBook[]
  cards: Card[]
  shelves: Shelf[]
  cursor: number
  revision: number
}

// --- Настройки --------------------------------------------------------------

export type ThemeName = 'Paper' | 'Sepia' | 'Dark' | 'Oled'
export type IntensityName = 'Gentle' | 'Normal' | 'Strong' | 'Extreme'

/**
 * Что притушить вокруг того места, где читатель сейчас.
 *
 * Именем, а не флагом: режимов будет больше, а `boolean` пришлось бы менять
 * на строку ровно тогда, когда настройки уже лежат на устройствах.
 */
export type FocusMode = 'off' | 'sentence' | 'paragraph'

export interface AppSettings {
  theme: string
  fontScale: number
  lineScale: number
  onboardingSeen: boolean
  /**
   * Открывал ли читатель разбор слова хоть раз.
   *
   * Касание слова — главное действие продукта, и узнать о нём неоткуда.
   * Читалка подсказывает это один раз и навсегда замолкает, как только
   * подсказкой воспользовались.
   */
  wordTapSeen: boolean
  lastSeenVersion: string
  reduceMotion: boolean
  /** Короткие звуки компаньона; отдельно от радио и озвучки слов. */
  companionSounds: boolean
  /** Набирать основу слова полужирным: якорь для беглого чтения. */
  emphasizeStems: boolean
  /** Прожектор: `off`, `sentence`, `paragraph`. */
  focusMode: FocusMode
  /** Темп ведущей строки, слов в минуту. Ноль — выключена. */
  pacerWpm: number
  /** Размер отрезка чтения в словах. Ноль — отрезки выключены. */
  segmentWords: number
  /** Разделы газеты, интересные читателю. Пустой список — весь номер. */
  newspaperTopics: string[]
  demoAdded: boolean
  intensity: string
  /** Местный день последней тренировки: серия считается по календарю читателя. */
  trainedOn: number
  streakDays: number
  bestStreak: number
  answers: number
  right: number
}

// --- Тренировка -------------------------------------------------------------

export interface DeckStatus {
  kind: CardKind
  due: number
  total: number
  learned: number
}

export interface FreshRule {
  rule: string
  title: string
}

export interface TrainingQueue {
  /** Ключи заданий: номера карточек, а у новых правил — имя правила. */
  keys: string[]
  rules: FreshRule[]
}

/**
 * Каким способом спрашивают. Выбирает ядро, а не интерфейс: способ зависит от
 * прочности карточки, и это правило одно на все три клиента.
 */
export type DrillKind = 'Choice' | 'Letters' | 'Typing' | 'Builder' | 'Gap'

export interface Drill {
  cardId: string
  kind: DrillKind
  /** Что показывают крупно: перевод слова, русская фраза, название правила. */
  question: string
  /** Строка помельче: предложение с пропуском, пример правила, подсказка. */
  subject: string
  answer: string
  /** Варианты, буквы или блоки — смотря что за способ. */
  pieces: string[]
  /** Буквы, открытые заранее, — номера позиций в `answer`. */
  given: number[]
  rule: string
  formula: string
  explanation: string
}

// --- Словарь ----------------------------------------------------------------

export interface Sense {
  pos: string
  /** Толкование по-английски: читатель остаётся в языке. */
  definition: string
}

export interface DictionaryEntry {
  word: string
  /** МФА без косых черт: «ˈlaɪbɹɛɹi». Пусто, если произношения нет. */
  pronunciation: string
  translations: string[]
  senses: Sense[]
}

// --- Команды сессии ---------------------------------------------------------

/**
 * Что клиент просит сделать.
 *
 * `now` в UTC-миллисекундах, `offsetMinutes` и свежие идентификаторы приходят
 * снаружи везде, где нужны: своих часов и своей случайности у ядра нет.
 *
 * Смещение **в минутах**, а не в часах. Индия живёт на +5:30, Непал на +5:45,
 * и деление на часы сдвигает им границу дня — вместе с ней уезжает серия.
 */
export type Command =
  | { op: 'define'; word: string; path: string }
  | { op: 'planAdd'; fingerprint: string }
  | { op: 'addBook'; book: LibraryBook }
  | { op: 'attachFile'; id: string; path: string; fingerprint: string }
  | { op: 'reviveBook'; id: string; path: string; fingerprint: string }
  | { op: 'describe'; id: string; title: string; author: string | null; chapters: number }
  | { op: 'rememberProgress'; id: string; chapter: number; withinChapter: number; now: number }
  | {
      op: 'saveWord'
      bookId: string
      surface: string
      lemma: string
      translation: string
      context: string
      pos: string
      cefr: string
      id: string
      now: number
    }
  | { op: 'savePhrase'; bookId: string; sentence: string; translation: string; id: string; now: number }
  | { op: 'ruleCard'; rule: string; title: string; id: string; now: number }
  | { op: 'removeWord'; bookId: string; lemma: string }
  | { op: 'removeBook'; id: string }
  | { op: 'moveToShelf'; id: string; shelf: string | null; now: number }
  | { op: 'addShelf'; name: string; now: number }
  | { op: 'removeShelf'; name: string }
  | { op: 'review'; cardId: string; right: boolean; now: number; offsetMinutes: number }
  | { op: 'seenWordTap' }
  | { op: 'due'; now: number }
  | { op: 'deckStatus'; kind: CardKind; now: number }
  | { op: 'trainingQueue'; kind: CardKind; now: number }
  | { op: 'drillFor'; cardId: string }
  | { op: 'ruleDrill'; rule: string; cardId: string }
  | { op: 'sameText'; assembled: string; expected: string }
  | { op: 'appendedPage'; before: string; page: string }
  | { op: 'reminderAt'; now: number; offsetMinutes: number }
  | { op: 'continueReading' }
  | { op: 'deck'; bookId: string }
  | { op: 'setTheme'; theme: string }
  | { op: 'setFontScale'; scale: number }
  | { op: 'setLineScale'; scale: number }
  | { op: 'setEmphasizeStems'; on: boolean }
  | { op: 'setFocusMode'; mode: FocusMode }
  | { op: 'setPacer'; wpm: number }
  | { op: 'setSegmentWords'; words: number }
  | { op: 'setNewspaperTopics'; topics: string[] }
  | { op: 'seenOnboarding' }
  | { op: 'seenVersion'; version: string }
  | { op: 'setReduceMotion'; on: boolean }
  | { op: 'setCompanionSounds'; on: boolean }
  | { op: 'setIntensity'; intensity: string }
  | { op: 'markDemoAdded' }
  | { op: 'replaceSettings'; settings: AppSettings }
  | { op: 'pending' }
  | {
      op: 'applyServer'
      cursor: number
      books: LibraryBook[]
      cards: Card[]
      sent: { revision: number; books: string[]; cards: string[] } | null
      now: number
    }
  | { op: 'migrate'; freshIds: string[] }

/**
 * Ответ на команду.
 *
 * Одна форма на все команды, и лишние поля просто не приходят: клиент и так
 * знает, какую команду послал.
 */
export interface Outcome {
  /** Изменилось ли хоть что-нибудь. По нему клиент решает, писать ли на диск. */
  changed: boolean
  libraryChanged: boolean
  settingsChanged: boolean
  practiceChanged?: boolean
  /** Что делать с добавляемой книгой: `known`, `attach`, `revive` или `fresh`. */
  plan?: 'known' | 'attach' | 'revive' | 'fresh'
  bookId?: string
  book?: LibraryBook
  card?: Card
  cards?: Card[]
  books?: LibraryBook[]
  shelf?: Shelf
  /** Момент напоминания или отсутствие поля, если напоминать не о чем. */
  at?: number
  streak?: number
  status?: DeckStatus
  queue?: TrainingQueue
  drill?: Drill
  right?: boolean
  text?: string
  definition?: DictionaryEntry
  /**
   * Удалось ли открыть словарь. Отдельно от статьи: неизвестное слово в
   * исправном словаре и отсутствующий файл — разные причины, и только во
   * втором случае нужен сетевой fallback.
   */
  dictionaryAvailable?: boolean
  /** Поколения §17 Persist performance — для generation-aware ack. */
  libraryGeneration?: number
  settingsGeneration?: number
  practiceGeneration?: number
  practice?: unknown
}

export interface DirtyFlags {
  library: boolean
  settings: boolean
  practice?: boolean
  libraryGeneration?: number
  settingsGeneration?: number
  practiceGeneration?: number
  librarySavedGeneration?: number
  settingsSavedGeneration?: number
  practiceSavedGeneration?: number
}
