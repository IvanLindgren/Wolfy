/**
 * Добавление книги: файл → библиотека.
 *
 * Книга остаётся на устройстве. Она не загружается на сервер ни целиком, ни
 * кусками: сервер знает, что вы читаете «Гэтсби» и на какой вы главе, но
 * самого файла у него нет и не будет. Книга пользователя — его файл.
 *
 * Заводить ли книгу заново, решает **ядро** по отпечатку содержимого, и
 * решает до записи: книга, приехавшая синхронизацией с другого устройства,
 * получает файл, а не заводится второй раз.
 */

import * as bridge from '../core/bridge'
import { downloadBookURL } from '../api/client'
import { newId, now } from '../core/clock'
import { session, useSession } from '../core/session'
import type { LibraryBook } from '../core/types'
import { requestPersistence } from '../storage/opfs'
import { extractPdfPages } from './pdf'

/** Что умеет читать ядро. Остальное отвергается с внятным объяснением. */
export const ACCEPTED = '.epub,.txt,.pdf,application/epub+zip,application/pdf,text/plain'

/**
 * Лимиты — те же, что в Rust `parser/limits.rs`.
 *
 * Ядро всё равно откажется разбирать слишком большой файл, но к тому времени
 * гигабайт уже будет прочитан в память вкладки: `arrayBuffer()` аллоцирует
 * целиком, и на телефоне вкладка падает раньше, чем ядро успевает ответить.
 * Поэтому размер проверяется дважды: по `file.size` — до чтения, и по
 * фактической длине буфера — после, на случай, если `size` соврал.
 */
const MAX_SOURCE_BYTES = 80 * 1024 * 1024
const MAX_TXT_BYTES = 10 * 1024 * 1024
const TOO_LARGE_MSG = 'Книга слишком велика для безопасной обработки'

function checkSourceSize(size: number, detail?: string): string | null {
  if (size > MAX_SOURCE_BYTES) {
    return `${TOO_LARGE_MSG}: ${detail ?? `${size} байт`} превышает ${MAX_SOURCE_BYTES} байт`
  }
  return null
}

function checkTxtSize(size: number): string | null {
  if (size > MAX_TXT_BYTES) {
    return `${TOO_LARGE_MSG}: TXT ${size} байт превышает ${MAX_TXT_BYTES} байт`
  }
  return checkSourceSize(size, `TXT ${size} байт`)
}

/** Размер файла, если браузер его сообщил. Ноль — «не знаю», а не «пусто». */
function sizeOf(file: File): number {
  return (file as unknown as { size?: number }).size ?? 0
}

export type ImportResult =
  | { kind: 'added'; book: LibraryBook }
  /** Такая книга уже есть — открываем её, а не заводим вторую. */
  | { kind: 'known'; book: LibraryBook }
  | { kind: 'refused'; message: string }

/**
 * SHA-256 в нижнем регистре.
 *
 * Тот же алгоритм и та же запись, что у клиента на Kotlin: один и тот же
 * файл, добавленный на телефоне и в браузере, обязан узнаться как одна книга,
 * а не превратиться в две с одинаковым названием.
 */
export async function fingerprintOf(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function extensionOf(name: string): string {
  return name.split('.').pop()?.toLowerCase() ?? ''
}

function titleOf(name: string): string {
  return name.replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').trim() || 'Без названия'
}

/** Добавляет книгу из файла: перетаскиванием, диалогом или из системы. */
export async function addFile(file: File): Promise<ImportResult> {
  const extension = extensionOf(file.name)
  if (!['epub', 'txt', 'pdf'].includes(extension)) {
    return {
      kind: 'refused',
      message: `Формат «${extension || 'без расширения'}» пока не поддерживается. Wolfy читает EPUB, TXT и PDF.`,
    }
  }
  // Проверяем `file.size` до `arrayBuffer()`: не аллоцируем гигабайт впустую.
  const size = sizeOf(file)
  if (size > 0) {
    const refusal = extension === 'txt' ? checkTxtSize(size) : checkSourceSize(size)
    if (refusal) return { kind: 'refused', message: refusal }
  }

  try {
    return extension === 'pdf' ? await addPdf(file) : await addPlain(file, extension)
  } catch (error) {
    return {
      kind: 'refused',
      message: error instanceof Error ? error.message : 'Книгу не удалось открыть.',
    }
  }
}

/** Добавляет книгу по публичной HTTPS-ссылке через защищённый прокси API. */
export async function addURL(address: string): Promise<ImportResult> {
  const clean = address.trim()
  let parsed: URL
  try {
    parsed = new URL(clean)
  } catch {
    return { kind: 'refused', message: 'Введите полную HTTPS-ссылку на файл книги.' }
  }
  if (parsed.protocol !== 'https:') {
    return { kind: 'refused', message: 'Для загрузки книги нужна HTTPS-ссылка.' }
  }

  try {
    const downloaded = await downloadBookURL(clean)
    return await addFile(
      new File([downloaded.bytes], downloaded.fileName, { type: downloaded.contentType }),
    )
  } catch (error) {
    return {
      kind: 'refused',
      message: error instanceof Error ? error.message : 'Книгу по ссылке не удалось скачать.',
    }
  }
}

async function addPlain(file: File, extension: string): Promise<ImportResult> {
  // Повторно — на случай прямого вызова в обход `addFile`.
  const declared = sizeOf(file)
  if (declared > 0) {
    const refusal = extension === 'txt' ? checkTxtSize(declared) : checkSourceSize(declared)
    if (refusal) throw new Error(refusal)
  }
  const bytes = await file.arrayBuffer()
  // Буфер мог оказаться больше объявленного размера.
  const actual =
    extension === 'txt'
      ? checkTxtSize(bytes.byteLength)
      : checkSourceSize(bytes.byteLength)
  if (actual) throw new Error(actual)
  // Отпечаток считается здесь, до передачи в воркер: буфер уходит `transfer`
  // и после этого на главном потоке пуст.
  const fingerprint = await fingerprintOf(bytes)

  const plan = await session.planAdd(fingerprint)
  if (plan.plan === 'known' && plan.bookId) {
    const known = session.book(plan.bookId)
    if (known) return { kind: 'known', book: known }
  }

  let id =
    (plan.plan === 'attach' || plan.plan === 'revive') && plan.bookId ? plan.bookId : newId()
  const opened = await bridge.importBook(id, file.name, bytes)

  if (plan.plan === 'attach' && plan.bookId) {
    await session.attachFile(plan.bookId, opened.path, fingerprint)
  } else if (plan.plan === 'revive' && plan.bookId) {
    await session.reviveBook(plan.bookId, opened.path, fingerprint)
  } else {
    // §5: ядро заменяет id на канонический (из source_key) — узнаём настоящий
    // номер из ответа, а не из случайного, иначе describe и поиск книги
    // разойдутся с тем, что лежит в библиотеке.
    const added = await session.addBook({
      id,
      path: opened.path,
      title: opened.title || titleOf(file.name),
      author: opened.author,
      format: extension,
      sourceKey: fingerprint,
      addedAt: now(),
      chapters: opened.chapters.length,
      progress: { chapter: 0, withinChapter: 0, openedAt: 0 },
      shelf: null,
    })
    // Ядро могло заменить номер на канонический — переносим уже разобранную
    // книгу под него, иначе она останется висеть в памяти воркера под старым.
    if (added.book && added.book.id !== id) {
      await bridge.rekeyBook(id, added.book.id)
      id = added.book.id
    }
  }

  await session.describe(
    id,
    opened.title || titleOf(file.name),
    opened.author,
    opened.chapters.length,
  )
  await afterFirstBook()

  const book = session.book(id)
  return book ? { kind: 'added', book } : { kind: 'refused', message: 'Книга не завелась.' }
}

/**
 * PDF: текст извлекается в браузере, а не в ядре.
 *
 * `pdf-extract` не собирается под `wasm32-unknown-unknown`, а `pdf.js` умеет
 * то же самое и уже есть в браузере. Постраничное деление при этом
 * сохраняется: одна физическая страница — одна единица навигации, и страницы
 * без текста сохраняются тоже, иначе номера, оглавление и прогресс уедут
 * после первой же иллюстрации.
 */
async function addPdf(file: File): Promise<ImportResult> {
  const declared = sizeOf(file)
  if (declared > 0) {
    const refusal = checkSourceSize(declared)
    if (refusal) throw new Error(refusal)
  }
  const bytes = await file.arrayBuffer()
  const actual = checkSourceSize(bytes.byteLength)
  if (actual) throw new Error(actual)
  const fingerprint = await fingerprintOf(bytes)

  const plan = await session.planAdd(fingerprint)
  if (plan.plan === 'known' && plan.bookId) {
    const known = session.book(plan.bookId)
    if (known) return { kind: 'known', book: known }
  }

  const pages = await extractPdfPages(bytes)
  if (pages.every((page) => !page.trim())) {
    return {
      kind: 'refused',
      message:
        'В этом PDF нет текстового слоя — похоже, это скан. Распознайте страницы через «Страница по фото».',
    }
  }

  let id =
    (plan.plan === 'attach' || plan.plan === 'revive') && plan.bookId ? plan.bookId : newId()
  const title = titleOf(file.name)
  const opened = await bridge.importPages(id, title, pages)

  if (plan.plan === 'attach' && plan.bookId) {
    await session.attachFile(plan.bookId, opened.path, fingerprint)
  } else if (plan.plan === 'revive' && plan.bookId) {
    await session.reviveBook(plan.bookId, opened.path, fingerprint)
  } else {
    const added = await session.addBook({
      id,
      path: opened.path,
      title,
      author: null,
      // Формат остаётся `pdf`: читателю важно, чем книга была, а хранится
      // она уже текстом со страничными разделителями.
      format: 'pdf',
      sourceKey: fingerprint,
      addedAt: now(),
      chapters: opened.chapters.length,
      progress: { chapter: 0, withinChapter: 0, openedAt: 0 },
      shelf: null,
    })
    // Ядро могло заменить номер на канонический — переносим уже разобранную
    // книгу под него, иначе она останется висеть в памяти воркера под старым.
    if (added.book && added.book.id !== id) {
      await bridge.rekeyBook(id, added.book.id)
      id = added.book.id
    }
  }

  await session.describe(id, title, null, opened.chapters.length)
  await afterFirstBook()

  const book = session.book(id)
  return book ? { kind: 'added', book } : { kind: 'refused', message: 'Книга не завелась.' }
}

/** Добавляет EPUB, скачанный из ленты через прокси сервера. */
export async function addDownloaded(
  bytes: ArrayBuffer,
  fileName: string,
  title: string,
  author: string,
  sourceKey: string,
): Promise<ImportResult> {
  const refusal = checkSourceSize(bytes.byteLength)
  if (refusal) return { kind: 'refused', message: refusal }
  const plan = await session.planAdd(sourceKey)
  if (plan.bookId) {
    const known = session.book(plan.bookId)
    if (known && known.path) return { kind: 'known', book: known }
  }

  let id = plan.bookId ?? newId()
  const opened = await bridge.importBook(id, fileName, bytes)

  if (plan.plan === 'attach' && plan.bookId) {
    await session.attachFile(plan.bookId, opened.path, sourceKey)
  } else if (plan.plan === 'revive' && plan.bookId) {
    await session.reviveBook(plan.bookId, opened.path, sourceKey)
  } else {
    const added = await session.addBook({
      id,
      path: opened.path,
      title: title || opened.title || titleOf(fileName),
      author: author || opened.author,
      format: 'epub',
      sourceKey,
      addedAt: now(),
      chapters: opened.chapters.length,
      progress: { chapter: 0, withinChapter: 0, openedAt: 0 },
      shelf: null,
    })
    // Ядро могло заменить номер на канонический — переносим уже разобранную
    // книгу под него, иначе она останется висеть в памяти воркера под старым.
    if (added.book && added.book.id !== id) {
      await bridge.rekeyBook(id, added.book.id)
      id = added.book.id
    }
  }

  await session.describe(
    id,
    title || opened.title || titleOf(fileName),
    author || opened.author,
    opened.chapters.length,
  )
  await afterFirstBook()

  const book = session.book(id)
  return book ? { kind: 'added', book } : { kind: 'refused', message: 'Книга не завелась.' }
}

/**
 * Дописывает распознанную страницу в книгу снимков.
 *
 * Как склеивать страницы, знает ядро: между ними обязана быть пустая строка,
 * иначе последняя фраза страницы слипнется с первой фразой следующей — и
 * уедет вместе с ней в контекст перевода.
 */
export const SNAPSHOTS = 'Снимки страниц'

export async function appendSnapshot(text: string): Promise<ImportResult> {
  const existing = session.books().find((book) => book.title === SNAPSHOTS)
  const before = existing?.path ? await bridge.bookText(existing.path) : ''
  const whole = await session.appendedPage(before, text)

  const id = existing?.id ?? newId()
  const pages = whole.split('\f').map((page) => page.trim())
  const opened = await bridge.importPages(id, SNAPSHOTS, pages)

  if (existing) {
    await session.attachFile(id, opened.path, '')
  } else {
    await session.addBook({
      id,
      path: opened.path,
      title: SNAPSHOTS,
      author: null,
      format: 'txt',
      sourceKey: '',
      addedAt: now(),
      chapters: opened.chapters.length,
      progress: { chapter: 0, withinChapter: 0, openedAt: 0 },
      shelf: null,
    })
  }

  await session.describe(id, SNAPSHOTS, null, opened.chapters.length)
  const book = session.book(id)
  return book ? { kind: 'added', book } : { kind: 'refused', message: 'Страница не сохранилась.' }
}

/**
 * После первой книги просим браузер не стирать хранилище.
 *
 * После, а не при старте: до первой книги терять нечего, а разрешение,
 * спрошенное ни за что, получает отказ — и второй раз его уже не спросишь.
 */
async function afterFirstBook(): Promise<void> {
  if (useSession.getState().library.books.filter((book) => !book.deleted).length <= 1) {
    await requestPersistence()
  }
}
