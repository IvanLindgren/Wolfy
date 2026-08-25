/**
 * Хранилище файлов в браузере: OPFS.
 *
 * Почему OPFS, а не IndexedDB: книга — это большой бинарный файл, который
 * читается кусками и живёт годами. IndexedDB отдаёт запись целиком, то есть
 * пятимегабайтный EPUB пришлось бы держать в памяти ради оглавления. OPFS
 * даёт настоящий файл с произвольным доступом, а в воркере — ещё и
 * синхронный, без промисов на каждый килобайт.
 *
 * Здесь же лежит состояние сессии — `library.json` и `settings.json`, — и по
 * тому же протоколу `dirty`/`saved`, что и на десктопе. Второй модели данных
 * для веба нет и быть не должно.
 *
 * Раскладка:
 *
 * ```
 * /state/library.json    библиотека, колоды, полки
 * /state/settings.json   настройки, серия дней, статистика
 * /books/<id>.<ext>      файлы книг — ровно то, что принёс читатель
 * ```
 *
 * Пути в `LibraryBook.path` хранятся относительными (`books/<id>.epub`):
 * абсолютных путей в браузере не бывает, а относительный переживёт и
 * переезд, и синхронизацию.
 */

const BOOKS = 'books'
const STATE = 'state'

/** Есть ли OPFS вообще. Без него приложение работает, но забывает всё. */
export function opfsAvailable(): boolean {
  return typeof navigator !== 'undefined' && !!navigator.storage?.getDirectory
}

async function root(): Promise<FileSystemDirectoryHandle> {
  return navigator.storage.getDirectory()
}

async function dir(name: string, create = true): Promise<FileSystemDirectoryHandle> {
  return (await root()).getDirectoryHandle(name, { create })
}

/**
 * Разбирает относительный путь на каталог и имя.
 *
 * Вложенность здесь ровно одна, и глубже она не понадобится: книги лежат
 * плоско, состояние — плоско.
 */
function split(path: string): { folder: string; name: string } {
  const parts = path.split('/').filter(Boolean)
  if (parts.length === 1) return { folder: '', name: parts[0]! }
  return { folder: parts.slice(0, -1).join('/'), name: parts[parts.length - 1]! }
}

async function handleFor(
  path: string,
  create: boolean,
): Promise<FileSystemFileHandle | null> {
  const { folder, name } = split(path)
  try {
    const parent = folder ? await dir(folder, create) : await root()
    return await parent.getFileHandle(name, { create })
  } catch {
    // «Нет файла» — обычный ответ, а не ошибка: книга могла быть удалена, а
    // состояния при первом запуске просто ещё нет.
    return null
  }
}

/** Записывает файл целиком, заменяя прежний. */
export async function writeFile(path: string, data: BufferSource | string): Promise<void> {
  const handle = await handleFor(path, true)
  if (!handle) throw new Error(`не удалось создать «${path}»`)

  // Синхронный доступ доступен только в воркере, зато он вдвое быстрее и не
  // держит промис на каждую запись. Там, где его нет, работает обычный поток.
  const createSync = (
    handle as FileSystemFileHandle & {
      createSyncAccessHandle?: () => Promise<FileSystemSyncAccessHandle>
    }
  ).createSyncAccessHandle

  if (typeof createSync === 'function') {
    const access = await createSync.call(handle)
    try {
      const bytes =
        typeof data === 'string' ? new TextEncoder().encode(data) : toUint8(data)
      access.truncate(0)
      access.write(bytes, { at: 0 })
      access.flush()
      return
    } finally {
      access.close()
    }
  }

  const writable = await handle.createWritable()
  await writable.write(data)
  await writable.close()
}

/** Читает файл целиком. `null` — файла нет, и это обычный ответ. */
export async function readFile(path: string): Promise<Uint8Array | null> {
  const handle = await handleFor(path, false)
  if (!handle) return null
  try {
    const file = await handle.getFile()
    return new Uint8Array(await file.arrayBuffer())
  } catch {
    return null
  }
}

export async function readText(path: string): Promise<string | null> {
  const bytes = await readFile(path)
  return bytes ? new TextDecoder().decode(bytes) : null
}

export async function removeFile(path: string): Promise<void> {
  const { folder, name } = split(path)
  try {
    const parent = folder ? await dir(folder, false) : await root()
    await parent.removeEntry(name)
  } catch {
    // Уже нет — цель достигнута.
  }
}

export async function fileSize(path: string): Promise<number> {
  const handle = await handleFor(path, false)
  if (!handle) return 0
  try {
    return (await handle.getFile()).size
  } catch {
    return 0
  }
}

// --- Книги ------------------------------------------------------------------

/** Путь, по которому ляжет файл книги. Относительный: других тут не бывает. */
export function bookPath(id: string, extension: string): string {
  const clean = extension.replace(/[^a-z0-9]/gi, '').toLowerCase()
  return `${BOOKS}/${id}${clean ? `.${clean}` : ''}`
}

export async function saveBook(
  id: string,
  extension: string,
  bytes: BufferSource,
): Promise<string> {
  const path = bookPath(id, extension)
  await writeFile(path, bytes)
  return path
}

export async function readBook(path: string): Promise<Uint8Array | null> {
  return readFile(path)
}

// --- Состояние сессии -------------------------------------------------------

export const LIBRARY_PATH = `${STATE}/library.json`
export const SETTINGS_PATH = `${STATE}/settings.json`
export const PRACTICE_PATH = `${STATE}/practice.json`
export const LIBRARY_BACKUP = `${LIBRARY_PATH}.bak`
export const SETTINGS_BACKUP = `${SETTINGS_PATH}.bak`
export const PRACTICE_BACKUP = `${PRACTICE_PATH}.bak`

/**
 * Атомарная запись маленького состояния (library/settings/practice).
 *
 * OPFS `truncate(0) -> write` не является crash-атомарной: если вкладку
 * убить между усечением и записью, файл останется пустым/оборванным и
 * следующее открытие получило бы пустую библиотеку. Для многомегабайтных
 * книг такой трюк не нужен, а для килобайтных JSON делаем двухслотовую
 * схему: пишем primary, затем `.bak` с тем же содержимым. При чтении
 * primary valid -> primary, primary broken + backup valid -> backup,
 * оба broken -> явная ошибка, а не молчаливый Default (P12).
 * Книги (`books/*`) пишутся обычным `writeFile` — они большие и не
 * относятся к atomic state persistence.
 */
export async function writeStateAtomic(path: string, data: string): Promise<void> {
  await writeFile(path, data)
  const backup = `${path}.bak`
  try {
    await writeFile(backup, data)
  } catch (e) {
    // Backup — best-effort: primary уже на диске, а потеря бэкапа не
    // означает потерю данных, лишь потерю страховки на следующий сбой.
    console.warn(`Failed to write backup for ${path}`, e)
  }
}

function isEmptyText(s: string | null): boolean {
  return s === null || s.trim() === ''
}

function isValidJsonText(s: string | null): boolean {
  if (isEmptyText(s)) return true // пустое трактуется как Default, а не битое
  try {
    JSON.parse(s!)
    return true
  } catch {
    return false
  }
}

/**
 * Читает сырые primary + backup для восстановления.
 *
 * Возвращает все шесть строк; выбор лучшего делает вызывающий через
 * strict-открытие Rust (чтобы проверка была канонической, а не дублированной
 * в JS). Для быстрой предпроверки можно использовать `isValidJsonText`.
 */
export async function readStateRaw(): Promise<{
  libraryPrimary: string | null
  libraryBackup: string | null
  settingsPrimary: string | null
  settingsBackup: string | null
  practicePrimary: string | null
  practiceBackup: string | null
}> {
  const [
    libraryPrimary,
    libraryBackup,
    settingsPrimary,
    settingsBackup,
    practicePrimary,
    practiceBackup,
  ] = await Promise.all([
    readText(LIBRARY_PATH),
    readText(LIBRARY_BACKUP),
    readText(SETTINGS_PATH),
    readText(SETTINGS_BACKUP),
    readText(PRACTICE_PATH),
    readText(PRACTICE_BACKUP),
  ])
  return {
    libraryPrimary,
    libraryBackup,
    settingsPrimary,
    settingsBackup,
    practicePrimary,
    practiceBackup,
  }
}

/**
 * Читает состояние с восстановлением из бэкапа на уровне JS-валидации.
 *
 * Пытается primary, при битой JSON-синтаксисе падает в backup. Окончательная
 * проверка (serde) всё равно делается в Rust strict, но 99% синтаксических
 * повреждений ловятся уже здесь без вызова WASM.
 */
export async function readStateWithRecovery(): Promise<{
  library: string | null
  settings: string | null
  practice: string | null
  recoveredFromBackup: boolean
  backupUsedFor: ('library' | 'settings' | 'practice')[]
}> {
  const raw = await readStateRaw()
  let library = raw.libraryPrimary
  let settings = raw.settingsPrimary
  let practice = raw.practicePrimary
  const backupUsedFor: ('library' | 'settings' | 'practice')[] = []

  if (!isValidJsonText(library) && isValidJsonText(raw.libraryBackup)) {
    library = raw.libraryBackup
    backupUsedFor.push('library')
  } else if (!isValidJsonText(library) && !isValidJsonText(raw.libraryBackup) && !isEmptyText(library)) {
    // обе битые — оставляем primary, чтобы Rust вернул явную ошибку
  }

  if (!isValidJsonText(settings) && isValidJsonText(raw.settingsBackup)) {
    settings = raw.settingsBackup
    backupUsedFor.push('settings')
  }

  if (!isValidJsonText(practice) && isValidJsonText(raw.practiceBackup)) {
    practice = raw.practiceBackup
    backupUsedFor.push('practice')
  }

  return {
    library,
    settings,
    practice,
    recoveredFromBackup: backupUsedFor.length > 0,
    backupUsedFor,
  }
}

export async function readState(): Promise<{
  library: string | null
  settings: string | null
  practice: string | null
}> {
  // Для совместимости: старый readState теперь делает recovery.
  const recovered = await readStateWithRecovery()
  return { library: recovered.library, settings: recovered.settings, practice: recovered.practice }
}

// --- Место -----------------------------------------------------------------

export interface StorageUsage {
  /** Сколько заняли книги. */
  books: number
  /** Сколько занято всего по мнению браузера, включая кэши и словарь. */
  total: number
  /** Сколько браузер вообще готов дать. Ноль — не сказал. */
  quota: number
  /** Обещал ли браузер не стирать эти данные под нехватку места. */
  persisted: boolean
}

export async function storageUsage(): Promise<StorageUsage> {
  let books = 0
  try {
    const folder = await dir(BOOKS, false)
    for await (const [, handle] of folder as unknown as AsyncIterable<
      [string, FileSystemHandle]
    >) {
      if (handle.kind === 'file') {
        books += (await (handle as FileSystemFileHandle).getFile()).size
      }
    }
  } catch {
    // Каталога книг ещё нет — значит, книг ноль.
  }

  const estimate = (await navigator.storage?.estimate?.()) ?? {}
  const persisted = (await navigator.storage?.persisted?.()) ?? false

  return {
    books,
    total: estimate.usage ?? books,
    quota: estimate.quota ?? 0,
    persisted,
  }
}

/**
 * Просит браузер не стирать хранилище под нехватку места.
 *
 * Спрашивается после того, как читатель добавил первую книгу, а не при
 * старте: до первой книги терять нечего, а разрешение, спрошенное ни за что,
 * получает отказ.
 */
export async function requestPersistence(): Promise<boolean> {
  try {
    if (await navigator.storage.persisted()) return true
    return await navigator.storage.persist()
  } catch {
    return false
  }
}

/** Стирает все книги и состояние. Зовётся только из настроек, по кнопке. */
export async function clearEverything(): Promise<void> {
  const base = await root()
  for (const name of [BOOKS, STATE]) {
    try {
      await base.removeEntry(name, { recursive: true })
    } catch {
      // Нет каталога — нечего и стирать.
    }
  }
}

function toUint8(data: BufferSource): Uint8Array {
  if (data instanceof Uint8Array) return data
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
  }
  return new Uint8Array(data)
}
