import type { AppSettings, Card, DeckStatus, LibraryBook } from '../core/types'

/**
 * Что Wolfy знает о читателе и о себе.
 *
 * ## Зачем это отдельным модулем
 *
 * Настройки — единственный экран, где приложение отчитывается: сколько
 * прочитано, что даёт аккаунт, какая версия и что установлено. Раньше веб
 * отчитывался одной строкой «Веб 0.1.0 · ядро … · словарь …», и всё остальное
 * читателю приходилось складывать самому: книги считать глазами в библиотеке,
 * слова — на другой вкладке, серию — на третьей.
 *
 * ## Что здесь считается, а что спрашивается
 *
 * Книги считаются здесь: правило «дочитано» — это определение, а не
 * отображение, порог задаёт ядро (доля ≥ 0.999), и повторить его на глаз в
 * JSX значит однажды разойтись с ним молча.
 *
 * А колоды **не считаются**: их состояние приходит из ядра готовым. Колода
 * грамматики наполняется сама — новые правила подмешиваются порцией в день, —
 * и в `library.cards` их до первого ответа нет. Пересчёт по карточкам дал бы
 * на одном экране «0 к повторению» рядом со значком раздела, где горит пять.
 */
export interface ReadingFacts {
  books: number
  /** Книга, которую открывали хоть раз. */
  started: number
  /** Ядро считает книгу дочитанной по доле, а не по последней главе. */
  finished: number
  /** Книги, известные по синхронизации, но без файла на этом устройстве. */
  withoutFile: number
  /** Всего карточек во всех колодах — числом ядра, а не пересчётом. */
  cards: number
  learned: number
  /** Сколько созрело прямо сейчас: то же число, что горит на значке раздела. */
  due: number
  /** Слова и фразы, добавленные за неделю. Правила сюда не входят: их
   *  добавляет не читатель, и «новых слов за неделю» они бы завысили. */
  addedThisWeek: number
  streakDays: number
  bestStreak: number
  answers: number
  /** Доля верных в процентах; `null`, когда отвечать ещё не приходилось. */
  accuracy: number | null
}

const WEEK_MS = 7 * 24 * 60 * 60 * 1000

/** Доля книги от нуля до единицы — тем же счётом, что и в ядре. */
export function readFraction(book: LibraryBook): number {
  if (book.chapters <= 0) return 0
  const value = (book.progress.chapter + book.progress.withinChapter) / book.chapters
  return Math.min(1, Math.max(0, value))
}

export function readingFacts(
  books: LibraryBook[],
  cards: Card[],
  decks: DeckStatus[],
  settings: AppSettings,
  now: number,
): ReadingFacts {
  const live = books.filter((book) => !book.deleted)
  const answers = settings.answers
  const sum = (pick: (status: DeckStatus) => number) =>
    decks.reduce((total, status) => total + pick(status), 0)
  return {
    books: live.length,
    started: live.filter((book) => book.progress.openedAt > 0).length,
    // Тот же порог, что в ядре: 0.999, а не 1.0. Последняя строка последней
    // главы редко попадает в единицу ровно, и книга, дочитанная до точки,
    // не должна висеть непрочитанной из-за ошибки округления.
    finished: live.filter((book) => readFraction(book) >= 0.999).length,
    withoutFile: live.filter((book) => !book.path).length,
    cards: sum((status) => status.total),
    learned: sum((status) => status.learned),
    due: sum((status) => status.due),
    addedThisWeek: cards.filter(
      (card) => !card.deleted && card.kind !== 'rule' && card.addedAt > now - WEEK_MS,
    ).length,
    streakDays: settings.streakDays,
    bestStreak: settings.bestStreak,
    answers,
    accuracy: answers > 0 ? Math.round((settings.right / answers) * 100) : null,
  }
}

/**
 * Число со словом в нужном падеже.
 *
 * «5 книг» и «2 книги» — разные слова, и склеивать их через «книг(и)» значит
 * расписаться в том, что интерфейс писали не для чтения.
 */
export function plural(count: number, one: string, few: string, many: string): string {
  const tens = Math.abs(count) % 100
  const units = count % 10
  if (tens > 10 && tens < 20) return `${count} ${many}`
  if (units === 1) return `${count} ${one}`
  if (units >= 2 && units <= 4) return `${count} ${few}`
  return `${count} ${many}`
}
