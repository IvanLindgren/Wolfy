/**
 * Большие неизменяемые ресурсы: лексикон и офлайн-словарь.
 *
 * Оба лежат в Cache Storage, а не в OPFS и не в IndexedDB. Причина одна:
 * это ровно то, для чего Cache Storage придуман — один большой ответ,
 * который никогда не меняется и должен переживать перезапуск вкладки без
 * единого запроса в сеть.
 *
 * Скачивание идёт потоком с прогрессом и умеет отменяться. Тридцать мегабайт
 * без прогресса — это тридцать мегабайт, во время которых читатель думает,
 * что приложение зависло.
 */

const CACHE = 'wolfy-assets'

/** Лексикон: 1,8 МБ, нужен для разбора слова. */
export const LEXICON_URL = '/lexicon/english_lexicon.tsv'

/** Офлайн-словарь: русский эквивалент, толкование и МФА. Скачивается по согласию. */
export const DICTIONARY_URL = '/v1/dictionary'

export interface DownloadProgress {
  /** Сколько байт уже пришло. */
  loaded: number
  /** Сколько всего. Ноль — сервер не сказал `Content-Length`. */
  total: number
}

async function cache(): Promise<Cache | null> {
  if (typeof caches === 'undefined') return null
  try {
    return await caches.open(CACHE)
  } catch {
    return null
  }
}

/** Лежит ли ресурс в кэше. По нему настройки показывают «словарь установлен». */
export async function isCached(url: string): Promise<boolean> {
  const store = await cache()
  if (!store) return false
  return !!(await store.match(url))
}

/** Размер закэшированного ресурса в байтах. Ноль — ресурса нет. */
export async function cachedSize(url: string): Promise<number> {
  const store = await cache()
  const response = await store?.match(url)
  if (!response) return 0
  const length = response.headers.get('content-length')
  if (length) return Number(length)
  return (await response.clone().arrayBuffer()).byteLength
}

export async function forget(url: string): Promise<void> {
  const store = await cache()
  await store?.delete(url)
}

/**
 * Достаёт ресурс: из кэша, а если его там нет — из сети, попутно докладывая
 * о прогрессе и складывая результат в кэш.
 *
 * Отмена обязательна и работает через обычный `AbortSignal`: читатель,
 * передумавший качать словарь на мобильном интернете, должен иметь такую
 * возможность, а не ждать конца.
 */
export async function fetchCached(
  url: string,
  options: {
    signal?: AbortSignal
    onProgress?: (progress: DownloadProgress) => void
  } = {},
): Promise<Uint8Array> {
  const store = await cache()
  const hit = await store?.match(url)
  if (hit) {
    return new Uint8Array(await hit.arrayBuffer())
  }

  const response = await fetch(url, { signal: options.signal })
  if (!response.ok) {
    throw new Error(`${url}: сервер ответил ${response.status}`)
  }

  const total = Number(response.headers.get('content-length') ?? 0)
  const chunks: Uint8Array[] = []
  let loaded = 0

  if (response.body && options.onProgress) {
    const reader = response.body.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) {
        chunks.push(value)
        loaded += value.byteLength
        options.onProgress({ loaded, total })
      }
    }
  } else {
    const buffer = new Uint8Array(await response.arrayBuffer())
    chunks.push(buffer)
    loaded = buffer.byteLength
    options.onProgress?.({ loaded, total: total || loaded })
  }

  const bytes = concat(chunks, loaded)

  // В кэш кладём только целиком скачанное: половина словаря, объявленная
  // готовой, хуже, чем его отсутствие — она молча отвечает «слова нет».
  try {
    await store?.put(
      url,
      new Response(bytes as BlobPart, {
        headers: {
          'content-length': String(bytes.byteLength),
          'cache-control': 'immutable',
        },
      }),
    )
  } catch {
    // Место кончилось — приложение всё равно работает, просто в следующий
    // раз ресурс скачается заново.
  }

  return bytes
}

function concat(chunks: Uint8Array[], size: number): Uint8Array {
  if (chunks.length === 1) return chunks[0]!
  const whole = new Uint8Array(size)
  let at = 0
  for (const chunk of chunks) {
    whole.set(chunk, at)
    at += chunk.byteLength
  }
  return whole
}

/**
 * Распаковывает gzip средствами браузера.
 *
 * Словарь приезжает архивом: тридцать мегабайт против семи. Своего распаковщика
 * не заводим — `DecompressionStream` есть во всех целевых браузерах и работает
 * потоком, не собирая архив и результат в памяти одновременно.
 */
export async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('браузер не умеет распаковывать gzip')
  }
  const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

/** Сколько занимают все закэшированные ресурсы. */
export async function assetsSize(): Promise<number> {
  const store = await cache()
  if (!store) return 0
  let size = 0
  for (const request of await store.keys()) {
    size += await cachedSize(request.url)
  }
  return size
}

export async function clearAssets(): Promise<void> {
  if (typeof caches === 'undefined') return
  try {
    await caches.delete(CACHE)
  } catch {
    // Нечего стирать.
  }
}
