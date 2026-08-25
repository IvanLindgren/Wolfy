/**
 * Состояние ядра на стороне интерфейса.
 *
 * Три уровня состояния нельзя смешивать, и это первый из них:
 *
 * - **состояние ядра** — библиотека, колоды, настройки. Владелец один: сессия
 *   в воркере. Здесь лежит только её отражение, обновляемое после команды;
 * - состояние сервера — профиль, лента, переводы — живёт в TanStack Query;
 * - состояние экрана — что открыто и куда прокручено — в компонентах.
 *
 * Интерфейс **никогда не правит библиотеку напрямую**. Он посылает команду и
 * перечитывает то, что ядро назвало изменившимся. Соблазн «поправить локально
 * для скорости» здесь особенно велик и особенно вреден: расписание повторений
 * считает ядро, и две правды о карточке — это две разные даты следующего
 * показа.
 */

import { create } from 'zustand'

import * as bridge from './bridge'
import { newId, now, offsetMinutes } from './clock'
import type {
  AppSettings,
  Card,
  CardKind,
  Command,
  DeckStatus,
  Drill,
  FocusMode,
  LibraryBook,
  LibraryState,
  Outcome,
  ThemeName,
  TrainingQueue,
} from './types'

const EMPTY_LIBRARY: LibraryState = {
  books: [],
  cards: [],
  shelves: [],
  cursor: 0,
  revision: 0,
}

const DEFAULT_SETTINGS: AppSettings = {
  theme: 'Paper',
  fontScale: 1,
  lineScale: 1,
  onboardingSeen: false,
  lastSeenVersion: '',
  reduceMotion: false,
  emphasizeStems: false,
  focusMode: 'off',
  pacerWpm: 0,
  segmentWords: 0,
  newspaperTopics: [],
  demoAdded: false,
  intensity: 'Normal',
  trainedOn: 0,
  streakDays: 0,
  bestStreak: 0,
  answers: 0,
  right: 0,
}

export interface SessionState {
  /** Поднялось ли ядро. До этого экраны рисуют скелет, а не спиннер. */
  ready: boolean
  /** Ошибка старта: повреждённое состояние, которое не восстановлено из бэкапа. */
  bootError: string | null
  /** Версия ядра — показывается в диагностике настроек. */
  version: string
  /** Готов ли лексикон: без него разбор слова отвечает «не знаю». */
  lexicon: boolean
  /** Стоит ли офлайн-словарь. */
  dictionary: boolean
  library: LibraryState
  settings: AppSettings

  boot: () => Promise<void>
  /** Выполняет команду и подтягивает то, что изменилось. */
  run: (command: Command) => Promise<Outcome>
  refresh: () => Promise<void>
  setDictionaryReady: (ready: boolean) => void
}

export const useSession = create<SessionState>((set, get) => ({
  ready: false,
  bootError: null,
  version: '',
  lexicon: false,
  dictionary: false,
  library: EMPTY_LIBRARY,
  settings: DEFAULT_SETTINGS,

  async boot() {
    if (get().ready) return
    try {
      const boot = await bridge.boot()
      set({
        ready: true,
        bootError: null,
        version: boot.version,
        lexicon: boot.lexicon,
        dictionary: boot.dictionary,
        library: boot.library,
        settings: boot.settings,
      })

    // Лексикон догоняет уже открытое приложение: книга читается и без него,
    // а первый тап по слову случится не в первую секунду.
    void bridge.loadLexicon().then((ok) => set({ lexicon: ok }))
    // Словарь, скачанный в прошлый раз, поднимается сам: спрашивать согласие
    // второй раз незачем.
    if (boot.dictionary) {
      void bridge.restoreDictionary().then((ok) => set({ dictionary: ok }))
    }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      set({ bootError: message })
      // Явно не ставим ready: приложение покажет ошибку восстановления,
      // а не пустую библиотеку. Клиент не должен после ошибки автоматически
      // сохранять пустое состояние поверх повреждённого (P12).
      throw e
    }
  },

  async run(command) {
    const outcome = await bridge.command(command)
    if (outcome.libraryChanged) {
      set({ library: await bridge.library() })
    }
    if (outcome.settingsChanged) {
      set({ settings: await bridge.settings() })
    }
    return outcome
  },

  async refresh() {
    const [library, settings] = await Promise.all([bridge.library(), bridge.settings()])
    set({ library, settings })
  },

  setDictionaryReady(ready) {
    set({ dictionary: ready })
  },
}))

// --- Готовые команды --------------------------------------------------------
//
// Обёртки, а не «сервис»: каждая — это одна команда с проставленными `now`,
// `offsetMinutes` и свежим номером. Смысл в том, чтобы ни один экран не забыл
// передать время, — забудет один, и серия дней разъедется у всех.

function run(command: Command): Promise<Outcome> {
  return useSession.getState().run(command)
}

export const session = {
  /** Книги, которые видит читатель: удалённые остаются только синхронизации. */
  books(): LibraryBook[] {
    return useSession.getState().library.books.filter((book) => !book.deleted)
  },

  book(id: string): LibraryBook | undefined {
    return useSession.getState().library.books.find((book) => book.id === id)
  },

  /** Колода книги. */
  deck(bookId: string): Card[] {
    return useSession
      .getState()
      .library.cards.filter((card) => card.bookId === bookId && !card.deleted)
  },

  /** Заводить ли книгу заново — решает ядро по отпечатку содержимого. */
  async planAdd(fingerprint: string): Promise<Outcome> {
    return run({ op: 'planAdd', fingerprint })
  },

  async addBook(book: Omit<LibraryBook, 'rev' | 'dirty' | 'deleted'>): Promise<Outcome> {
    return run({
      op: 'addBook',
      book: { ...book, rev: 0, dirty: true, deleted: false },
    })
  },

  async attachFile(id: string, path: string, fingerprint: string): Promise<Outcome> {
    return run({ op: 'attachFile', id, path, fingerprint })
  },

  /** Явное повторное добавление удалённой книги: снимает tombstone на том же номере. */
  async reviveBook(id: string, path: string, fingerprint: string): Promise<Outcome> {
    return run({ op: 'reviveBook', id, path, fingerprint })
  },

  /** Запоминает, что ядро нашло в книге при открытии. */
  async describe(
    id: string,
    title: string,
    author: string | null,
    chapters: number,
  ): Promise<Outcome> {
    return run({ op: 'describe', id, title, author, chapters })
  },

  /** Запоминает место с точностью до слова: глава и доля внутри неё. */
  async rememberProgress(
    id: string,
    chapter: number,
    withinChapter: number,
  ): Promise<Outcome> {
    return run({ op: 'rememberProgress', id, chapter, withinChapter, now: now() })
  },

  async saveWord(word: {
    bookId: string
    surface: string
    lemma: string
    translation?: string
    context?: string
    pos?: string
    cefr?: string
  }): Promise<Outcome> {
    return run({
      op: 'saveWord',
      bookId: word.bookId,
      surface: word.surface,
      lemma: word.lemma,
      translation: word.translation ?? '',
      context: word.context ?? '',
      pos: word.pos ?? '',
      cefr: word.cefr ?? '',
      id: newId(),
      now: now(),
    })
  },

  async savePhrase(
    bookId: string,
    sentence: string,
    translation: string,
  ): Promise<Outcome> {
    return run({
      op: 'savePhrase',
      bookId,
      sentence,
      translation,
      id: newId(),
      now: now(),
    })
  },

  async ruleCard(rule: string, title: string): Promise<Outcome> {
    return run({ op: 'ruleCard', rule, title, id: newId(), now: now() })
  },

  async removeWord(bookId: string, lemma: string): Promise<Outcome> {
    return run({ op: 'removeWord', bookId, lemma })
  },

  async removeBook(id: string): Promise<Outcome> {
    return run({ op: 'removeBook', id })
  },

  async moveToShelf(id: string, shelf: string | null): Promise<Outcome> {
    return run({ op: 'moveToShelf', id, shelf, now: now() })
  },

  async addShelf(name: string): Promise<Outcome> {
    return run({ op: 'addShelf', name, now: now() })
  },

  async removeShelf(name: string): Promise<Outcome> {
    return run({ op: 'removeShelf', name })
  },

  /**
   * Учитывает ответ тренировки.
   *
   * Одной командой, а не двумя: расписание карточки и серия дней — это одно
   * событие. Двумя оно разъезжалось бы — ответ засчитан в серию, а карточка
   * не пересчитана.
   */
  async review(cardId: string, right: boolean): Promise<Outcome> {
    return run({
      op: 'review',
      cardId,
      right,
      now: now(),
      offsetMinutes: offsetMinutes(),
    })
  },

  async deckStatus(kind: CardKind): Promise<DeckStatus | undefined> {
    return (await run({ op: 'deckStatus', kind, now: now() })).status
  },

  async trainingQueue(kind: CardKind): Promise<TrainingQueue | undefined> {
    return (await run({ op: 'trainingQueue', kind, now: now() })).queue
  },

  async drillFor(cardId: string): Promise<Drill | undefined> {
    return (await run({ op: 'drillFor', cardId })).drill
  },

  async ruleDrill(rule: string, cardId: string): Promise<Drill | undefined> {
    return (await run({ op: 'ruleDrill', rule, cardId })).drill
  },

  /** Сходятся ли собранный ответ и ожидаемый — решает ядро, а не экран. */
  async sameText(assembled: string, expected: string): Promise<boolean> {
    return (await run({ op: 'sameText', assembled, expected })).right ?? false
  },

  async appendedPage(before: string, page: string): Promise<string> {
    return (await run({ op: 'appendedPage', before, page })).text ?? ''
  },

  async reminderAt(): Promise<number | null> {
    const outcome = await run({
      op: 'reminderAt',
      now: now(),
      offsetMinutes: offsetMinutes(),
    })
    return outcome.at ?? null
  },

  async continueReading(): Promise<LibraryBook | undefined> {
    return (await run({ op: 'continueReading' })).book
  },

  async due(): Promise<Card[]> {
    return (await run({ op: 'due', now: now() })).cards ?? []
  },

  async setTheme(theme: ThemeName): Promise<void> {
    await run({ op: 'setTheme', theme })
  },

  async setFontScale(scale: number): Promise<void> {
    await run({ op: 'setFontScale', scale })
  },

  async setLineScale(scale: number): Promise<void> {
    await run({ op: 'setLineScale', scale })
  },

  async setReduceMotion(on: boolean): Promise<void> {
    await run({ op: 'setReduceMotion', on })
  },

  async setEmphasizeStems(on: boolean): Promise<void> {
    await run({ op: 'setEmphasizeStems', on })
  },

  async setFocusMode(mode: FocusMode): Promise<void> {
    await run({ op: 'setFocusMode', mode })
  },

  /** Ноль выключает ведущую строку; остальное ядро прижимает к пределам. */
  async setPacer(wpm: number): Promise<void> {
    await run({ op: 'setPacer', wpm })
  },

  /** Ноль выключает отрезки чтения. */
  async setSegmentWords(words: number): Promise<void> {
    await run({ op: 'setSegmentWords', words })
  },

  async setNewspaperTopics(topics: string[]): Promise<void> {
    await run({ op: 'setNewspaperTopics', topics })
  },

  async setIntensity(intensity: string): Promise<void> {
    await run({ op: 'setIntensity', intensity })
  },

  async seenOnboarding(): Promise<void> {
    await run({ op: 'seenOnboarding' })
  },

  async markDemoAdded(): Promise<void> {
    await run({ op: 'markDemoAdded' })
  },

  async replaceSettings(settings: AppSettings): Promise<void> {
    await run({ op: 'replaceSettings', settings })
  },

  /** Что изменено на этом устройстве и ещё не отправлено. */
  async pending(): Promise<{ books: LibraryBook[]; cards: Card[] }> {
    const outcome = await run({ op: 'pending' })
    return { books: outcome.books ?? [], cards: outcome.cards ?? [] }
  },

  /** Принимает ответ сервера. Слияние живёт в ядре, а не здесь. */
  async applyServer(
    cursor: number,
    books: LibraryBook[],
    cards: Card[],
    sent: { revision: number; books: string[]; cards: string[] } | null,
  ): Promise<Outcome> {
    return run({ op: 'applyServer', cursor, books, cards, sent, now: now() })
  },
}
