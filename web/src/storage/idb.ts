/**
 * Кэш переводов в IndexedDB.
 *
 * Почему не OPFS: переводов тысячи, каждый — сотня байт, и искать их нужно по
 * ключу. Файл на запись — не тот инструмент; хранилище с индексом — тот.
 *
 * Почему не `localStorage`: он синхронный и блокирует главный поток на каждой
 * записи, а записи здесь идут во время чтения — то есть ровно тогда, когда
 * главный поток занят отрисовкой абзаца.
 *
 * Ключ — пара «предложение + слово», потому что перевод контекстный: «bank» в
 * двух разных предложениях это два разных перевода, и общий ключ по слову
 * показал бы читателю берег вместо банка.
 */

const DB_NAME = 'wolfy'
const DB_VERSION = 1
const TRANSLATIONS = 'translations'
const DEFINITIONS = 'definitions'

export interface CachedTranslation {
  key: string
  text: string
  /** Когда положили: старые записи чистятся, чтобы кэш не рос вечно. */
  at: number
}

let opening: Promise<IDBDatabase | null> | null = null

function open(): Promise<IDBDatabase | null> {
  if (opening) return opening
  opening = new Promise((resolve) => {
    if (typeof indexedDB === 'undefined') {
      resolve(null)
      return
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(TRANSLATIONS)) {
        db.createObjectStore(TRANSLATIONS, { keyPath: 'key' })
      }
      if (!db.objectStoreNames.contains(DEFINITIONS)) {
        db.createObjectStore(DEFINITIONS, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    // Приватный режим и запрет на хранилище — не ошибка приложения: перевод
    // просто перестаёт кэшироваться, а сеть работает как работала.
    request.onerror = () => resolve(null)
    request.onblocked = () => resolve(null)
  })
  return opening
}

function run<T>(
  store: string,
  mode: IDBTransactionMode,
  body: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T | null> {
  return open().then(
    (db) =>
      new Promise<T | null>((resolve) => {
        if (!db) {
          resolve(null)
          return
        }
        try {
          const tx = db.transaction(store, mode)
          const request = body(tx.objectStore(store))
          request.onsuccess = () => resolve(request.result)
          request.onerror = () => resolve(null)
        } catch {
          resolve(null)
        }
      }),
  )
}

/**
 * Ключ перевода.
 *
 * Предложение нормализуется по пробелам: одна и та же фраза из EPUB и из PDF
 * отличается переносами строк, и без нормализации кэш промахивался бы на
 * каждой второй книге.
 */
export function translationKey(sentence: string, word: string): string {
  return `${sentence.replace(/\s+/g, ' ').trim()}\u0000${word.toLowerCase()}`
}

export async function cachedTranslation(key: string): Promise<string | null> {
  const found = await run<CachedTranslation>(TRANSLATIONS, 'readonly', (store) =>
    store.get(key),
  )
  return found?.text ?? null
}

export async function cacheTranslation(key: string, text: string): Promise<void> {
  await run(TRANSLATIONS, 'readwrite', (store) =>
    store.put({ key, text, at: Date.now() } satisfies CachedTranslation),
  )
}

export async function cachedDefinition<T>(key: string): Promise<T | null> {
  const found = await run<{ key: string; value: T }>(DEFINITIONS, 'readonly', (store) =>
    store.get(key),
  )
  return found?.value ?? null
}

export async function cacheDefinition<T>(key: string, value: T): Promise<void> {
  await run(DEFINITIONS, 'readwrite', (store) => store.put({ key, value }))
}

/** Стирает кэш. Зовётся из настроек вместе с очисткой места. */
export async function clearCaches(): Promise<void> {
  await run(TRANSLATIONS, 'readwrite', (store) => store.clear())
  await run(DEFINITIONS, 'readwrite', (store) => store.clear())
}
