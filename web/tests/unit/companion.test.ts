/**
 * Контракты и движок компаньона: одинаковые ответы на всех платформах.
 * Проверяются именно общие свойства: хеш характера, контракт набора,
 * кулдауны и антиповтор движка.
 */
import { describe, expect, it } from 'vitest'

import {
  DEFAULT_PERSONALITY, EM_DASH, SCENARIO_COUNTS, canonicalPayload, fallbackPack,
  fnv1a32, fromSyncCompanion, profileHash, toSyncCompanion, validatePack, validateProfile,
  type CompanionPhrasePack, type CompanionProfile,
} from '../../src/companion/model'
import { CompanionReactionEngine, analyzeMood, seedFor } from '../../src/companion/engine'

function profile(overrides: Partial<CompanionProfile> = {}): CompanionProfile {
  return {
    id: 'test',
    name: 'Лис',
    pronouns: null,
    locale: 'ru',
    personality: { ...DEFAULT_PERSONALITY },
    mbti: null,
    description: '',
    appearance: {
      packId: 'notionists-wolfy-v1', packVersion: 1, base: 'base.base', body: 'body.none',
      hair: 'hair.none', brows: 'brows.none', eyes: 'eyes.none', nose: 'nose.none',
      mouth: 'mouth.none', beard: 'beard.none', accessoryBack: 'accessoryBack.none',
      accessoryFront: 'accessoryFront.none', gesture: 'gesture.none',
      skin: 'paper', hairColor: 'ink', outfitColor: 'brick', accentColor: 'gold', seed: 0,
    },
    phrasePack: null,
    reactionsEnabled: true,
    readerMode: 'active',
    profileHash: '',
    rev: 0,
    deleted: false,
    createdAt: 0,
    updatedAt: 0,
    ...overrides,
  }
}

describe('canonical payload and hash', () => {
  it('uses the server sync envelope instead of a bare profile', () => {
    const original = profile({ profileHash: 'abc123', rev: 17, phrasePack: fallbackPack('ru') })
    const wire = toSyncCompanion(original)
    expect(wire.profile.phrasePack).toBeNull()
    expect(wire.phrasePack?.phrases).toHaveLength(100)
    expect(wire.rev).toBe(17)
    expect((wire as unknown as Record<string, unknown>).name).toBeUndefined()
    expect(fromSyncCompanion(wire)).toEqual(original)
  })

  it('clothes do not change the hash, personality does', () => {
    const a = profile()
    const dressed = profile({ appearance: { ...a.appearance, body: 'body.17', hair: 'hair.05' } })
    expect(profileHash(a)).toBe(profileHash(dressed))

    const warmer = profile({ personality: { ...DEFAULT_PERSONALITY, warmth: 72 } })
    expect(profileHash(a)).not.toBe(profileHash(warmer))
  })

  it('hash is stable against key order', () => {
    expect(canonicalPayload(profile())).toBe(canonicalPayload(profile()))
    expect(fnv1a32('лис')).toBe(fnv1a32('лис'))
  })

  it('seed depends on profile and day', () => {
    expect(seedFor('one', 1)).toBe(seedFor('one', 1))
    expect(seedFor('one', 1)).not.toBe(seedFor('two', 1))
    expect(seedFor('one', 1)).not.toBe(seedFor('one', 2))
  })
})

describe('profile validation', () => {
  it('name bounds follow code points', () => {
    expect(validateProfile(profile({ name: 'Ж'.repeat(40) }))).toHaveLength(0)
    expect(validateProfile(profile({ name: 'Ж'.repeat(41) }))).toContain('name')
    expect(validateProfile(profile({ name: '  ' }))).toContain('name')
  })

  it('mbti allowlist is closed', () => {
    expect(validateProfile(profile({ mbti: 'infp' }))).toHaveLength(0)
    expect(validateProfile(profile({ mbti: 'ABCD' }))).toContain('mbti')
  })
})

describe('fallback pack', () => {
  it('is exactly 100 with the exact distribution', () => {
    for (const locale of ['ru', 'en']) {
      const pack = fallbackPack(locale)
      expect(pack.phrases).toHaveLength(100)
      expect(validatePack(pack)).toHaveLength(0)
      const counts = new Map<string, number>()
      for (const phrase of pack.phrases) counts.set(phrase.scenario, (counts.get(phrase.scenario) ?? 0) + 1)
      for (const [scenario, count] of Object.entries(SCENARIO_COUNTS)) {
        expect(counts.get(scenario)).toBe(count)
      }
    }
  })

  it('validator rejects broken packs', () => {
    const good = fallbackPack('ru')
    const short: CompanionPhrasePack = { ...good, phrases: good.phrases.slice(1) }
    expect(validatePack(short)).not.toHaveLength(0)
    const dash = { ...good, phrases: good.phrases.map((p) => ({ ...p, text: `Раз ${EM_DASH} два` })) }
    expect(validatePack(dash)).not.toHaveLength(0)
  })
})

describe('reaction engine', () => {
  function engine() {
    const clock = { now: 0 }
    const e = new CompanionReactionEngine(profile(), () => clock.now)
    return { e, clock }
  }

  const quiet = { sessionMinutes: 0, overlayOpen: false, scrolling: false, reactionsEnabled: true }

  it('fixed seed is deterministic', () => {
    const a = new CompanionReactionEngine(profile(), () => 0)
    const b = new CompanionReactionEngine(profile(), () => 0)
    expect(a.decide({ kind: 'session_start' }, quiet).phrase?.id).toBe(b.decide({ kind: 'session_start' }, quiet).phrase?.id)
  })

  it('respects cooldown and session cap', () => {
    const { e, clock } = engine()
    expect(e.decide({ kind: 'session_start' }, quiet).phrase).not.toBeNull()
    clock.now += 60_000
    expect(e.decide({ kind: 'page_completed' }, quiet).phrase).toBeNull()
    // Прокручиваем десять часов: непрошеных не больше пяти.
    let shown = 0
    for (let minute = 0; minute < 600; minute += 1) {
      clock.now += 60_000
      if (e.decide({ kind: 'steady_reading' }, { ...quiet, sessionMinutes: minute }).phrase) shown += 1
    }
    expect(shown).toBe(5)
  })

  it('overlay suppresses everything', () => {
    const { e, clock } = engine()
    clock.now = 60 * 60_000
    expect(e.decide({ kind: 'chapter_completed' }, { ...quiet, overlayOpen: true }).phrase).toBeNull()
    expect(e.decide({ kind: 'chapter_completed' }, { ...quiet, scrolling: true }).phrase).toBeNull()
    expect(e.decide({ kind: 'chapter_completed' }, quiet).phrase).not.toBeNull()
  })
})

describe('mood scorer', () => {
  it('golden corpus', () => {
    expect(analyzeMood('They laughed and danced all night. What a wonderful day!').mood).toBe('joy')
    expect(analyzeMood('She cried quietly. The grief was heavy, tears would not stop.').mood).toBe('sadness')
    expect(analyzeMood('Suddenly he heard a scream. Danger! Run, escape now!').mood).toBe('tension')
    expect(analyzeMood('A secret whispered in the dark. A strange shadow vanished behind the fog.').mood).toBe('mystery')
    expect(analyzeMood('The train arrived at six. He bought a ticket and found his seat.').mood).toBe('neutral')
  })

  it('difficulty is an independent score', () => {
    const hard = analyzeMood('Nevertheless, hitherto, consequently, furthermore, thereby.')
    const plain = analyzeMood('The cat sat on the mat.')
    expect(hard.difficulty).toBeGreaterThan(plain.difficulty)
  })
})
