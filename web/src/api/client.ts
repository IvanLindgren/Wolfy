/**
 * HTTP-клиент: единственное место, которое ходит в сеть.
 *
 * Три правила этого слоя.
 *
 * 1. **Токен не живёт в JavaScript.** Сессия — httpOnly-кука, выставленная
 *    сервером; здесь нет ни `localStorage`, ни заголовка `Authorization`.
 *    Запросы идут `credentials: 'same-origin'`, а веб-приложение раздаётся с
 *    того же origin, что и API. В `localStorage` токен доступен любому
 *    скрипту на странице — включая чужой, приехавший с рекламой или
 *    расширением.
 * 2. **Отсутствие сети — не ошибка приложения.** Библиотека, разбор и колоды
 *    работают офлайн; сеть нужна четырём вещам — переводу, ленте, OCR и
 *    синхронизации, — и каждая объясняет своё отсутствие строкой, а не
 *    крутит спиннер.
 * 3. **Ошибка сервера приходит в одной форме** — `{code, message}`, где
 *    `message` уже по-русски и годен к показу без перевода.
 */

/** Признак для сервера: сессию вернуть кукой, а не токеном в теле. */
const COOKIE_SESSION = { 'X-Wolfy-Session': 'cookie' } as const

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, message: string, code = '') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }

  /** Нужен ли вход. По нему экраны предлагают войти вместо показа ошибки. */
  get needsSignIn(): boolean {
    return this.status === 401
  }
}

/** Сети нет: запрос даже не дошёл. Отдельно от ошибки сервера намеренно. */
export class OfflineError extends Error {
  constructor() {
    super('Нет связи с сервером')
    this.name = 'OfflineError'
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
  headers?: Record<string, string>
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      // Кука сессии — та самая, ради которой приложение и API живут на одном
      // origin.
      credentials: 'same-origin',
      headers: {
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...options.headers,
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new OfflineError()
  }

  if (response.status === 204) return undefined as T

  const text = await response.text()
  const payload = text ? safeParse(text) : null

  if (!response.ok) {
    const problem = payload as { error?: string; message?: string; code?: string } | null
    throw new ApiError(
      response.status,
      problem?.message || problem?.error || `Сервер ответил ${response.status}`,
      problem?.code ?? '',
    )
  }

  return payload as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

// --- Возможности сервера ----------------------------------------------------

export interface Capabilities {
  status: string
  translate: boolean
  ocr: boolean
  dictionary: boolean
  signIn: boolean
  register: boolean
  resend: boolean
  google: boolean
  googleClientId: string
  yandex: boolean
}

const NOTHING: Capabilities = {
  status: 'offline',
  translate: false,
  ocr: false,
  dictionary: false,
  signIn: false,
  register: false,
  resend: false,
  google: false,
  googleClientId: '',
  yandex: false,
}

/**
 * Что этот сервер умеет.
 *
 * Кнопка, которая всегда отвечает ошибкой, хуже её отсутствия: по этому
 * ответу экран входа прячет «Создать аккаунт», если регистрация не настроена,
 * а настройки — установку словаря, если словаря на сервере нет.
 */
export async function capabilities(): Promise<Capabilities> {
  try {
    return await request<Capabilities>('/healthz')
  } catch {
    return NOTHING
  }
}

// --- Аккаунт ----------------------------------------------------------------

export interface DeviceInfo {
  id: string
  name: string
  platform: string
}

export interface Account {
  id?: string
  email: string
  displayName: string
}

export type AuthOutcome =
  | { kind: 'signedIn'; account: Account }
  /** Регистрация не выдаёт сессию сразу: почту нужно подтвердить. */
  | { kind: 'awaitingEmail'; email: string }
  | { kind: 'emailNotConfirmed'; email: string }
  | { kind: 'refused'; message: string }
  | { kind: 'offline' }

interface AuthResponse {
  email?: string
  name?: string
  displayName?: string
  user?: { id?: string; email?: string; displayName?: string }
  verificationRequired?: boolean
}

function accountOf(response: AuthResponse, fallbackEmail: string): Account {
  return {
    id: response.user?.id,
    email: response.email || response.user?.email || fallbackEmail,
    displayName: response.name || response.displayName || response.user?.displayName || '',
  }
}

export async function signIn(
  email: string,
  password: string,
  device: DeviceInfo,
): Promise<AuthOutcome> {
  const clean = email.trim()
  try {
    const response = await request<AuthResponse>('/v1/auth/login', {
      method: 'POST',
      headers: COOKIE_SESSION,
      body: { email: clean, password, device },
    })
    return { kind: 'signedIn', account: accountOf(response, clean) }
  } catch (error) {
    if (error instanceof OfflineError) return { kind: 'offline' }
    if (error instanceof ApiError) {
      // 403 у Читавука значит «почта не подтверждена» — и это не отказ, а
      // шаг, который читателю нужно объяснить, а не спрятать за «неверный
      // пароль».
      if (error.status === 403 && /подтверд|unverified/i.test(error.message)) {
        return { kind: 'emailNotConfirmed', email: clean }
      }
      return { kind: 'refused', message: error.message }
    }
    throw error
  }
}

/**
 * Регистрация.
 *
 * Сервер отвечает 202 и требованием подтвердить почту — сессии не будет, пока
 * читатель не сходит в письмо. Экран обязан это показать, а не молча остаться
 * на форме.
 */
export async function register(
  email: string,
  password: string,
  name: string,
  device: DeviceInfo,
): Promise<AuthOutcome> {
  const clean = email.trim()
  try {
    const response = await request<AuthResponse>('/v1/auth/register', {
      method: 'POST',
      headers: COOKIE_SESSION,
      body: { email: clean, password, name: name.trim(), device },
    })
    // Ответ 200 с сессией тоже бывает — если сервер настроен не требовать
    // подтверждения. Тогда читатель уже внутри.
    if (response.verificationRequired === false && response.email) {
      return { kind: 'signedIn', account: accountOf(response, clean) }
    }
    return { kind: 'awaitingEmail', email: clean }
  } catch (error) {
    if (error instanceof OfflineError) return { kind: 'offline' }
    if (error instanceof ApiError) {
      if (error.status === 202) return { kind: 'awaitingEmail', email: clean }
      if (error.status === 501) {
        return { kind: 'refused', message: 'На этом сервере регистрация не настроена.' }
      }
      return { kind: 'refused', message: error.message }
    }
    throw error
  }
}

export async function resendVerification(email: string): Promise<boolean> {
  try {
    await request('/v1/auth/resend-verification', {
      method: 'POST',
      body: { email: email.trim() },
    })
    return true
  } catch {
    return false
  }
}

/**
 * Начинает web-вход через Яндекс.
 *
 * Читавук возвращает браузер на доверенный `/auth/return` Wolfy с одноразовым
 * completion code; уже Wolfy меняет его на общую сессию в httpOnly cookie.
 * Google этот redirect-flow не использует: его GIS ID token идёт сразу в
 * `googleComplete`.
 */
export async function socialStart(
  provider: 'yandex',
  returnUrl: string,
  device: DeviceInfo,
): Promise<string> {
  const response = await request<{ authorizationUrl?: string }>(
    `/v1/auth/${provider}/start`,
    {
      method: 'POST',
      headers: COOKIE_SESSION,
      body: { returnUrl, returnTarget: 'web', device },
    },
  )
  if (!response.authorizationUrl) {
    throw new ApiError(502, 'Сервер не вернул адрес входа.')
  }
  return response.authorizationUrl
}

/** Меняет подписанный Google GIS ID token на общую сессию в httpOnly cookie. */
export async function googleComplete(
  idToken: string,
  device: DeviceInfo,
): Promise<AuthOutcome> {
  try {
    const response = await request<AuthResponse>('/v1/auth/google', {
      method: 'POST',
      headers: COOKIE_SESSION,
      body: { idToken, device },
    })
    return { kind: 'signedIn', account: accountOf(response, '') }
  } catch (error) {
    if (error instanceof OfflineError) return { kind: 'offline' }
    if (error instanceof ApiError) return { kind: 'refused', message: error.message }
    throw error
  }
}

/** Завершает вход через Яндекс: одноразовый код в обмен на сессию. */
export async function yandexComplete(
  code: string,
  device: DeviceInfo,
): Promise<AuthOutcome> {
  try {
    const response = await request<AuthResponse>('/v1/auth/yandex/complete', {
      method: 'POST',
      headers: COOKIE_SESSION,
      body: { code, device },
    })
    return { kind: 'signedIn', account: accountOf(response, '') }
  } catch (error) {
    if (error instanceof OfflineError) return { kind: 'offline' }
    if (error instanceof ApiError) return { kind: 'refused', message: error.message }
    throw error
  }
}

/** Кто вошёл. `null` — никто: это обычное состояние, а не ошибка. */
export async function me(): Promise<Account | null> {
  try {
    return await request<Account>('/v1/me')
  } catch (error) {
    if (error instanceof ApiError && error.needsSignIn) return null
    throw error
  }
}

export async function signOut(): Promise<void> {
  try {
    await request('/v1/auth/logout', { method: 'POST', headers: COOKIE_SESSION })
  } catch {
    // Выход обязан получиться в любом случае: кука истечёт сама, а
    // приложение уже забыло аккаунт.
  }
}

// --- Перевод и словарь ------------------------------------------------------

export async function translate(
  text: string,
  signal?: AbortSignal,
  options: { source?: string; target?: string; context?: string } = {},
): Promise<string> {
  const { source = 'EN', target = 'RU', context = '' } = options
  const response = await request<{ text?: string }>('/v1/translate', {
    method: 'POST',
    body: { text, context, source, target },
    signal,
  })
  return response.text ?? ''
}

/** Сетевое толкование — запасной путь для тех, кто не скачал словарь. */
export async function define(word: string, signal?: AbortSignal) {
  return request<{
    word: string
    pronunciation: string
    translations: string[]
    senses: { pos: string; definition: string }[]
  }>(`/v1/define?word=${encodeURIComponent(word)}`, { signal })
}

// --- Синхронизация ----------------------------------------------------------

export interface SyncBook {
  id: string
  title: string
  author: string
  format: string
  sourceKey: string
  chapterCount: number
  lastChapter: number
  /**
   * Место внутри главы. На сервере это целое — там колонка под смещение в
   * символах. Доля главы умножается на десять тысяч: точности хватает с
   * запасом, а целое переживает любую смену представления.
   */
  lastOffset: number
  shelf: string
  position: number
  rev: number
  deleted: boolean
}

export interface SyncCard {
  id: string
  bookId: string
  kind: string
  surface: string
  lemma: string
  translation: string
  context: string
  pos: string
  cefr: string
  hp: number
  streak: number
  intervalDays: number
  /** RFC 3339 — так время выглядит и в базе, и в логах. */
  dueAt: string
  reviewedAt?: string | null
  rev: number
  deleted: boolean
}

export interface SyncPayload {
  cursor: number
  books: SyncBook[]
  cards: SyncCard[]
  /** Настройки чтения целиком: полтора десятка полей, меняющихся вместе. */
  reading?: unknown
}

export async function sync(payload: SyncPayload): Promise<SyncPayload> {
  return request<SyncPayload>('/v1/sync', { method: 'POST', body: payload })
}

// --- Страница по фото -------------------------------------------------------

export async function recognize(
  image: string,
  mime: string,
  signal?: AbortSignal,
): Promise<string> {
  const response = await request<{ text?: string }>('/v1/ocr', {
    method: 'POST',
    body: { image, mime },
    signal,
  })
  return response.text ?? ''
}

// --- Лента открытых изданий -------------------------------------------------

export interface DiscoveryProfile {
  englishLevel: string
  genres: string[]
  onboardingComplete: boolean
}

export interface DiscoveryItem {
  id: string
  contentType: string
  title: string
  author: string
  summary: string
  genres: string[]
  level: string
  coverUrl: string
  pageUrl: string
  liked: boolean
  added: boolean
}

export interface DiscoveryPage {
  items: DiscoveryItem[]
  nextCursor: number
  hasMore: boolean
}

export async function discoveryProfile(): Promise<DiscoveryProfile> {
  return request<DiscoveryProfile>('/v1/discovery/profile')
}

export async function saveDiscoveryProfile(
  profile: DiscoveryProfile,
): Promise<DiscoveryProfile> {
  return request<DiscoveryProfile>('/v1/discovery/profile', {
    method: 'PUT',
    body: profile,
  })
}

export async function discoveryFeed(cursor = 0): Promise<DiscoveryPage> {
  return request<DiscoveryPage>(`/v1/discovery/feed?cursor=${cursor}`)
}

export async function likeDiscoveryItem(id: string): Promise<void> {
  await request(`/v1/discovery/items/${encodeURIComponent(id)}/like`, { method: 'POST' })
}

// --- Заметки и выделения к книге --------------------------------------------

export interface AnnotationItem {
  id: string
  chapter: number
  /** Полуинтервал номеров токенов главы. У заметки к месту `end === start`. */
  start: number
  end: number
  tone: number | null
  quote: string
  note: string
  /** Версия правки: номер в счётчике Лампорта писателя. */
  rev: number
  /** Стабильный номер устройства, подписавшего правку. */
  writer: string
  /**
   * Поколение серверного снимка, которым запись была проштампована.
   * Ставит только сервер; у местных, ещё не отправленных записей — ноль.
   */
  generation?: number
  createdAt: number
  updatedAt: number
  /** Пометка удаления: доезжает до других устройств, живая запись — нет. */
  deleted?: boolean
}

/** Ответ сервера: слитый список и поколение снимка. */
export interface AnnotationSync {
  items: AnnotationItem[]
  generation: number
}

/**
 * Заметки книги с сервера.
 *
 * `null` значит «сейчас не доезжает»: не вошёл, нет сети, сервер молчит.
 * Синхронизация — необязательная часть заметок, они работают и без неё,
 * поэтому вызывающему возвращается не ошибка, а вежливое «в этот раз никак».
 *
 * `device` и `seen` обязательны: первый регистрирует устройство в реестре
 * сборщика мусора (держатель копии обязан блокировать стирание пометок),
 * второй подтверждает поколение снимка, которое устройство уже долговечно
 * сохранило. Возвращённое поколение клиент подтвердит следующим запросом —
 * после того, как сохранит снимок у себя.
 */
export async function fetchBookAnnotations(
  bookId: string,
  device: string,
  seen: number,
): Promise<AnnotationSync | null> {
  try {
    const payload = await request<{ items?: AnnotationItem[]; generation?: number }>(
      `/v1/books/${encodeURIComponent(bookId)}/annotations?` +
        new URLSearchParams({ device, seen: String(seen) }),
    )
    return {
      items: Array.isArray(payload.items) ? payload.items : [],
      generation: payload.generation ?? 0,
    }
  } catch {
    return null
  }
}

/**
 * Отправляет список целиком; сервер сам сливает его с хранимым по (rev,
 * writer) каждой записи и возвращает слитый результат вместе с поколением
 * снимка. Поэтому порядок отправки между устройствами не важен: у версии
 * больше — та и верна, где бы её ни правили.
 */
export async function pushBookAnnotations(
  bookId: string,
  device: string,
  seen: number,
  items: AnnotationItem[],
): Promise<AnnotationSync | null> {
  try {
    const payload = await request<{ items?: AnnotationItem[]; generation?: number }>(
      `/v1/books/${encodeURIComponent(bookId)}/annotations?` +
        new URLSearchParams({ device, seen: String(seen) }),
      { method: 'PUT', body: { items } },
    )
    return {
      items: Array.isArray(payload.items) ? payload.items : [],
      generation: payload.generation ?? 0,
    }
  } catch {
    return null
  }
}

/**
 * Скачивает EPUB через прокси сервера.
 *
 * Через прокси, а не напрямую: у Standard Ebooks нет CORS-заголовков для
 * нашего origin, и запрос из браузера просто не состоялся бы.
 */
export async function downloadDiscoveryItem(id: string): Promise<{
  bytes: ArrayBuffer
  fileName: string
  title: string
  author: string
  sourceKey: string
}> {
  const response = await fetch(`/v1/discovery/items/${encodeURIComponent(id)}/add`, {
    method: 'POST',
    credentials: 'same-origin',
  })
  if (!response.ok) {
    throw new ApiError(response.status, 'Книгу не удалось скачать.')
  }
  // Название и автор приходят заголовками, а не в теле: тело — это EPUB.
  const header = (name: string) =>
    decodeURIComponent((response.headers.get(name) ?? '').replace(/\+/g, ' '))
  const disposition = response.headers.get('content-disposition') ?? ''
  const named = /filename\*=UTF-8''([^;]+)/.exec(disposition)?.[1]

  return {
    bytes: await response.arrayBuffer(),
    fileName: named ? decodeURIComponent(named) : `${id}.epub`,
    title: header('X-Wolfy-Title'),
    author: header('X-Wolfy-Author'),
    sourceKey: header('X-Wolfy-Source') || id,
  }
}

// --- Книга по ссылке -------------------------------------------------------

/**
 * Получает EPUB, PDF или TXT через защищённый серверный загрузчик.
 *
 * Напрямую браузер почти всегда упрётся в CORS. Сервер дополнительно не даёт
 * ссылке обратиться к localhost/локальной сети и обрывает ответ после 64 МБ.
 */
export async function downloadBookURL(address: string): Promise<{
  bytes: ArrayBuffer
  fileName: string
  contentType: string
}> {
  let response: Response
  try {
    response = await fetch('/v1/library/fetch', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: address.trim() }),
    })
  } catch {
    throw new OfflineError()
  }

  if (!response.ok) {
    const payload = safeParse(await response.text()) as { error?: string; message?: string } | null
    throw new ApiError(
      response.status,
      payload?.message || payload?.error || `Сервер ответил ${response.status}`,
    )
  }

  const disposition = response.headers.get('content-disposition') ?? ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  let fileName = 'book'
  if (encoded) {
    try {
      fileName = decodeURIComponent(encoded)
    } catch {
      // Заголовок формирует наш сервер, но повреждённый reverse proxy не
      // должен ломать уже скачанную книгу: расширение проверит addFile.
    }
  }

  return {
    bytes: await response.arrayBuffer(),
    fileName,
    contentType: response.headers.get('content-type') ?? 'application/octet-stream',
  }
}
