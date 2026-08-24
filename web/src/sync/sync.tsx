import { useEffect } from 'react'
import { create } from 'zustand'

import * as api from '../api/client'
import { session, useSession } from '../core/session'
import type { AppSettings, Card, LibraryBook } from '../core/types'
import { useAccount } from '../account/useAccount'

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
  const pending = await session.pending()
  const sent = {
    revision: state.library.revision,
    books: pending.books.map((book) => book.id),
    cards: pending.cards.map((card) => card.id),
  }
  useSyncState.setState({ running: true, pending: sent.books.length + sent.cards.length, error: null })
  try {
    const response = await api.sync({
      cursor: state.library.cursor,
      books: pending.books.map(bookToWire),
      cards: pending.cards.map(cardToWire),
      reading: state.settings,
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
    if (!hasLocalReadingChoices(state.settings) && isSettings(response.reading)) {
      await session.replaceSettings(response.reading)
    }
    const left = await session.pending()
    useSyncState.setState({ running: false, lastSuccess: Date.now(), pending: left.books.length + left.cards.length, error: null })
    return true
  } catch (error) {
    useSyncState.setState({ running: false, error: error instanceof Error ? error.message : 'Синхронизация не состоялась.' })
    return false
  }
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
    window.addEventListener('online', online)
    return () => window.removeEventListener('online', online)
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
