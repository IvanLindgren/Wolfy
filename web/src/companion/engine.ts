/**
 * Локальный движок реплик и оценка настроения страницы.
 *
 * Повторяет Kotlin-версию поведение в поведение: те же кулдауны, тот же
 * антиповтор, тот же seed-алгоритм, тот же лексикон. Обычное чтение не делает
 * ни одного сетевого запроса на всех трёх платформах.
 */
import { fallbackPack, type CompanionPhrase, type CompanionPhrasePack, type CompanionProfile } from './model'

export const UNPROMPTED_GAP_MS = 7 * 60_000
export const MAX_PER_SESSION = 5
export const MAX_COOLDOWN = 120

export type ReactionEvent =
  | { kind: 'session_start' }
  | { kind: 'session_resume' }
  | { kind: 'steady_reading' }
  | { kind: 'page_completed' }
  | { kind: 'chapter_completed' }
  | { kind: 'long_session' }
  | { kind: 'return_after_break' }
  | { kind: 'difficult_page'; difficulty: number }
  | { kind: 'mood'; mood: string }

export interface ReactionContext {
  sessionMinutes: number
  overlayOpen: boolean
  scrolling: boolean
  reactionsEnabled: boolean
}

export interface Decision {
  phrase: CompanionPhrase | null
  at: number
}

function scenarioOf(event: ReactionEvent): string | null {
  switch (event.kind) {
    case 'mood':
      return event.mood === 'joy' ? 'mood_joy'
      : event.mood === 'sadness' ? 'mood_sadness'
      : event.mood === 'tension' ? 'mood_tension'
      : event.mood === 'mystery' ? 'mood_mystery'
      : null
    case 'difficult_page':
      return 'difficult_page'
    default:
      return event.kind
  }
}

function isPrompted(event: ReactionEvent): boolean {
  return event.kind === 'session_start' || event.kind === 'chapter_completed' || event.kind === 'return_after_break'
}

/** Seed из профиля и дня: дни меняют реплики, устройства согласованы. */
export function seedFor(profileId: string, epochDay: number): number {
  let hash = 1469598103934665603n
  for (const ch of profileId) {
    hash ^= BigInt(ch.codePointAt(0) ?? 0) & 0xffn
    hash *= 1099511628211n
    hash &= 0xffffffffffffffffn
  }
  hash ^= BigInt(epochDay) * 31n
  hash *= 1099511628211n
  hash &= 0xffffffffffffffffn
  return Number(hash & 0x7fffffffn)
}

export class CompanionReactionEngine {
  private readonly pack: CompanionPhrasePack
  private readonly clock: () => number
  private rng: number
  private recentIds: string[] = []
  private recentTexts: string[] = []
  private nextAllowedAt = 0
  private scenarioCooldown = new Map<string, number>()
  private shownThisSession = 0

  constructor(profile: CompanionProfile, clock: () => number = () => Date.now()) {
    this.pack = profile.phrasePack && profile.phrasePack.phrases.length === 100
      ? profile.phrasePack
      : fallbackPack(profile.locale)
    this.clock = clock
    this.rng = seedFor(profile.id, 0)
  }

  newSession(): void {
    this.shownThisSession = 0
  }

  restoreHistory(ids: string[], nextAllowedAt: number, shown: number): void {
    this.recentIds = ids.slice(-20)
    this.nextAllowedAt = nextAllowedAt
    this.shownThisSession = shown
  }

  historyIds(): string[] {
    return [...this.recentIds]
  }

  nextAllowedAtValue(): number {
    return this.nextAllowedAt
  }

  shownThisSessionCount(): number {
    return this.shownThisSession
  }

  decide(event: ReactionEvent, context: ReactionContext): Decision {
    const now = this.clock()
    const silence: Decision = { phrase: null, at: now }
    if (!context.reactionsEnabled) return silence
    if (context.overlayOpen || context.scrolling) return silence
    const scenario = scenarioOf(event)
    if (!scenario) return silence
    if (now < this.nextAllowedAt) return silence
    if (now < (this.scenarioCooldown.get(scenario) ?? 0)) return silence
    const prompted = isPrompted(event)
    if (!prompted && this.shownThisSession >= MAX_PER_SESSION) return silence

    const candidates = this.pack.phrases
      .filter((phrase) => phrase.scenario === scenario)
      .filter((phrase) => !this.recentIds.includes(phrase.id))
      .filter((phrase) => !this.recentTexts.includes(phrase.text))
      .filter((phrase) => context.sessionMinutes >= phrase.minMinutes)
      .filter((phrase) => phrase.moods.length === 0 || (event.kind === 'mood' && phrase.moods.includes(event.mood)))
    if (candidates.length === 0) return silence

    // Детерминированный выбор: LCG вместо случайности платформы.
    this.rng = (Math.imul(this.rng, 6364136223846793) + 1442695040) >>> 0
    const chosen: CompanionPhrase = candidates[(this.rng >>> 1) % candidates.length]!
    const cooldownMs = Math.min(Math.max(chosen.cooldownMinutes, 0), MAX_COOLDOWN) * 60_000
    this.nextAllowedAt = now + Math.max(UNPROMPTED_GAP_MS, cooldownMs)
    this.scenarioCooldown.set(scenario, now + cooldownMs)
    if (!prompted) this.shownThisSession += 1
    this.recentIds.push(chosen.id)
    if (this.recentIds.length > 20) this.recentIds.shift()
    this.recentTexts.push(chosen.text)
    if (this.recentTexts.length > 10) this.recentTexts.shift()
    return { phrase: chosen, at: now }
  }

  noteManualShow(): void {
    this.nextAllowedAt = this.clock() + UNPROMPTED_GAP_MS
  }
}

// ---------- настроение страницы ----------

export const MOOD_NEUTRAL = 'neutral'

export interface MoodResult {
  mood: string
  confidence: number
  difficulty: number
}

const MOOD_LEXICON: Record<string, string[]> = {
  joy: ['happy', 'joy', 'joyful', 'laugh', 'laughed', 'smile', 'smiled', 'delight', 'wonderful', 'glad', 'cheerful', 'bright', 'merry', 'celebration', 'dance', 'люб', 'радост', 'весел', 'счастл', 'улыб', 'смех', 'смеял', 'праздник', 'светл', 'прекрасн', 'чудесн'],
  sadness: ['sad', 'sorrow', 'grief', 'cry', 'cried', 'tears', 'weep', 'lonely', 'misery', 'mourn', 'goodbye', 'lost', 'pain', 'hurt', 'груст', 'печал', 'тоска', 'плак', 'слёз', 'слез', 'одинок', 'прощай', 'боль', 'утрат', 'скорб'],
  tension: ['fear', 'afraid', 'terror', 'panic', 'danger', 'dangerous', 'threat', 'scream', 'shout', 'blood', 'fight', 'attack', 'chase', 'escape', 'sudden', 'страх', 'ужас', 'паник', 'опасн', 'угроз', 'крик', 'кров', 'драк', 'напад', 'погон', 'бежал', 'внезапн', 'тревог'],
  mystery: ['mystery', 'mysterious', 'secret', 'shadow', 'whisper', 'strange', 'riddle', 'hidden', 'vanish', 'disappeared', 'clue', 'fog', 'dark', 'silence', 'тайн', 'загадк', 'секрет', 'тень', 'шёпот', 'шепот', 'странн', 'скрыт', 'исчез', 'пропал', 'туман', 'темнот', 'тишин'],
  calm: ['calm', 'quiet', 'peace', 'gentle', 'soft', 'warm', 'slow', 'rest', 'breeze', 'спокой', 'тих', 'мир', 'нежн', 'мягк', 'тепл', 'медленн', 'отдых', 'ветер'],
}

const DIFFICULT = ['moreover', 'nevertheless', 'notwithstanding', 'consequently', 'furthermore', 'hitherto', 'thereby', 'wherein', 'hereby', 'henceforth', 'впрочем', 'следовательно', 'невзирая']
const NEGATIONS = new Set(['not', 'no', 'never', 'neither', 'nor', 'without', 'не', 'нет', 'ни', 'без', 'вовсе'])

const MAX_WORDS = 1200
const CONFIDENT_HITS = 2
const CONFIDENT_DENSITY = 0.12

/**
 * Оценка фрагмента. Компаньон говорит о тексте страницы, а не о состоянии
 * читателя: «здесь тревожно», а не «тебе страшно».
 */
export function analyzeMood(text: string): MoodResult {
  const tokens = text.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean)
  const limited = tokens.slice(-MAX_WORDS)
  const hits: Record<string, number> = { joy: 0, sadness: 0, tension: 0, mystery: 0, calm: 0 }
  let difficult = 0

  for (const token of limited) {
    if (NEGATIONS.has(token)) {
      continue
    }
    for (const [mood, lexicon] of Object.entries(MOOD_LEXICON)) {
      if (lexicon.some((stem) => token.startsWith(stem))) hits[mood] = (hits[mood] ?? 0) + 1
    }
    if (DIFFICULT.some((stem) => token.startsWith(stem))) difficult += 1
  }

  let best = 'neutral'
  let bestHits = 0
  for (const [mood, count] of Object.entries(hits)) {
    if (count > bestHits) {
      best = mood
      bestHits = count
    }
  }
  const density = limited.length === 0 ? 0 : bestHits / limited.length
  const confident = bestHits > 0 && (bestHits >= CONFIDENT_HITS || density >= CONFIDENT_DENSITY)
  const mood = confident ? best : MOOD_NEUTRAL
  const confidence = !confident ? 0 : bestHits >= CONFIDENT_HITS ? Math.min(1, bestHits / 6) : (density / CONFIDENT_DENSITY) * 0.5
  return { mood, confidence, difficulty: Math.min(1, difficult / 20) }
}
