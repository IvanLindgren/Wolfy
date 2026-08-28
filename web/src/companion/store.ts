/**
 * Состояние компаньона в браузере.
 *
 * Хранится локально и синхронизируется как отдельная коллекция payload-а.
 * Черновик редактора существует только в этом браузере и в синхронизацию не
 * едет, пока читатель не нажал «Сохранить».
 */
import { create } from 'zustand'
import { newId } from '../core/clock'

import {
  DEFAULT_APPEARANCE, DEFAULT_PERSONALITY, fallbackPack, profileHash,
  type CompanionPhrasePack, type CompanionProfile,
} from './model'

const STORE_KEY = 'wolfy.companion.profile'
const DRAFT_KEY = 'wolfy.companion.draft'

/** Миграция старых локальных и sync-профилей без поля согласия. */
function normalizeProfile(profile: CompanionProfile): CompanionProfile {
  const aiConsentAt = profile.aiConsentAt ?? 0
  return {
    ...profile,
    presentation: profile.presentation || 'neutral',
    aiConsentAt: Number.isFinite(aiConsentAt) && aiConsentAt > 0 ? aiConsentAt : 0,
  }
}

function loadProfile(): CompanionProfile | null {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as CompanionProfile
    if (parsed.deleted) return null
    return normalizeProfile(parsed)
  } catch {
    return null
  }
}

function tombstone(): CompanionProfile | null {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as CompanionProfile
    return parsed.deleted ? normalizeProfile(parsed) : null
  } catch {
    return null
  }
}

export const COMPANION_DIRTY_EVENT = 'wolfy:companion-dirty'

function notifyDirty(): void {
  window.dispatchEvent(new Event(COMPANION_DIRTY_EVENT))
}

export type PackRequest =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'failed'; message: string; retryable: boolean }

interface CompanionStore {
  profile: CompanionProfile | null
  tombstone: CompanionProfile | null
  draft: CompanionProfile | null
  packRequest: PackRequest

  restore: () => void
  save: (profile: CompanionProfile) => void
  saveDraft: (draft: CompanionProfile | null) => void
  remove: () => void
  attachPack: (pack: CompanionPhrasePack) => void
  markPackLoading: () => void
  markPackReady: () => void
  markPackFailed: (message: string, retryable: boolean) => void
  applyServer: (remote: CompanionProfile | null | undefined, sent?: CompanionProfile | null) => void
  clearTombstone: () => void
  outgoing: () => CompanionProfile | null
}

export const useCompanion = create<CompanionStore>((set, get) => ({
  profile: loadProfile(),
  tombstone: tombstone(),
  draft: (() => {
    try {
      const raw = localStorage.getItem(DRAFT_KEY)
      return raw ? normalizeProfile(JSON.parse(raw) as CompanionProfile) : null
    } catch {
      return null
    }
  })(),
  packRequest: { kind: 'idle' },

  restore: () => set({ profile: loadProfile(), tombstone: tombstone() }),

  save: (profile) => {
    const now = Date.now()
    const fixed: CompanionProfile = {
      ...profile,
      createdAt: profile.createdAt || now,
      updatedAt: now,
      deleted: false,
      profileHash: profileHash(profile),
      rev: 0,
    }
    localStorage.setItem(STORE_KEY, JSON.stringify(fixed))
    localStorage.removeItem(DRAFT_KEY)
    set({ profile: fixed, draft: null })
    notifyDirty()
  },

  saveDraft: (draft) => {
    if (!draft) {
      localStorage.removeItem(DRAFT_KEY)
      set({ draft: null })
      return
    }
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
    set({ draft })
  },

  remove: () => {
    const profile = get().profile
    if (!profile) return
    const tombstoned: CompanionProfile = { ...profile, deleted: true, updatedAt: Date.now() }
    localStorage.setItem(STORE_KEY, JSON.stringify(tombstoned))
    localStorage.removeItem(DRAFT_KEY)
    set({ profile: null, draft: null, tombstone: tombstoned })
    notifyDirty()
  },

  attachPack: (pack) => {
    const profile = get().profile
    if (!profile) return
    get().save({ ...profile, phrasePack: pack })
  },

  markPackLoading: () => set({ packRequest: { kind: 'loading' } }),

  markPackReady: () => set({ packRequest: { kind: 'idle' } }),

  markPackFailed: (message, retryable) => set({ packRequest: { kind: 'failed', message, retryable } }),

  applyServer: (remote, sent = null) => {
    if (!remote) return
    remote = normalizeProfile(remote)
    const { profile, tombstone: localTombstone, draft } = get()
    if (localTombstone) {
      if (sent === localTombstone && sent.deleted && remote.deleted && remote.rev >= sent.rev) {
        localStorage.removeItem(STORE_KEY)
        set({ profile: null, tombstone: null })
      }
      return
    }
    if (profile?.rev === 0 && profile !== sent) return
    if (profile && profile.rev >= remote.rev) return
    if (remote.deleted) return
    localStorage.setItem(STORE_KEY, JSON.stringify(remote))
    set({ profile: remote, draft })
  },

  clearTombstone: () => {
    if (get().tombstone) {
      localStorage.removeItem(STORE_KEY)
      set({ tombstone: null })
    }
  },

  outgoing: () => get().tombstone ?? (get().profile?.rev === 0 ? get().profile : null),
}))

/** Пустой профиль мастера: UUID придумывает клиент, создание работает офлайн. */
export function blankDraft(): CompanionProfile {
  return {
    id: newId(),
    name: '',
    pronouns: null,
    presentation: '',
    locale: 'ru',
    personality: { ...DEFAULT_PERSONALITY },
    mbti: null,
    description: '',
    appearance: { ...DEFAULT_APPEARANCE },
    phrasePack: null,
    reactionsEnabled: true,
    readerMode: 'active',
    aiConsentAt: 0,
    profileHash: '',
    rev: 0,
    deleted: false,
    createdAt: 0,
    updatedAt: 0,
  }
}

export function packFor(profile: CompanionProfile | null): CompanionPhrasePack {
  if (profile?.phrasePack && profile.phrasePack.phrases.length === 100) return profile.phrasePack
  return fallbackPack(profile?.locale ?? 'ru')
}
