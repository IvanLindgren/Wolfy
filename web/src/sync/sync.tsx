import { useEffect } from 'react'
import { create } from 'zustand'

import * as api from '../api/client'
import { session, useSession } from '../core/session'
import type { AppSettings, Card, LibraryBook } from '../core/types'
import { useAccount } from '../account/useAccount'
import { readBook } from '../storage/opfs'

interface SyncState {
  running: boolean
  lastSuccess: number
  pending: number
  error: string | null
}

export const useSyncState = create<SyncState>(() => ({ running: false, lastSuccess: 0, pending: 0, error: null }))
let active: Promise<boolean> | null = null

export function syncNow(): Promise<boolean> {
  if (active) return active
  active = exchange().finally(() => { active = null })
  return active
}

async function exchange(): Promise<boolean> {
  const state = useSession.getState()
  const [{ useCompanion }, { toSyncCompanion, fromSyncCompanion }] = await Promise.all([
    import('../companion/store'),
    import('../companion/model'),
  ])
  const sentCompanion = useCompanion.getState().outgoing()
  const pending = await session.pending()
  const sent = {
    revision: state.library.revision,
    books: pending.books.map((book) => book.id),
    cards: pending.cards.map((card) => card.id),
  }
  useSyncState.setState({ running: true, pending: sent.books.length + sent.cards.length + (sentCompanion ? 1 : 0), error: null })
  try {
    const response = await api.sync({
      cursor: state.library.cursor,
      books: pending.books.map(bookToWire),
      cards: pending.cards.map(cardToWire),
      reading: state.settings,
      // Компаньон едет отдельной коллекцией. Модуль грузится лениво: его
      // данные (набор реплик) не должны попадать в стартовый кусок оболочки.
      companion: sentCompanion ? toSyncCompanion(sentCompanion) : null,
    })
    const current = useSession.getState().library
    const knownBooks = new Map(current.books.map((book) => [book.id, book]))
    const knownCards = new Map(current.cards.map((card) => [card.id, card]))
    await session.applyServer(
      response.cursor,
      response.books.map((book) => bookFromWire(book, knownBooks.get(book.id))),
      response.cards.map((card) => cardFromWire(card, knownCards.get(card.id))),
      sent,
    )
    // Профиль компаньона: серверная ревизия выигрывает, tombstone гаснет.
    useCompanion.getState().applyServer(
      response.companion ? fromSyncCompanion(response.companion) : null,
      sentCompanion,
    )
    await syncBookFiles(response.files ?? [])
    if (!hasLocalReadingChoices(state.settings) && isSettings(response.reading)) {
      await session.replaceSettings(response.reading)
    }
    const left = await session.pending()
    const companionLeft = useCompanion.getState().outgoing() ? 1 : 0
    useSyncState.setState({ running: false, lastSuccess: Date.now(), pending: left.books.length + left.cards.length + companionLeft, error: null })
    return true
  } catch (error) {
    useSyncState.setState({ running: false, error: error instanceof Error ? error.message : 'Синхронизация не состоялась.' })
    return false
  }
}

const FILE_CHUNK_BYTES = 1024 * 1024
const MAX_SYNC_FILE_BYTES = 256 * 1024 * 1024

async function syncBookFiles(remoteFiles: api.SyncBookFile[]): Promise<void> {
  const remote = new Map(remoteFiles.map((file) => [file.bookId, file]))
  const books = session.books().filter((book) => !book.deleted)

  // Сначала публикуем файлы этого браузера. Вычисляется SHA самого хранимого
  // представления: у PDF веб хранит извлечённый текст, который тоже можно
  // безопасно открыть на телефоне и компьютере.
  for (const book of books) {
    if (!book.path || remote.has(book.id)) continue
    const bytes = await readBook(book.path)
    if (!bytes?.byteLength || bytes.byteLength > MAX_SYNC_FILE_BYTES) continue
    const sha256 = await digest(bytes)
    const fileName = syncedFileName(book)
    for (let offset = 0; offset < bytes.byteLength; offset += FILE_CHUNK_BYTES) {
      await api.uploadBookChunk(
        book.id,
        fileName,
        sha256,
        offset,
        bytes.byteLength,
        bytes.subarray(offset, Math.min(offset + FILE_CHUNK_BYTES, bytes.byteLength)),
      )
    }
  }

  // Затем восстанавливаем локально отсутствующие книги. Размер и SHA берутся
  // из подписанных сессией метаданных и проверяются до передачи в воркер.
  for (const book of session.books().filter((item) => !item.deleted && !item.path)) {
    const file = remote.get(book.id)
    if (!file || file.size <= 0 || file.size > MAX_SYNC_FILE_BYTES) continue
    const bytes = new Uint8Array(file.size)
    let offset = 0
    while (offset < file.size) {
      const chunk = await api.downloadBookChunk(book.id, offset, Math.min(FILE_CHUNK_BYTES, file.size - offset))
      if (!chunk.byteLength || offset + chunk.byteLength > file.size) throw new Error('Сервер вернул неполный файл книги.')
      bytes.set(chunk, offset)
      offset += chunk.byteLength
    }
    if ((await digest(bytes)).toLowerCase() !== file.sha256.toLowerCase()) {
      throw new Error('Файл книги повреждён при загрузке.')
    }
    const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
    // PDF.js остаётся ленивым: синхронизация обычного EPUB не должна тянуть
    // тяжёлый PDF-парсер в стартовую оболочку сайта.
    const { attachSyncedBook } = await import('../library/import')
    await attachSyncedBook(book, buffer, file.fileName)
  }
}

async function digest(bytes: Uint8Array): Promise<string> {
  const view = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', view))
  return Array.from(hash, (part) => part.toString(16).padStart(2, '0')).join('')
}

function syncedFileName(book: LibraryBook): string {
  const leaf = book.path.split('/').pop() ?? ''
  const extension = leaf.includes('.') ? leaf.split('.').pop()!.replace(/[^a-z0-9]/gi, '').toLowerCase() : ''
  return `book-${book.id}${extension ? `.${extension}` : ''}`
}

export function SyncController() {
  const account = useAccount()
  const ready = useSession((state) => state.ready)
  const revision = useSession((state) => state.library.revision)
  useEffect(() => {
    if (!ready || !account.data || !navigator.onLine) return
    const timer = window.setTimeout(() => { void syncNow() }, 700)
    return () => window.clearTimeout(timer)
  }, [account.data, ready, revision])
  useEffect(() => {
    const online = () => { if (account.data) void syncNow() }
    const companionDirty = () => { if (account.data && navigator.onLine) void syncNow() }
    window.addEventListener('online', online)
    window.addEventListener('wolfy:companion-dirty', companionDirty)
    return () => {
      window.removeEventListener('online', online)
      window.removeEventListener('wolfy:companion-dirty', companionDirty)
    }
  }, [account.data])
  return null
}

function bookToWire(book: LibraryBook): api.SyncBook {
  return {
    id: book.id, title: book.title, author: book.author ?? '', format: book.format,
    sourceKey: book.sourceKey, chapterCount: book.chapters,
    lastChapter: book.progress.chapter, lastOffset: Math.round(book.progress.withinChapter * 10_000),
    shelf: book.shelf ?? '', position: 0, rev: book.rev, deleted: book.deleted,
  }
}

function bookFromWire(book: api.SyncBook, previous?: LibraryBook): LibraryBook {
  return {
    id: book.id, path: previous?.path ?? '', title: book.title, author: book.author || null,
    format: book.format, sourceKey: book.sourceKey, addedAt: previous?.addedAt ?? 0,
    chapters: book.chapterCount, progress: { chapter: book.lastChapter, withinChapter: book.lastOffset / 10_000, openedAt: previous?.progress.openedAt ?? 0 },
    shelf: book.shelf || null, rev: book.rev, dirty: false, deleted: book.deleted,
  }
}

function cardToWire(card: Card): api.SyncCard {
  return {
    id: card.id, bookId: card.bookId, kind: card.kind, surface: card.surface, lemma: card.lemma,
    translation: card.translation, context: card.context, pos: card.pos, cefr: card.cefr,
    hp: card.hp, streak: card.streak, intervalDays: card.intervalDays,
    dueAt: instant(card.dueAt), reviewedAt: card.reviewedAt ? instant(card.reviewedAt) : null,
    rev: card.rev, deleted: card.deleted,
  }
}

function cardFromWire(card: api.SyncCard, previous?: Card): Card {
  return {
    id: card.id, bookId: card.bookId, kind: card.kind === 'phrase' || card.kind === 'rule' ? card.kind : 'word',
    surface: card.surface, lemma: card.lemma, translation: card.translation, context: card.context,
    pos: card.pos, cefr: card.cefr, hp: card.hp, streak: card.streak,
    intervalDays: card.intervalDays, dueAt: parseInstant(card.dueAt), reviewedAt: parseInstant(card.reviewedAt ?? ''),
    addedAt: previous?.addedAt ?? parseInstant(card.dueAt), rev: card.rev, dirty: false, deleted: card.deleted,
  }
}

function instant(value: number): string { return value > 0 ? new Date(value).toISOString() : '' }
function parseInstant(value: string): number { const parsed = Date.parse(value); return Number.isFinite(parsed) ? parsed : 0 }
function hasLocalReadingChoices(settings: AppSettings): boolean { return settings.onboardingSeen || settings.theme !== 'Paper' || settings.fontScale !== 1 || settings.lineScale !== 1 }
function isSettings(value: unknown): value is AppSettings {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<AppSettings>
  return typeof candidate.theme === 'string' && typeof candidate.fontScale === 'number' && typeof candidate.lineScale === 'number'
}
