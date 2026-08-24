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

/** Лимиты скачивания: защита от decomposition bomb и случайного гиганта. */
const MAX_COMPRESSED_BYTES = 40 * 1024 * 1024
const MAX_DECOMPRESSED_BYTES = 80 * 1024 * 1024

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
 *
 * §28: стримим через TransformStream без собственного массива чанков,
 * считаем байты одним TransformStream и не делаем второй полный concat.
 * Сохраняем прогресс и добавляем лимиты на сжатый и распакованный размер.
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
    const buf = new Uint8Array(await hit.arrayBuffer())
    if (buf.byteLength > MAX_COMPRESSED_BYTES) {
      throw new Error(`${url}: закэшированный ресурс слишком велик`)
    }
    return buf
  }

  const response = await fetch(url, { signal: options.signal })
  if (!response.ok) {
    throw new Error(`${url}: сервер ответил ${response.status}`)
  }

  const total = Number(response.headers.get('content-length') ?? 0)
  if (total > MAX_COMPRESSED_BYTES) {
    throw new Error(`${url}: Content-Length ${total} превышает лимит ${MAX_COMPRESSED_BYTES}`)
  }

  let bytes: Uint8Array

  // Пытаемся идти без собственного массива чанков: считающий TransformStream + Response.arrayBuffer()
  if (response.body && typeof TransformStream !== 'undefined') {
    let loaded = 0
    const onProgress = options.onProgress
    const counting = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        loaded += chunk.byteLength
        if (loaded > MAX_COMPRESSED_BYTES) {
          controller.error(new Error(`сжатый ресурс превысил лимит ${MAX_COMPRESSED_BYTES} байт`))
          return
        }
        controller.enqueue(chunk)
        if (onProgress) onProgress({ loaded, total })
      },
    })
    try {
      // pipeThrough сохраняет сигнал отмены через fetch (abort закрывает body)
      const counted = response.body.pipeThrough(counting)
      bytes = new Uint8Array(await new Response(counted).arrayBuffer())
      if (!onProgress) {
        // если прогресс не просили, но лимит уже проверили в потоке
      }
      if (bytes.byteLength > MAX_COMPRESSED_BYTES) {
        throw new Error(`сжатый ресурс превысил лимит`)
      }
      // финальный прогресс если total неизвестен
      if (onProgress && total === 0) onProgress({ loaded: bytes.byteLength, total: bytes.byteLength })
    } catch (e) {
      // Fallback для окружений без pipeThrough (jsdom) — старый путь с чанками
      if (e instanceof Error && /превысил лимит/.test(e.message)) throw e
      // если TransformStream не сработал, пробуем классику
      bytes = await readWithChunks(response, options.onProgress, total)
    }
  } else if (response.body && options.onProgress) {
    bytes = await readWithChunks(response, options.onProgress, total)
  } else {
    const buffer = new Uint8Array(await response.arrayBuffer())
    if (buffer.byteLength > MAX_COMPRESSED_BYTES) {
      throw new Error(`сжатый ресурс превысил лимит ${MAX_COMPRESSED_BYTES}`)
    }
    bytes = buffer
    options.onProgress?.({ loaded: buffer.byteLength, total: total || buffer.byteLength })
  }

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

async function readWithChunks(
  response: Response,
  onProgress: ((p: DownloadProgress) => void) | undefined,
  total: number,
): Promise<Uint8Array> {
  const chunks: Uint8Array[] = []
  let loaded = 0
  const reader = response.body!.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    if (value) {
      if (loaded + value.byteLength > MAX_COMPRESSED_BYTES) {
        throw new Error(`сжатый ресурс превысил лимит ${MAX_COMPRESSED_BYTES} байт`)
      }
      chunks.push(value)
      loaded += value.byteLength
      onProgress?.({ loaded, total })
    }
  }
  if (chunks.length === 1) return chunks[0]!
  const whole = new Uint8Array(loaded)
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
 *
 * §28: считаем и распакованный выход, обрываем при превышении лимита.
 */
export async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('браузер не умеет распаковывать gzip')
  }
  if (bytes.byteLength > MAX_COMPRESSED_BYTES) {
    throw new Error(`сжатый вход gunzip превысил лимит ${MAX_COMPRESSED_BYTES}`)
  }
  const decompressedStream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('gzip'))
  // Считаем распакованные байты потоком, чтобы не держать две копии
  if (typeof TransformStream !== 'undefined') {
    let loaded = 0
    const counter = new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        loaded += chunk.byteLength
        if (loaded > MAX_DECOMPRESSED_BYTES) {
          controller.error(new Error(`распакованный словарь превысил лимит ${MAX_DECOMPRESSED_BYTES} байт`))
          return
        }
        controller.enqueue(chunk)
      },
    })
    const counted = decompressedStream.pipeThrough(counter)
    const out = new Uint8Array(await new Response(counted).arrayBuffer())
    if (out.byteLength > MAX_DECOMPRESSED_BYTES) {
      throw new Error(`распакованный словарь превысил лимит`)
    }
    return out
  }
  // Фолбэк без TransformStream (jsdom)
  const out = new Uint8Array(await new Response(decompressedStream).arrayBuffer())
  if (out.byteLength > MAX_DECOMPRESSED_BYTES) {
    throw new Error(`распакованный словарь превысил лимит ${MAX_DECOMPRESSED_BYTES} байт`)
  }
  return out
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
