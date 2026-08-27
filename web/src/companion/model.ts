/**
 * Контракты компаньона: те же имена полей, что уезжают по сети.
 *
 * Веб повторяет модель KMP: один профиль, десять шкал, внешность слоями
 * notionists-wolfy-v1 и набор из ста реплик. Расхождение имён сломало бы
 * синхронизацию между браузером и телефоном.
 */

export const PACK_ID = 'notionists-wolfy-v1'
export const PHRASE_COUNT = 100
export const EM_DASH = '\u2014'
export const EN_DASH = '\u2013'

export const PERSONALITY_KEYS = [
  'warmth', 'playfulness', 'energy', 'directness', 'optimism',
  'emotionality', 'supportStyle', 'verbosity', 'curiosity', 'formality',
] as const

export type PersonalityKey = (typeof PERSONALITY_KEYS)[number]

export type CompanionPersonality = Record<PersonalityKey, number>

export const DEFAULT_PERSONALITY: CompanionPersonality = PERSONALITY_KEYS.reduce(
  (acc, key) => ({ ...acc, [key]: 50 }),
  {} as CompanionPersonality,
)

export const LAYER_ORDER = [
  'accessoryBack', 'base', 'body', 'hair', 'brows', 'eyes', 'nose', 'mouth',
  'beard', 'gesture', 'accessoryFront',
] as const

export type CompanionSlot = (typeof LAYER_ORDER)[number]

export interface CompanionAppearance {
  packId: string
  packVersion: number
  base: string
  body: string
  hair: string
  brows: string
  eyes: string
  nose: string
  mouth: string
  beard: string
  accessoryBack: string
  accessoryFront: string
  gesture: string
  skin: string
  hairColor: string
  outfitColor: string
  accentColor: string
  seed: number
}

export const DEFAULT_APPEARANCE: CompanionAppearance = {
  packId: PACK_ID,
  packVersion: 1,
  base: 'base.base',
  body: 'body.none',
  hair: 'hair.none',
  brows: 'brows.none',
  eyes: 'eyes.none',
  nose: 'nose.none',
  mouth: 'mouth.none',
  beard: 'beard.none',
  accessoryBack: 'accessoryBack.none',
  accessoryFront: 'accessoryFront.none',
  gesture: 'gesture.none',
  skin: 'paper',
  hairColor: 'ink',
  outfitColor: 'brick',
  accentColor: 'gold',
  seed: 0,
}

export interface CompanionPhrase {
  id: string
  scenario: string
  text: string
  minMinutes: number
  cooldownMinutes: number
  weight: number
  moods: string[]
  motion: string
}

export interface CompanionPhrasePack {
  schemaVersion: number
  profileHash: string
  locale: string
  generatedAt: number
  source: 'generated' | 'fallback' | 'cache'
  phrases: CompanionPhrase[]
}

export interface CompanionProfile {
  id: string
  name: string
  pronouns?: string | null
  locale: string
  personality: CompanionPersonality
  mbti?: string | null
  description: string
  appearance: CompanionAppearance
  phrasePack: CompanionPhrasePack | null
  reactionsEnabled: boolean
  readerMode: 'off' | 'quiet' | 'active'
  /** Старые локальные профили не содержат поле и считаются не согласившимися. */
  aiConsentAt?: number
  profileHash: string
  rev: number
  deleted: boolean
  createdAt: number
  updatedAt: number
}

/** Точный серверный конверт отдельной LWW-записи компаньона. */
export interface SyncCompanion {
  profile: CompanionProfile
  phrasePack: CompanionPhrasePack | null
  profileHash: string
  rev: number
  deleted: boolean
}

export function toSyncCompanion(profile: CompanionProfile): SyncCompanion {
  return {
    profile: { ...profile, phrasePack: null, profileHash: '', rev: 0, deleted: false },
    phrasePack: profile.phrasePack,
    profileHash: profile.profileHash,
    rev: profile.rev,
    deleted: profile.deleted,
  }
}

export function fromSyncCompanion(wire: SyncCompanion): CompanionProfile {
  return {
    ...wire.profile,
    phrasePack: wire.phrasePack ?? null,
    profileHash: wire.profileHash,
    rev: wire.rev,
    deleted: wire.deleted,
  }
}

/** Распределение ста реплик по сценариям. Контракт фиксирован. */
export const SCENARIO_COUNTS: Record<string, number> = {
  session_start: 10, session_resume: 8, steady_reading: 18,
  page_completed: 10, chapter_completed: 10, long_session: 8,
  return_after_break: 8, difficult_page: 8,
  mood_joy: 4, mood_sadness: 4, mood_tension: 4, mood_mystery: 4,
  session_end: 4,
}

export const MBTI_CODES = [
  'INTJ', 'INTP', 'ENTJ', 'ENTP', 'INFJ', 'INFP', 'ENFJ', 'ENFP',
  'ISTJ', 'ISFJ', 'ESTJ', 'ESFJ', 'ISTP', 'ISFP', 'ESTP', 'ESFP',
]

/** Палитры редактора: тёплые, газетные, без кислоты. */
export const PALETTES = {
  skin: [
    ['paper', '#F7E1CE'], ['light', '#F2C6A0'], ['tan', '#DBA97E'],
    ['brown', '#8D5A3B'], ['deep', '#5C3A25'],
  ],
  hairColor: [
    ['ink', '#1A1816'], ['chestnut', '#5A3825'], ['auburn', '#7A4A2B'],
    ['sand', '#B98F5E'], ['gray', '#8B8B8B'],
  ],
  outfitColor: [
    ['brick', '#8C3B2E'], ['navy', '#274357'], ['forest', '#4C6B44'],
    ['slate', '#4A4E57'], ['plum', '#5C3A56'],
  ],
  accentColor: [
    ['gold', '#C9A227'], ['copper', '#B06A3B'], ['steel', '#9AA5AE'], ['cream', '#EADFC8'],
  ],
} as const

export const POLAR_LABELS: [string, string, PersonalityKey][] = [
  ['сдержанный', 'тёплый', 'warmth'],
  ['серьёзный', 'игривый', 'playfulness'],
  ['спокойный', 'энергичный', 'energy'],
  ['тактичный', 'прямой', 'directness'],
  ['скептичный', 'оптимистичный', 'optimism'],
  ['рациональный', 'эмоциональный', 'emotionality'],
  ['поддерживает', 'бросает вызов', 'supportStyle'],
  ['лаконичный', 'разговорчивый', 'verbosity'],
  ['практичный', 'любопытный', 'curiosity'],
  ['дружеский', 'формальный', 'formality'],
]

export const SLOT_TITLES: Record<string, string> = {
  base: 'Основа', hair: 'Причёска', brows: 'Брови', eyes: 'Глаза', nose: 'Нос',
  mouth: 'Рот', beard: 'Борода', body: 'Одежда', accessoryFront: 'Аксессуар',
  accessoryBack: 'Спина', gesture: 'Жест',
}

/** Имя предмета: слот и номер; «none» честно зовётся «Нет». */
export function assetLabel(assetId: string): string {
  const [slot = '', variant = ''] = assetId.split('.')
  if (variant === 'none') return 'Нет'
  const title = SLOT_TITLES[slot] ?? slot
  const number = Number(variant)
  return Number.isNaN(number) ? title : `${title} ${number}`
}

export function appearanceAsset(appearance: CompanionAppearance, slot: string): string {
  return (appearance as unknown as Record<string, string>)[slot] ?? `${slot}.none`
}

export function appearanceWithAsset(appearance: CompanionAppearance, slot: string, assetId: string): CompanionAppearance {
  return { ...appearance, [slot]: assetId } as CompanionAppearance
}

/** Короткая строка характера: заметные полюса, без чисел. */
export function characterLine(profile: CompanionProfile): string {
  const p = profile.personality
  const parts: string[] = []
  if (p.warmth >= 65) parts.push('тёплый')
  else if (p.warmth <= 35) parts.push('сдержанный')
  if (p.playfulness >= 65) parts.push('игривый')
  else if (p.playfulness <= 35) parts.push('серьёзный')
  if (p.energy >= 65) parts.push('энергичный')
  else if (p.energy <= 35) parts.push('спокойный')
  if (p.verbosity >= 65) parts.push('разговорчивый')
  else if (p.verbosity <= 35) parts.push('лаконичный')
  if (parts.length === 0) parts.push('ровный и внимательный')
  return parts.join(', ')
}

/** Канонический JSON персональных полей: порядок ключей фиксирован. */
export function canonicalPayload(profile: CompanionProfile): string {
  const personality = PERSONALITY_KEYS.map((key) => `"${key}":${profile.personality[key] ?? 50}`).join(',')
  const mbti = profile.mbti ? JSON.stringify(profile.mbti.toUpperCase()) : 'null'
  return `{"locale":${JSON.stringify(profile.locale || 'ru')},"personality":{${personality}},"mbti":${mbti},"description":${JSON.stringify(profile.description.trim())}}`
}

/** FNV-1a 32 по кодовым точкам: тот же хеш, что на сервере и в KMP. */
export function fnv1a32(text: string): string {
  let hash = 0x811c9dc5
  // Кодовые точки, а не коды UTF-16: одна буква обязана давать один хеш
  // на всех платформах.
  for (const codePoint of text) {
    hash ^= codePoint.codePointAt(0) ?? 0
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash.toString(16).padStart(8, '0')
}

export function profileHash(profile: CompanionProfile): string {
  return fnv1a32(canonicalPayload(profile))
}

/** Проверка профиля перед сохранением; список нарушений пуст, когда всё хорошо. */
export function validateProfile(profile: CompanionProfile): string[] {
  const issues: string[] = []
  const nameLength = [...profile.name.trim()].length
  if (nameLength < 1 || nameLength > 40) issues.push('name')
  if ([...(profile.pronouns ?? '')].length > 80) issues.push('pronouns')
  if ([...profile.description].length > 1200) issues.push('description')
  if (profile.mbti && !MBTI_CODES.includes(profile.mbti.toUpperCase())) issues.push('mbti')
  if (profile.locale !== 'ru' && profile.locale !== 'en') issues.push('locale')
  const aiConsentAt = profile.aiConsentAt ?? 0
  if (!Number.isFinite(aiConsentAt) || aiConsentAt < 0) issues.push('aiConsentAt')
  for (const key of PERSONALITY_KEYS) {
    const value = profile.personality[key]
    if (!Number.isInteger(value) || value < 0 || value > 100) issues.push(key)
  }
  return issues
}

/** Контракт набора: сто уникальных реплик с точным распределением. */
export function validatePack(pack: CompanionPhrasePack): string[] {
  const issues: string[] = []
  if (pack.schemaVersion !== 1) issues.push('schemaVersion')
  if (pack.locale !== 'ru' && pack.locale !== 'en') issues.push('locale')
  if (pack.phrases.length !== PHRASE_COUNT) issues.push('count')
  const counts: Record<string, number> = {}
  const seen = new Set<string>()
  for (const phrase of pack.phrases) {
    if (seen.has(phrase.id)) issues.push('duplicateIds')
    seen.add(phrase.id)
    counts[phrase.scenario] = (counts[phrase.scenario] ?? 0) + 1
    const length = [...phrase.text].length
    if (length < 2 || length > 120) issues.push(`text:${phrase.id}`)
    if (phrase.text.includes(EM_DASH) || phrase.text.includes(EN_DASH) || /http|[\u0000-\u001f]/.test(phrase.text)) issues.push(`prohibited:${phrase.id}`)
    if (phrase.minMinutes < 0 || phrase.minMinutes > 90) issues.push(`minMinutes:${phrase.id}`)
    if (phrase.cooldownMinutes < 0 || phrase.cooldownMinutes > 120) issues.push(`cooldown:${phrase.id}`)
    if (phrase.weight < 1 || phrase.weight > 100) issues.push(`weight:${phrase.id}`)
    if (!['none', 'wave', 'nod', 'peek', 'think', 'speak'].includes(phrase.motion)) issues.push(`motion:${phrase.id}`)
    if (phrase.moods.some((mood) => !['joy', 'sadness', 'tension', 'mystery'].includes(mood) || phrase.scenario !== `mood_${mood}`)) issues.push(`moods:${phrase.id}`)
    if (!phrase.scenario.startsWith('mood_') && phrase.moods.length > 0) issues.push(`moods:${phrase.id}`)
  }
  for (const [scenario, count] of Object.entries(SCENARIO_COUNTS)) {
    if (counts[scenario] !== count) issues.push(scenario)
    for (let index = 1; index <= count; index += 1) {
      const expected = `${scenario}.${String(index).padStart(2, '0')}`
      if (!seen.has(expected)) issues.push(`missingId:${expected}`)
    }
  }
  return issues
}

// ---------- встроенный fallback: сто реплик на локаль ----------

type Row = [scenario: string, index: number, text: string, min?: number, cooldown?: number, moods?: string[], motion?: string]

function buildPack(locale: 'ru' | 'en', rows: Row[]): CompanionPhrasePack {
  return {
    schemaVersion: 1,
    profileHash: '',
    locale,
    generatedAt: 0,
    source: 'fallback',
    phrases: rows.map(([scenario, index, text, min = 0, cooldown = 20, moods = [], motion = 'none']) => ({
      id: `${scenario}.${String(index).padStart(2, '0')}`,
      scenario,
      text,
      minMinutes: min,
      cooldownMinutes: cooldown,
      weight: 1,
      moods,
      motion,
    })),
  }
}

const RU_ROWS: Row[] = [
  ['session_start', 1, 'Ну что, почитаем немного?', 0, 20, [], 'wave'],
  ['session_start', 2, 'Рад(а) тебя видеть. С чего начнём?', 0, 20, [], 'wave'],
  ['session_start', 3, 'Устраивайся, я побуду рядом.', 0, 20, [], 'wave'],
  ['session_start', 4, 'Хорошее время для страницы другой.', 0, 20, [], 'nod'],
  ['session_start', 5, 'Сегодня без спешки. Читай как удобно.'],
  ['session_start', 6, 'Я здесь, если захочешь поговорить о прочитанном.'],
  ['session_start', 7, 'Начнём с того места, где остановились.'],
  ['session_start', 8, 'Книга ждёт. Я тоже.', 0, 20, [], 'nod'],
  ['session_start', 9, 'Пару страниц? Это уже немало.'],
  ['session_start', 10, 'Тихий час для чтения. Обойдёмся без суеты.'],
  ['session_resume', 1, 'С возвращением. Продолжаем?'],
  ['session_resume', 2, 'Ты вернулся, и место помнит тебя.'],
  ['session_resume', 3, 'Продолжим с той же страницы.'],
  ['session_resume', 4, 'Отдохнул(а)? Книга никуда не делась.'],
  ['session_resume', 5, 'Мы недалеко ушли. Продолжим спокойно.'],
  ['session_resume', 6, 'Снова здесь. Люблю такие продолжения.'],
  ['session_resume', 7, 'Возьмём с того места, где тихо стало.'],
  ['session_resume', 8, 'Рад(а) продолжению. Без спешки.'],
  ['steady_reading', 1, 'Хороший темп. Мне нравится.', 5],
  ['steady_reading', 2, 'Ты давно не отрывался. Уважаю.', 10],
  ['steady_reading', 3, 'Тихо и спокойно. Так и читается лучше.', 5],
  ['steady_reading', 4, 'Я тут посижу, не отвлекаю.', 15, 30],
  ['steady_reading', 5, 'Страницы идут одна за другой.', 10],
  ['steady_reading', 6, 'Приятно смотреть, как ровно идёт чтение.', 20, 30],
  ['steady_reading', 7, 'Здесь у тебя хорошо получается сосредоточиться.', 15],
  ['steady_reading', 8, 'Если устанешь, я подожду.', 25, 40],
  ['steady_reading', 9, 'Такой ритм подходит книге.', 10],
  ['steady_reading', 10, 'Продолжай, я рядом.', 20, 30],
  ['steady_reading', 11, 'Спокойное чтение лучшее чтение.', 12],
  ['steady_reading', 12, 'Кажется, книга тебе нравится.', 18, 35],
  ['steady_reading', 13, 'Не будем торопиться. Дольше прочитанное живёт.', 8],
  ['steady_reading', 14, 'Ещё немного, и будет хороший кусок.', 22, 40],
  ['steady_reading', 15, 'Я примечаю, как ровно ты читаешь.', 30, 45],
  ['steady_reading', 16, 'Так и держать.', 15],
  ['steady_reading', 17, 'Читается легко, верно?', 25, 40],
  ['steady_reading', 18, 'Долгое тихое чтение. Хорошее дело.', 35, 50],
  ['page_completed', 1, 'Страница. Уже что-то.', 0, 10, [], 'peek'],
  ['page_completed', 2, 'Ещё страница позади.', 0, 10, [], 'peek'],
  ['page_completed', 3, 'Дальше, кажется, будет интереснее.', 0, 10, [], 'peek'],
  ['page_completed', 4, 'Так и до главы недалеко.', 0, 12, [], 'peek'],
  ['page_completed', 5, 'Перевернём спокойно.', 0, 10, [], 'peek'],
  ['page_completed', 6, 'Каждая страница приближает конец.', 0, 12, [], 'peek'],
  ['page_completed', 7, 'Мне тоже любопытно, что там дальше.', 0, 12, [], 'peek'],
  ['page_completed', 8, 'Хорошая страница. Была и такая.', 0, 10, [], 'peek'],
  ['page_completed', 9, 'Продолжение следует, как в газетах.', 0, 12, [], 'peek'],
  ['page_completed', 10, 'Отметили страницу. Идём дальше.', 0, 10, [], 'peek'],
  ['chapter_completed', 1, 'Целая глава! Поздравляю.', 0, 15, [], 'nod'],
  ['chapter_completed', 2, 'Глава закрыта. Хорошая точка.', 0, 15, [], 'nod'],
  ['chapter_completed', 3, 'Ты дошёл до конца главы. Солидно.', 0, 15, [], 'nod'],
  ['chapter_completed', 4, 'Можно выдохнуть: глава позади.', 0, 15, [], 'nod'],
  ['chapter_completed', 5, 'Здесь удобно остановиться. Но можно и дальше.', 0, 15, [], 'nod'],
  ['chapter_completed', 6, 'Прочитана глава. Я это запомнил(а).', 0, 15, [], 'nod'],
  ['chapter_completed', 7, 'Такая отметина идёт книгам на пользу.', 0, 15, [], 'nod'],
  ['chapter_completed', 8, 'Глава далась. Дальше новая история.', 0, 15, [], 'nod'],
  ['chapter_completed', 9, 'Отметим: глава готова.', 0, 15, [], 'nod'],
  ['chapter_completed', 10, 'Конец главы всегда немного праздник.', 0, 15, [], 'nod'],
  ['long_session', 1, 'Ты читаешь давно. Может, короткая пауза?', 45],
  ['long_session', 2, 'Час чтения это серьёзно.', 60],
  ['long_session', 3, 'Долгое чтение утомляет глаза. Загляни в окно.', 50],
  ['long_session', 4, 'Долгая сессия. Гордиться можно, но отдохни.', 45],
  ['long_session', 5, 'Ты прочитал(а) много. Пей воды.', 55],
  ['long_session', 6, 'Такой марафон заслуживает чаю.', 60],
  ['long_session', 7, 'Книга в хорошей форме. Читатель тоже держится.', 50],
  ['long_session', 8, 'Мы долго вместе. Спасибо, что не гонишь.', 65, 60],
  ['return_after_break', 1, 'С возвращением. Книга подождёт сколько нужно.'],
  ['return_after_break', 2, 'Давно не виделись. Начнём отсюда.'],
  ['return_after_break', 3, 'Ничего, что прошло время. Книга не обижается.'],
  ['return_after_break', 4, 'Снова к чтению. По-моему, отличное решение.'],
  ['return_after_break', 5, 'Мы вспомним, где остановились, вместе.'],
  ['return_after_break', 6, 'Прошло времени столько, сколько прошло. Продолжим.'],
  ['return_after_break', 7, 'Рад(а), что заглянул(а). Без нотаций.'],
  ['return_after_break', 8, 'Возвращение лучшая глава.', 0, 20, [], 'wave'],
  ['difficult_page', 1, 'Здесь непросто. Это нормально.'],
  ['difficult_page', 2, 'Сложная страница. Такие бывают у всех.'],
  ['difficult_page', 3, 'Много новых слов. Двигайся медленно, я подожду.'],
  ['difficult_page', 4, 'Тяжёлый кусок. За ним обычно легче.'],
  ['difficult_page', 5, 'Не торопись здесь. Смысл догонит.'],
  ['difficult_page', 6, 'Если нужно перечитать, это не поражение.'],
  ['difficult_page', 7, 'Сложно значит растёшь. Я рядом.'],
  ['difficult_page', 8, 'Страница плотная. Переведи дух.'],
  ['mood_joy', 1, 'Здесь стало светло. Приятно читать.', 0, 15, ['joy']],
  ['mood_joy', 2, 'Весёлое место. Мне тоже понравилось.', 0, 15, ['joy']],
  ['mood_joy', 3, 'Тут хорошо написано. Так и улыбнешься.', 0, 15, ['joy']],
  ['mood_joy', 4, 'Радость на странице передаётся.', 0, 15, ['joy']],
  ['mood_sadness', 1, 'Печальная страница. Побудем в ней тихо.', 0, 15, ['sadness']],
  ['mood_sadness', 2, 'Здесь грустно написано. Это тоже нужно книге.', 0, 15, ['sadness']],
  ['mood_sadness', 3, 'Трогательное место. Читай медленно.', 0, 15, ['sadness']],
  ['mood_sadness', 4, 'Грусть в книгах честная. Я рядом.', 0, 15, ['sadness']],
  ['mood_tension', 1, 'Как напряжено здесь.', 0, 15, ['tension']],
  ['mood_tension', 2, 'Тревожная страница. Держись.', 0, 15, ['tension']],
  ['mood_tension', 3, 'Стало тревожнее. Дальше узнаем.', 0, 15, ['tension']],
  ['mood_tension', 4, 'Сюжет набирает ход. Интересно, куда.', 0, 15, ['tension']],
  ['mood_mystery', 1, 'Загадочно. Мне нравится гадать.', 0, 15, ['mystery']],
  ['mood_mystery', 2, 'Что-то тут нечисто. В хорошем смысле.', 0, 15, ['mystery']],
  ['mood_mystery', 3, 'Тайна сгущается. Запомним детали.', 0, 15, ['mystery']],
  ['mood_mystery', 4, 'Много вопросов. Значит, книга живая.', 0, 15, ['mystery']],
  ['session_end', 1, 'Хорошо почитали. До следующего раза.'],
  ['session_end', 2, 'Заканчиваем на хорошей ноте.'],
  ['session_end', 3, 'Я побуду здесь, пока ты не вернёшься.'],
  ['session_end', 4, 'Спасибо за чтение. Отдохни.'],
]

const EN_ROWS: Row[] = [
  ['session_start', 1, 'Ready for a few pages?', 0, 20, [], 'wave'],
  ['session_start', 2, 'Good to see you. Where do we start?', 0, 20, [], 'wave'],
  ['session_start', 3, 'Get comfortable, I will be right here.', 0, 20, [], 'wave'],
  ['session_start', 4, 'A good time for another page.', 0, 20, [], 'nod'],
  ['session_start', 5, 'No rush today. Read at your pace.'],
  ['session_start', 6, 'I am here if you want to talk about it.'],
  ['session_start', 7, 'We pick up right where you stopped.'],
  ['session_start', 8, 'The book is waiting. So am I.', 0, 20, [], 'nod'],
  ['session_start', 9, 'A couple of pages already counts.'],
  ['session_start', 10, 'A quiet reading hour. No fuss.'],
  ['session_resume', 1, 'Welcome back. Continue?'],
  ['session_resume', 2, 'You are back, and I remember the place.'],
  ['session_resume', 3, 'Same page as before. Shall we?'],
  ['session_resume', 4, 'Rested? The book kept still.'],
  ['session_resume', 5, 'We did not get far. Take it slow.'],
  ['session_resume', 6, 'Here again. I like sequels like this.'],
  ['session_resume', 7, 'Pick up where it got quiet.'],
  ['session_resume', 8, 'Glad to continue. No hurry.'],
  ['steady_reading', 1, 'Good pace. I like it.', 5],
  ['steady_reading', 2, 'You have been at it a while. Respect.', 10],
  ['steady_reading', 3, 'Quiet and steady. That is how books get read.', 5],
  ['steady_reading', 4, 'I will just sit here. Not distracting.', 15, 30],
  ['steady_reading', 5, 'Pages keep turning.', 10],
  ['steady_reading', 6, 'Nice to watch such an even rhythm.', 20, 30],
  ['steady_reading', 7, 'You focus well here.', 15],
  ['steady_reading', 8, 'If you get tired, I will wait.', 25, 40],
  ['steady_reading', 9, 'This rhythm suits the book.', 10],
  ['steady_reading', 10, 'Keep going, I am around.', 20, 30],
  ['steady_reading', 11, 'Calm reading is the best reading.', 12],
  ['steady_reading', 12, 'Seems the book got to you.', 18, 35],
  ['steady_reading', 13, 'No need to rush. Lived-in books last.', 8],
  ['steady_reading', 14, 'A bit more and it is a solid chunk.', 22, 40],
  ['steady_reading', 15, 'I notice how steadily you read.', 30, 45],
  ['steady_reading', 16, 'Keep it like this.', 15],
  ['steady_reading', 17, 'Reads easily, right?', 25, 40],
  ['steady_reading', 18, 'A long quiet read. A good thing.', 35, 50],
  ['page_completed', 1, 'One page. Already something.', 0, 10, [], 'peek'],
  ['page_completed', 2, 'Another page behind you.', 0, 10, [], 'peek'],
  ['page_completed', 3, 'Next one looks promising.', 0, 10, [], 'peek'],
  ['page_completed', 4, 'A chapter is not far now.', 0, 12, [], 'peek'],
  ['page_completed', 5, 'Turn it calmly.', 0, 10, [], 'peek'],
  ['page_completed', 6, 'Every page brings the end closer.', 0, 12, [], 'peek'],
  ['page_completed', 7, 'I am curious what comes next too.', 0, 12, [], 'peek'],
  ['page_completed', 8, 'A good page. They all count.', 0, 10, [], 'peek'],
  ['page_completed', 9, 'To be continued, like in newspapers.', 0, 12, [], 'peek'],
  ['page_completed', 10, 'Page marked. On we go.', 0, 10, [], 'peek'],
  ['chapter_completed', 1, 'A whole chapter! Well done.', 0, 15, [], 'nod'],
  ['chapter_completed', 2, 'Chapter closed. A good stopping point.', 0, 15, [], 'nod'],
  ['chapter_completed', 3, 'You finished the chapter. Solid.', 0, 15, [], 'nod'],
  ['chapter_completed', 4, 'You can exhale: the chapter is done.', 0, 15, [], 'nod'],
  ['chapter_completed', 5, 'A neat place to stop. Or not.', 0, 15, [], 'nod'],
  ['chapter_completed', 6, 'A chapter read. I keep count.', 0, 15, [], 'nod'],
  ['chapter_completed', 7, 'Books benefit from milestones like this.', 0, 15, [], 'nod'],
  ['chapter_completed', 8, 'The chapter is yours. A new story next.', 0, 15, [], 'nod'],
  ['chapter_completed', 9, 'Let the record show: chapter done.', 0, 15, [], 'nod'],
  ['chapter_completed', 10, 'The end of a chapter is a small holiday.', 0, 15, [], 'nod'],
  ['long_session', 1, 'You have read for a while. A short break?', 45],
  ['long_session', 2, 'An hour of reading is serious business.', 60],
  ['long_session', 3, 'Your eyes must be tired. I will watch the book.', 50],
  ['long_session', 4, 'A long session. Be proud, then rest.', 45],
  ['long_session', 5, 'You read a lot. Drink some water.', 55],
  ['long_session', 6, 'Such a marathon deserves tea.', 60],
  ['long_session', 7, 'The book is in good shape. So is the reader.', 50],
  ['long_session', 8, 'We have been together long. Thanks for having me.', 65, 60],
  ['return_after_break', 1, 'Welcome back. The book waits as long as needed.'],
  ['return_after_break', 2, 'Long time. We start from here.'],
  ['return_after_break', 3, 'No matter the pause. Books hold no grudge.'],
  ['return_after_break', 4, 'Back to reading. A fine decision.'],
  ['return_after_break', 5, 'We will recall where you stopped, together.'],
  ['return_after_break', 6, 'As much time passed as passed. Onwards.'],
  ['return_after_break', 7, 'Glad you dropped in. No lectures.'],
  ['return_after_break', 8, 'Returning is the best chapter.', 0, 20, [], 'wave'],
  ['difficult_page', 1, 'This one is hard. That is normal.'],
  ['difficult_page', 2, 'A tricky page. Happens to everyone.'],
  ['difficult_page', 3, 'Lots of new words. Go slowly, I will wait.'],
  ['difficult_page', 4, 'A dense stretch. Usually lighter after.'],
  ['difficult_page', 5, 'Do not rush here. Meaning catches up.'],
  ['difficult_page', 6, 'Rereading is not defeat.'],
  ['difficult_page', 7, 'Hard means growing. I am nearby.'],
  ['difficult_page', 8, 'A thick page. Take a breath.'],
  ['mood_joy', 1, 'It got brighter here. Nice to read.', 0, 15, ['joy']],
  ['mood_joy', 2, 'A cheerful spot. I enjoyed it too.', 0, 15, ['joy']],
  ['mood_joy', 3, 'Well written. You almost smile.', 0, 15, ['joy']],
  ['mood_joy', 4, 'Joy on the page is contagious.', 0, 15, ['joy']],
  ['mood_sadness', 1, 'A sad page. Let us sit with it quietly.', 0, 15, ['sadness']],
  ['mood_sadness', 2, 'Written with sorrow. Books need that too.', 0, 15, ['sadness']],
  ['mood_sadness', 3, 'A touching place. Read slowly.', 0, 15, ['sadness']],
  ['mood_sadness', 4, 'Bookish sadness is honest. I am here.', 0, 15, ['sadness']],
  ['mood_tension', 1, 'How tense it is here.', 0, 15, ['tension']],
  ['mood_tension', 2, 'An anxious page. Hold on.', 0, 15, ['tension']],
  ['mood_tension', 3, 'It grew more anxious. We will find out.', 0, 15, ['tension']],
  ['mood_tension', 4, 'The story picks up speed. Where to, I wonder.', 0, 15, ['tension']],
  ['mood_mystery', 1, 'Mysterious. I like guessing.', 0, 15, ['mystery']],
  ['mood_mystery', 2, 'Something is off here. In a good way.', 0, 15, ['mystery']],
  ['mood_mystery', 3, 'The mystery thickens. Remember the details.', 0, 15, ['mystery']],
  ['mood_mystery', 4, 'Many questions. Means the book is alive.', 0, 15, ['mystery']],
  ['session_end', 1, 'A good reading session. Until next time.'],
  ['session_end', 2, 'We stop on a good note.'],
  ['session_end', 3, 'I will stay here until you return.'],
  ['session_end', 4, 'Thanks for reading. Rest well.'],
]

export function fallbackPack(locale: string): CompanionPhrasePack {
  return buildPack(locale === 'en' ? 'en' : 'ru', locale === 'en' ? EN_ROWS : RU_ROWS)
}
