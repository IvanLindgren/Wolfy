import { create } from 'zustand'

import type { AiRecap, CompanionOpinion, CompanionQuestion } from '../api/client'

const STORE_KEY = 'wolfy.companion.memory.v1'

export type MemorySize = 'compact' | 'balanced' | 'deep'

export interface CompanionMemorySettings {
  enabled: boolean
  shareWithAi: boolean
  size: MemorySize
}

interface CacheEntry {
  key: string
  kind: 'opinion' | 'question' | 'recap'
  bookId: string
  createdAt: number
  opinion?: CompanionOpinion
  question?: CompanionQuestion
  recap?: AiRecap
}

interface MemoryEvent { title: string; text: string; kind: string }
interface BookCheckpoint { chapter: number; summary: string; events: MemoryEvent[]; updatedAt: number }
interface BookMemory { bookId: string; title: string; checkpoints: BookCheckpoint[]; updatedAt: number }
interface MemoryQuestion { bookId: string; title: string; text: string; createdAt: number }

interface MemoryDocument {
  schemaVersion: 2
  settings: CompanionMemorySettings
  cache: CacheEntry[]
  books: BookMemory[]
  /*
   * Вопросы читателя — те, что он написал сам.
   *
   * Список назывался `requests`, и в него же попадало каждое нажатие «мнение о
   * странице» и «вспомнить сюжет». Оттуда он целиком уезжал в промпт строкой
   * «недавние запросы читателя», и модель получала подпись кнопки вместо
   * вопроса — шесть раз подряд одну и ту же. Хуже: двадцать мест на список
   * подписи занимали быстрее, чем настоящие вопросы, ради которых память и
   * заведена. Нажатие кнопки — не вопрос.
   */
  questions: MemoryQuestion[]
}

interface CompanionMemoryStore extends MemoryDocument {
  setEnabled: (enabled: boolean) => void
  setShareWithAi: (enabled: boolean) => void
  setSize: (size: MemorySize) => void
  clear: () => void
  findOpinion: (bookId: string, chapter: number, pageText: string, profileHash: string) => CompanionOpinion | null
  rememberOpinion: (bookId: string, chapter: number, pageText: string, profileHash: string, value: CompanionOpinion) => void
  findQuestion: (bookId: string, chapter: number, question: string, position: number, profileHash: string) => CompanionQuestion | null
  rememberQuestion: (bookId: string, title: string, chapter: number, question: string, position: number, profileHash: string, value: CompanionQuestion) => void
  findRecap: (bookId: string, chapter: number, position: number) => AiRecap | null
  rememberRecap: (bookId: string, title: string, chapter: number, position: number, value: AiRecap) => void
  contextFor: (bookId: string) => string
}

const defaults = (): MemoryDocument => ({
  schemaVersion: 2,
  settings: { enabled: true, shareWithAi: true, size: 'balanced' },
  cache: [],
  books: [],
  questions: [],
})

const limits = (size: MemorySize) => size === 'compact'
  ? { cache: 30, books: 4, checkpoints: 4, questions: 8 }
  : size === 'deep'
    ? { cache: 250, books: 30, checkpoints: 24, questions: 40 }
    : { cache: 100, books: 12, checkpoints: 10, questions: 20 }

function trim(document: MemoryDocument): MemoryDocument {
  const size: MemorySize = ['compact', 'balanced', 'deep'].includes(document.settings.size)
    ? document.settings.size
    : 'balanced'
  const cap = limits(size)
  return {
    schemaVersion: 2,
    settings: { ...document.settings, size },
    cache: [...document.cache].sort((a, b) => b.createdAt - a.createdAt).slice(0, cap.cache),
    books: [...document.books].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, cap.books).map((book) => ({
      ...book,
      checkpoints: [...book.checkpoints].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, cap.checkpoints),
    })),
    questions: [...document.questions].sort((a, b) => b.createdAt - a.createdAt).slice(0, cap.questions),
  }
}

function load(): MemoryDocument {
  if (typeof localStorage === 'undefined') return defaults()
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return defaults()
    const parsed = JSON.parse(raw) as Partial<MemoryDocument>
    return trim({
      ...defaults(),
      ...parsed,
      settings: { ...defaults().settings, ...parsed.settings },
      // Кэш первой схемы собирался по другим ключам: ни один новый вопрос с
      // ним не совпадёт, и до вытеснения по времени он занимал бы место зря.
      cache: parsed.schemaVersion === 2 && Array.isArray(parsed.cache) ? parsed.cache : [],
      books: Array.isArray(parsed.books) ? parsed.books : [],
      // Первая схема писала в один список и вопросы, и нажатия кнопок.
      // Различить их задним числом можно только по тексту подписи, а подпись —
      // это интерфейс: разбирать данные по ней значит завести зависимость,
      // которая сломается от правки текста кнопки. Список читается заново.
      questions: parsed.schemaVersion === 2 && Array.isArray(parsed.questions) ? parsed.questions : [],
      schemaVersion: 2,
    })
  } catch {
    return defaults()
  }
}

function save(document: MemoryDocument): MemoryDocument {
  const fixed = trim(document)
  try {
    if (typeof localStorage !== 'undefined') localStorage.setItem(STORE_KEY, JSON.stringify(fixed))
  } catch {
    // Переполненное хранилище не должно скрыть уже полученный ответ.
  }
  return fixed
}

/**
 * Отпечаток входа: сам текст не сохраняется, сравнивается только хэш.
 *
 * Дорожек две, с разными начальными значениями, — это те же шестьдесят четыре
 * бита, что и в Kotlin, просто собранные из двух по тридцать два, потому что
 * `Math.imul` не умеет шире. Ширина нужна из-за цены совпадения: на чужой
 * ключ компаньон ответит уверенно и не про ту страницу, а сверить не с чем,
 * исходного текста рядом нет.
 *
 * Длина куска подмешивается между кусками как разделитель. Разделитель тут
 * был и раньше, и слить соседние поля в одно он не давал; просто прежний шаг
 * `hash ^= 0` не добавлял ни бита, а длина добавляет, и стоит столько же.
 */
function lane(seed: number, parts: string[]): string {
  let hash = seed
  for (const part of parts) {
    for (let index = 0; index < part.length; index += 1) {
      hash ^= part.charCodeAt(index)
      hash = Math.imul(hash, 0x01000193)
    }
    hash ^= part.length
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function fingerprint(kind: string, ...parts: string[]): string {
  return `${kind}:${lane(0x811c9dc5, parts)}${lane(0x7f4a7c15, parts)}`
}

/**
 * Место в главе, огрублённое до двадцатой доли — примерно полэкрана.
 *
 * Сырую позицию в ключ класть нельзя: она меняется от каждого движения пальца.
 * Огрублённая отвечает на тот вопрос, который и решает, годится ли прошлый
 * ответ: случилось ли с тех пор что-нибудь новое.
 */
function place(position: number): string {
  const bounded = Math.min(Math.max(Math.round(position), 0), PLACE_RANGE)
  return String(Math.floor(bounded / PLACE_STEP))
}

/** Позиция приезжает как доля главы, умноженная на десять тысяч. */
const PLACE_RANGE = 10_000
const PLACE_STEP = 500

/**
 * Текст в том виде, в каком он опознаётся как «тот же самый».
 *
 * «Кто он?» и «кто он» — один вопрос, и платить за второй незачем. Страница же
 * собирается заново на каждый показ, и разойтись двум сборкам достаточно
 * одного лишнего перевода строки.
 */
function plain(text: string): string {
  let result = ''
  let space = false
  for (const char of text) {
    if (/[\p{L}\p{N}]/u.test(char)) {
      if (space && result) result += ' '
      space = false
      result += char.toLowerCase()
    } else {
      space = true
    }
  }
  return result
}

export const useCompanionMemory = create<CompanionMemoryStore>((set, get) => ({
  ...load(),

  setEnabled: (enabled) => set((state) => save({ ...state, settings: { ...state.settings, enabled } })),
  setShareWithAi: (shareWithAi) => set((state) => save({ ...state, settings: { ...state.settings, shareWithAi } })),
  setSize: (size) => set((state) => save({ ...state, settings: { ...state.settings, size } })),
  clear: () => set((state) => save({ ...defaults(), settings: state.settings })),
  /*
   * Ключ собирается из того, что читатель считает своим запросом, а не из
   * всего, что уехало на сервер.
   *
   * В ключ вопроса входило всё прочитанное, а оно прирастает каждой строкой:
   * один и тот же вопрос за вечер был для памяти двумя разными. Прочитанное
   * заменено местом в главе, огрублённым до двадцатой доли — примерно
   * полэкрана. Это и есть порог «случилось ли с тех пор что-нибудь новое».
   *
   * Мнение о странице живёт по другому правилу: там текст страницы и есть
   * вопрос, и подменять его местом нельзя.
   */
  findOpinion: (bookId, chapter, pageText, profileHash) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('opinion', bookId, String(chapter), plain(pageText), profileHash))?.opinion
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberOpinion: (bookId, chapter, pageText, profileHash, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('opinion', bookId, String(chapter), plain(pageText), profileHash),
      kind: 'opinion', bookId, createdAt: now, opinion: { ...value, cached: false },
    }
    set((state) => save({
      ...state,
      cache: [entry, ...state.cache.filter((item) => item.key !== entry.key)],
    }))
  },

  findQuestion: (bookId, chapter, question, position, profileHash) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('question', bookId, String(chapter), plain(question), place(position), profileHash))?.question
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberQuestion: (bookId, title, chapter, question, position, profileHash, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('question', bookId, String(chapter), plain(question), place(position), profileHash),
      kind: 'question', bookId, createdAt: now, question: { ...value, cached: false },
    }
    set((state) => save({
      ...state,
      cache: [entry, ...state.cache.filter((item) => item.key !== entry.key)],
      questions: [{ bookId, title: title.slice(0, 300), text: question.slice(0, 500), createdAt: now }, ...state.questions],
    }))
  },

  /*
   * Пересказ ищется по месту, а не по фрагменту. Фрагмент — скользящее окно
   * последних экранов: оно меняется от каждой прочитанной строки, и «вспомнить
   * сюжет» дважды за вечер стоило двух самых дорогих запросов приложения.
   */
  findRecap: (bookId, chapter, position) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('recap', bookId, String(chapter), place(position)))?.recap
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberRecap: (bookId, title, chapter, position, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('recap', bookId, String(chapter), place(position)), kind: 'recap', bookId, createdAt: now,
      recap: { ...value, cached: false },
    }
    const state = get()
    const previous = state.books.find((book) => book.bookId === bookId)
    const checkpoint: BookCheckpoint = {
      chapter, summary: value.summary.slice(0, 1200),
      events: value.events.slice(0, 6).map((event) => ({ title: event.title.slice(0, 300), text: event.text.slice(0, 300), kind: event.kind })),
      updatedAt: now,
    }
    const book: BookMemory = {
      bookId, title: title.slice(0, 300), updatedAt: now,
      checkpoints: [checkpoint, ...(previous?.checkpoints ?? []).filter((item) => item.chapter !== chapter)],
    }
    set(save({
      ...state,
      cache: [entry, ...state.cache.filter((item) => item.key !== entry.key)],
      books: [book, ...state.books.filter((item) => item.bookId !== bookId)],
    }))
  },

  contextFor: (bookId) => {
    const state = get()
    if (!state.settings.enabled || !state.settings.shareWithAi) return ''
    const book = state.books.find((item) => item.bookId === bookId)
    const here = state.questions.filter((item) => item.bookId === bookId).slice(0, 6)
    const elsewhere = state.questions.filter((item) => item.bookId !== bookId).slice(0, 3)
    if (!book && here.length === 0 && elsewhere.length === 0) return ''
    const lines: string[] = []
    if (book) {
      lines.push('Ранее сохранённые краткие пересказы книги (могут содержать ошибки):')
      for (const point of book.checkpoints.slice(0, 3).reverse()) lines.push(`Глава ${point.chapter + 1}: ${point.summary}`)
    }
    if (here.length > 0) lines.push(`Читатель уже спрашивал об этой книге: ${here.reverse().map((item) => item.text).join('; ')}`)
    if (elsewhere.length > 0) lines.push(`О чём этот читатель спрашивает обычно: ${elsewhere.reverse().map((item) => item.text).join('; ')}`)
    return lines.join('\n').slice(0, 3500)
  },
}))
