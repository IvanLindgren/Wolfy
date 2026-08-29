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
interface MemoryRequest { bookId: string; title: string; text: string; createdAt: number }

interface MemoryDocument {
  schemaVersion: 1
  settings: CompanionMemorySettings
  cache: CacheEntry[]
  books: BookMemory[]
  requests: MemoryRequest[]
}

interface CompanionMemoryStore extends MemoryDocument {
  setEnabled: (enabled: boolean) => void
  setShareWithAi: (enabled: boolean) => void
  setSize: (size: MemorySize) => void
  clear: () => void
  findOpinion: (bookId: string, chapter: number, pageText: string, profileHash: string) => CompanionOpinion | null
  rememberOpinion: (bookId: string, title: string, chapter: number, pageText: string, profileHash: string, value: CompanionOpinion) => void
  findQuestion: (bookId: string, chapter: number, question: string, context: string, profileHash: string) => CompanionQuestion | null
  rememberQuestion: (bookId: string, title: string, chapter: number, question: string, context: string, profileHash: string, value: CompanionQuestion) => void
  findRecap: (bookId: string, excerpt: string) => AiRecap | null
  rememberRecap: (bookId: string, title: string, chapter: number, excerpt: string, value: AiRecap) => void
  contextFor: (bookId: string) => string
}

const defaults = (): MemoryDocument => ({
  schemaVersion: 1,
  settings: { enabled: true, shareWithAi: true, size: 'balanced' },
  cache: [],
  books: [],
  requests: [],
})

const limits = (size: MemorySize) => size === 'compact'
  ? { cache: 30, books: 4, checkpoints: 4, requests: 8 }
  : size === 'deep'
    ? { cache: 250, books: 30, checkpoints: 24, requests: 40 }
    : { cache: 100, books: 12, checkpoints: 10, requests: 20 }

function trim(document: MemoryDocument): MemoryDocument {
  const size: MemorySize = ['compact', 'balanced', 'deep'].includes(document.settings.size)
    ? document.settings.size
    : 'balanced'
  const cap = limits(size)
  return {
    schemaVersion: 1,
    settings: { ...document.settings, size },
    cache: [...document.cache].sort((a, b) => b.createdAt - a.createdAt).slice(0, cap.cache),
    books: [...document.books].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, cap.books).map((book) => ({
      ...book,
      checkpoints: [...book.checkpoints].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, cap.checkpoints),
    })),
    requests: [...document.requests].sort((a, b) => b.createdAt - a.createdAt).slice(0, cap.requests),
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
      cache: Array.isArray(parsed.cache) ? parsed.cache : [],
      books: Array.isArray(parsed.books) ? parsed.books : [],
      requests: Array.isArray(parsed.requests) ? parsed.requests : [],
      schemaVersion: 1,
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

export const useCompanionMemory = create<CompanionMemoryStore>((set, get) => ({
  ...load(),

  setEnabled: (enabled) => set((state) => save({ ...state, settings: { ...state.settings, enabled } })),
  setShareWithAi: (shareWithAi) => set((state) => save({ ...state, settings: { ...state.settings, shareWithAi } })),
  setSize: (size) => set((state) => save({ ...state, settings: { ...state.settings, size } })),
  clear: () => set((state) => save({ ...defaults(), settings: state.settings })),
  /*
   * Место прокрутки в ключ не входит: оно приезжает как доля главы на десять
   * тысяч, меняется от каждого движения пальца и на ответ не влияет — сервер
   * поле position принимает и нигде не читает. В ключе оно выключало бы кэш
   * ровно там, ради чего кэш заведён.
   */
  findOpinion: (bookId, chapter, pageText, profileHash) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('opinion', bookId, String(chapter), pageText, profileHash))?.opinion
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberOpinion: (bookId, title, chapter, pageText, profileHash, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('opinion', bookId, String(chapter), pageText, profileHash),
      kind: 'opinion', bookId, createdAt: now, opinion: { ...value, cached: false },
    }
    set((state) => save({
      ...state,
      cache: [entry, ...state.cache.filter((item) => item.key !== entry.key)],
      requests: [{ bookId, title: title.slice(0, 300), text: 'Мнение о странице', createdAt: now }, ...state.requests],
    }))
  },

  findQuestion: (bookId, chapter, question, context, profileHash) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('question', bookId, String(chapter), question, context, profileHash))?.question
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberQuestion: (bookId, title, chapter, question, context, profileHash, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('question', bookId, String(chapter), question, context, profileHash),
      kind: 'question', bookId, createdAt: now, question: { ...value, cached: false },
    }
    set((state) => save({
      ...state,
      cache: [entry, ...state.cache.filter((item) => item.key !== entry.key)],
      requests: [{ bookId, title: title.slice(0, 300), text: question.slice(0, 500), createdAt: now }, ...state.requests],
    }))
  },

  findRecap: (bookId, excerpt) => {
    if (!get().settings.enabled) return null
    const found = get().cache.find((entry) => entry.key === fingerprint('recap', bookId, excerpt))?.recap
    return found ? { ...found, remaining: -1, cached: true } : null
  },
  rememberRecap: (bookId, title, chapter, excerpt, value) => {
    if (!get().settings.enabled) return
    const now = Date.now()
    const entry: CacheEntry = {
      key: fingerprint('recap', bookId, excerpt), kind: 'recap', bookId, createdAt: now,
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
      requests: [{ bookId, title: title.slice(0, 300), text: 'Вспомнить сюжет', createdAt: now }, ...state.requests],
    }))
  },

  contextFor: (bookId) => {
    const state = get()
    if (!state.settings.enabled || !state.settings.shareWithAi) return ''
    const book = state.books.find((item) => item.bookId === bookId)
    const requests = state.requests.filter((item) => item.bookId === bookId).slice(0, 6)
    const generalRequests = state.requests.filter((item) => item.bookId !== bookId).slice(0, 3)
    if (!book && requests.length === 0 && generalRequests.length === 0) return ''
    const lines: string[] = []
    if (book) {
      lines.push('Ранее сохранённые краткие пересказы книги (могут содержать ошибки):')
      for (const point of book.checkpoints.slice(0, 3).reverse()) lines.push(`Глава ${point.chapter + 1}: ${point.summary}`)
    }
    if (requests.length > 0) lines.push(`Недавние запросы читателя: ${requests.reverse().map((item) => item.text).join('; ')}`)
    if (generalRequests.length > 0) lines.push(`Обычный стиль запросов читателя: ${generalRequests.reverse().map((item) => item.text).join('; ')}`)
    return lines.join('\n').slice(0, 3500)
  },
}))
