/// <reference lib="webworker" />
/**
 * Ядро в воркере.
 *
 * Разбор пятимегабайтного EPUB на главном потоке — это замерший интерфейс на
 * полторы секунды. Поэтому сессия, парсер и грамматика живут здесь целиком, а
 * интерфейс разговаривает с ними по RPC.
 *
 * Владелец состояния ровно один — сессия. Интерфейс никогда не правит
 * библиотеку, колоды и настройки сам: он посылает команду и перерисовывает
 * то, что ядро назвало изменившимся. Отсюда и запись на диск: она делается
 * здесь же, по флагам `dirty`, и главный поток о ней не знает.
 *
 * Файлы книг приходят сюда `ArrayBuffer`-ом как transferable — без копии.
 */

import * as Comlink from 'comlink'

import init, {
  WolfyBook,
  WolfySession,
  analyzeWord as wasmAnalyzeWord,
  explain as wasmExplain,
  grammarExercises as wasmGrammarExercises,
  grammarReference as wasmGrammarReference,
  installLexicon,
  lexiconReady,
  tokenizeText as wasmTokenize,
  version as wasmVersion,
} from '../src/core/pkg/wolfy_core.js'
import wasmUrl from '../src/core/pkg/wolfy_core_bg.wasm?url'

import type {
  AppSettings,
  Chapter,
  Command,
  DictionaryEntry,
  Exercises,
  Grammar,
  LibraryState,
  Outcome,
  Reference,
  TokenizedText,
  WordAnalysis,
} from '../src/core/types'
import {
  DICTIONARY_URL,
  LEXICON_URL,
  fetchCached,
  gunzip,
  isCached,
} from '../src/storage/assets'
import {
  LIBRARY_PATH,
  SETTINGS_PATH,
  bookPath,
  readBook,
  readStateRaw,
  removeFile,
  saveBook,
  writeStateAtomic,
} from '../src/storage/opfs'

/** Что известно сразу после запуска ядра. */
export interface Boot {
  version: string
  library: LibraryState
  settings: AppSettings
  /** Готов ли лексикон. Пока нет — разбор слова отвечает «не знаю». */
  lexicon: boolean
  /** Стоит ли офлайн-словарь. */
  dictionary: boolean
}

/** Книга, только что открытая: метаданные и оглавление. */
export interface OpenedBook {
  title: string | null
  author: string | null
  language: string | null
  cover: string | null
  chapters: { title: string | null }[]
  /** Относительный путь в хранилище. */
  path: string
}

let session: WolfySession | null = null
let ready: Promise<void> | null = null

/** Открытые книги: номер книги в библиотеке → разобранный файл. */
const opened = new Map<string, WolfyBook>()

/** Идущая загрузка словаря — чтобы её было чем остановить. */
let downloading: AbortController | null = null

async function ensure(): Promise<void> {
  if (!ready) {
    ready = (async () => {
      await init({ module_or_path: wasmUrl })
      const raw = await readStateRaw()

      // P12: corrupted JSON must not silently become empty library.
      // Try strict open with primary first, then fallback to backup combos.
      // At startup: primary valid -> primary, primary broken + backup valid -> backup,
      // оба broken -> explicit error/recovery UI, а не молчаливый Default.
      const candidates: Array<{ lib: string | null; set: string | null }> = [
        { lib: raw.libraryPrimary, set: raw.settingsPrimary },
        { lib: raw.libraryBackup, set: raw.settingsPrimary },
        { lib: raw.libraryPrimary, set: raw.settingsBackup },
        { lib: raw.libraryBackup, set: raw.settingsBackup },
      ]

      // Быстрый путь: если primary не битый по JSON-синтаксису, пробуем его сначала.
      // Остальные кандидаты пойдут при исключении из tryNew.
      let lastError: unknown = null
      let opened = false
      let recoveredFromBackup = false
      for (const c of candidates) {
        try {
          // WolfySession.tryNew отсутствует в старых сборках wasm — падаем в lenient.
          const tryNew = (WolfySession as unknown as {
            tryNew?: (lib?: string, set?: string) => WolfySession
          }).tryNew
          if (typeof tryNew === 'function') {
            session = tryNew.call(WolfySession, c.lib ?? undefined, c.set ?? undefined)
          } else {
            // Fallback для старой wasm без strict: пробуем lenient только если
            // primary не был битым по нашей JS-проверке (чтобы не маскировать битый).
            const isLibCorrupted =
              c.lib !== null && c.lib.trim() !== '' && !isValidJson(c.lib)
            const isSetCorrupted =
              c.set !== null && c.set.trim() !== '' && !isValidJson(c.set)
            if (isLibCorrupted || isSetCorrupted) continue
            session = new WolfySession(c.lib ?? undefined, c.set ?? undefined)
          }
          if (c.lib !== raw.libraryPrimary || c.set !== raw.settingsPrimary) {
            console.warn('Session recovered from backup slot', c)
            recoveredFromBackup = true
          }
          opened = true
          // Heal primary from backup while we have the good data in memory.
          // Следующий `persist` тоже залечит, но если пользователь не сделает
          // изменений, primary остался бы битым до следующего запуска.
          if (recoveredFromBackup && session) {
            try {
              const cur = session
              if (c.lib !== raw.libraryPrimary) {
                await writeStateAtomic(LIBRARY_PATH, cur.library())
              }
              if (c.set !== raw.settingsPrimary) {
                await writeStateAtomic(SETTINGS_PATH, cur.settings())
              }
            } catch (e) {
              console.warn('Failed to heal primary from backup', e)
            }
          }
          break
        } catch (e) {
          lastError = e
          continue
        }
      }

      if (!opened) {
        const hasAnyData = [
          raw.libraryPrimary,
          raw.libraryBackup,
          raw.settingsPrimary,
          raw.settingsBackup,
        ].some((v) => v !== null && v.trim() !== '')
        if (hasAnyData) {
          throw new Error(
            `Состояние библиотеки повреждено и не восстановлено из бэкапа: ${String(lastError)}`,
          )
        }
        // Совсем нет данных — первый запуск, открываем пустую сессию.
        const tryNew = (WolfySession as unknown as {
          tryNew?: (lib?: string, set?: string) => WolfySession
        }).tryNew
        if (typeof tryNew === 'function') {
          session = tryNew.call(WolfySession, undefined, undefined)
        } else {
          session = new WolfySession(undefined, undefined)
        }
      }
    })()
  }
  return ready
}

function isValidJson(s: string): boolean {
  try {
    JSON.parse(s)
    return true
  } catch {
    return false
  }
}

function current(): WolfySession {
  if (!session) throw new Error('сессия ядра ещё не открыта')
  return session
}

/**
 * Записывает то, что ядро назвало изменившимся — атомарно для маленького состояния.
 *
 * Именно то, а не всё: переписывать настройки при каждой прокрутке страницы и
 * библиотеку при каждой смене темы значит гонять диск впустую. Что изменилось,
 * знает только ядро — повторное сохранение слова, которое уже в колоде, не
 * меняет ничего.
 * Для library/settings/practice используется двухслотовая схема
 * (`writeStateAtomic` -> primary + `.bak`): `truncate(0)->write` на OPFS не
 * crash-атомарна и без неё битый файл молча стал бы пустой библиотекой (P12).
 * Книги (`books/*`) пишутся обычным `writeFile` — они большие.
 */
async function persist(outcome: Outcome): Promise<void> {
  if (!outcome.changed) return
  const core = current()
  const writes: Promise<unknown>[] = []
  if (outcome.libraryChanged) writes.push(writeStateAtomic(LIBRARY_PATH, core.library()))
  if (outcome.settingsChanged) writes.push(writeStateAtomic(SETTINGS_PATH, core.settings()))
  await Promise.all(writes)
  core.saved(outcome.libraryChanged, outcome.settingsChanged)
}

const api = {
  /** Поднимает ядро и отдаёт всё, что нужно первому экрану. */
  async boot(): Promise<Boot> {
    await ensure()
    const core = current()
    return {
      version: wasmVersion(),
      library: JSON.parse(core.library()) as LibraryState,
      settings: JSON.parse(core.settings()) as AppSettings,
      lexicon: lexiconReady(),
      dictionary: await isCached(DICTIONARY_URL),
    }
  },

  /**
   * Забирает лексикон и ставит его.
   *
   * Отдельным запросом, а не внутри `.wasm`: полтора мегабайта до первой
   * буквы текста — это полторы секунды, за которые читатель успевает уйти.
   * Ленивость сохраняется и здесь — лексикон разбирается при первом обращении
   * к нему, а не при установке.
   */
  async loadLexicon(): Promise<boolean> {
    await ensure()
    if (lexiconReady()) return true
    try {
      const bytes = await fetchCached(LEXICON_URL)
      return installLexicon(new TextDecoder().decode(bytes))
    } catch {
      // Нет сети и нет кэша: разбор слова ответит «не знаю», а книга всё
      // равно откроется и будет читаться.
      return false
    }
  },

  /**
   * Скачивает офлайн-словарь с прогрессом и ставит его в сессию.
   *
   * Отмена живёт здесь, а не приезжает параметром: `AbortSignal` через
   * границу воркера не передать, а тридцать мегабайт, которые нельзя
   * остановить, — это ровно то, из-за чего такие загрузки и ругают.
   */
  async loadDictionary(onProgress: (loaded: number, total: number) => void): Promise<boolean> {
    await ensure()
    downloading?.abort()
    downloading = new AbortController()
    try {
      const archive = await fetchCached(DICTIONARY_URL, {
        signal: downloading.signal,
        onProgress: ({ loaded, total }) => onProgress(loaded, total),
      })
      // Архив едет gzip: тридцать мегабайт против семи по сети.
      const plain =
        archive[0] === 0x1f && archive[1] === 0x8b ? await gunzip(archive) : archive
      return current().installDictionary(plain)
    } finally {
      downloading = null
    }
  },

  /** Останавливает скачивание словаря. */
  async cancelDictionary(): Promise<void> {
    downloading?.abort()
    downloading = null
  },

  /** Стоит ли словарь в этой сессии. */
  async dictionaryReady(): Promise<boolean> {
    return isCached(DICTIONARY_URL)
  },

  /** Ставит уже скачанный словарь — так он поднимается после перезапуска. */
  async restoreDictionary(): Promise<boolean> {
    await ensure()
    if (!(await isCached(DICTIONARY_URL))) return false
    try {
      const archive = await fetchCached(DICTIONARY_URL)
      const plain =
        archive[0] === 0x1f && archive[1] === 0x8b ? await gunzip(archive) : archive
      return current().installDictionary(plain)
    } catch {
      return false
    }
  },

  // --- Сессия ---------------------------------------------------------------

  /** Выполняет команду и записывает то, что изменилось. */
  async command(command: Command): Promise<Outcome> {
    await ensure()
    const outcome = JSON.parse(current().run(JSON.stringify(command))) as Outcome
    await persist(outcome)
    return outcome
  },

  /** Библиотека целиком — то, что рисует экран книг. */
  async library(): Promise<LibraryState> {
    await ensure()
    return JSON.parse(current().library()) as LibraryState
  },

  async settings(): Promise<AppSettings> {
    await ensure()
    return JSON.parse(current().settings()) as AppSettings
  },

  // --- Разбор ---------------------------------------------------------------

  async analyzeWord(word: string): Promise<WordAnalysis> {
    await ensure()
    return JSON.parse(wasmAnalyzeWord(word)) as WordAnalysis
  },

  async tokenize(text: string): Promise<TokenizedText> {
    await ensure()
    return JSON.parse(wasmTokenize(text)) as TokenizedText
  },

  async explain(sentence: string): Promise<Grammar> {
    await ensure()
    return JSON.parse(wasmExplain(sentence)) as Grammar
  },

  async grammarReference(): Promise<Reference> {
    await ensure()
    return JSON.parse(wasmGrammarReference()) as Reference
  },

  async grammarExercises(): Promise<Exercises> {
    await ensure()
    return JSON.parse(wasmGrammarExercises()) as Exercises
  },

  /**
   * Подстрочник: начальная форма и короткий русский эквивалент каждого слова.
   *
   * Одним вызовом на всю фразу, а не по слову. Внутри это два обращения к
   * памяти на слово, но через границу воркера каждое стоило бы отдельного
   * сообщения — двенадцать слов превратились бы в двадцать четыре пересылки
   * ради строки мелким шрифтом.
   */
  async gloss(words: string[]): Promise<{ lemma: string; translation: string; pos: string }[]> {
    await ensure()
    const core = current()
    return words.map((word) => {
      const analysis = JSON.parse(wasmAnalyzeWord(word)) as {
        lemma: string
        matchedPos?: string
        dominantPos?: string
        pos: string[]
      }
      const outcome = JSON.parse(
        core.run(JSON.stringify({ op: 'define', word: analysis.lemma, path: '' })),
      ) as Outcome
      return {
        lemma: analysis.lemma,
        translation: outcome.definition?.translations[0] ?? '',
        pos: analysis.matchedPos ?? analysis.dominantPos ?? analysis.pos[0] ?? '',
      }
    })
  },

  /** Ищет слово в офлайн-словаре. Пустой путь — словарь лежит в памяти. */
  async define(word: string): Promise<{
    entry: DictionaryEntry | null
    available: boolean
  }> {
    await ensure()
    const outcome = JSON.parse(
      current().run(JSON.stringify({ op: 'define', word, path: '' })),
    ) as Outcome
    return {
      entry: outcome.definition ?? null,
      available: outcome.dictionaryAvailable ?? false,
    }
  },

  // --- Книги ----------------------------------------------------------------

  /**
   * Кладёт файл книги в хранилище и разбирает его.
   *
   * Отпечаток сюда не приезжает и отсюда не уезжает: его считает главный
   * поток до передачи буфера, потому что буфер уходит `transfer` и после
   * передачи главному потоку уже недоступен.
   */
  async importBook(
    id: string,
    fileName: string,
    bytes: ArrayBuffer,
  ): Promise<OpenedBook> {
    await ensure()
    const extension = fileName.split('.').pop()?.toLowerCase() ?? ''
    const data = new Uint8Array(bytes)

    // Разбираем до записи: битый файл не должен занять место в хранилище.
    const book = WolfyBook.open(extension, nameOf(fileName), data)
    const path = await saveBook(id, extension, data)
    opened.get(id)?.free()
    opened.set(id, book)

    return { ...(JSON.parse(book.metadata()) as OpenedBook), path }
  },

  /**
   * Заводит книгу из уже извлечённых страниц: PDF, разобранный `pdf.js`, и
   * распознанная по фото страница приходят сюда.
   */
  async importPages(
    id: string,
    title: string,
    pages: string[],
  ): Promise<OpenedBook> {
    await ensure()
    const book = WolfyBook.fromPages(title, pages)

    // Страницы сохраняются разделителем страницы (U+000C): по нему книга
    // соберётся теми же страницами после перезапуска, а без него разбивка
    // потерялась бы и номера уехали.
    const data = new TextEncoder().encode(pages.join('\n\n\f\n\n'))
    const path = await saveBook(id, 'txt', data)
    opened.get(id)?.free()
    opened.set(id, book)

    return { ...(JSON.parse(book.metadata()) as OpenedBook), path }
  },

  /** Открывает книгу, уже лежащую в хранилище. */
  async openBook(id: string, path: string, title: string | null): Promise<OpenedBook> {
    await ensure()
    const bytes = await readBook(path)
    if (!bytes) throw new Error('файл книги не найден в хранилище')

    const extension = path.split('.').pop()?.toLowerCase() ?? ''
    const book = WolfyBook.open(extension, title ?? undefined, bytes)
    opened.get(id)?.free()
    opened.set(id, book)

    return { ...(JSON.parse(book.metadata()) as OpenedBook), path }
  },

  /**
   * Обложка книги, лежащей в хранилище.
   *
   * Открывает книгу отдельным, временным экземпляром и тут же его отпускает.
   * Через общий реестр это делать нельзя: библиотека тянет обложки, пока
   * читалка читает главу той же книги, и `free()` из-под неё уронил бы
   * чтение.
   */
  async cover(path: string): Promise<Uint8Array | null> {
    await ensure()
    const bytes = await readBook(path)
    if (!bytes) return null
    const extension = path.split('.').pop()?.toLowerCase() ?? ''
    let book: WolfyBook | null = null
    try {
      book = WolfyBook.open(extension, undefined, bytes)
      const meta = JSON.parse(book.metadata()) as { cover: string | null }
      return meta.cover ? book.resource(meta.cover) : null
    } catch {
      // Битая обложка или битая книга: библиотека наберёт свою.
      return null
    } finally {
      book?.free()
    }
  },

  /**
   * Названия глав книги, которая сейчас не открыта.
   *
   * Экран заметок показывает главу по имени, а не по номеру, но открывать
   * ради этого книгу в общей сессии нельзя: тот же идентификатор держит
   * читалка. Здесь книга открывается временно и закрывается сразу же.
   */
  async bookChapters(path: string): Promise<(string | null)[]> {
    await ensure()
    const bytes = await readBook(path)
    if (!bytes) return []
    const extension = path.split('.').pop()?.toLowerCase() ?? ''
    let book: WolfyBook | null = null
    try {
      book = WolfyBook.open(extension, undefined, bytes)
      const meta = JSON.parse(book.metadata()) as { chapters: { title: string | null }[] }
      return meta.chapters.map((chapter) => chapter.title)
    } catch {
      return []
    } finally {
      book?.free()
    }
  },

  /** Читает главу. Единственная тяжёлая операция — потому и здесь. */
  async chapter(id: string, index: number): Promise<Chapter> {
    const book = opened.get(id)
    if (!book) throw new Error('книга не открыта')
    return JSON.parse(book.chapter(index)) as Chapter
  },

  /** Иллюстрация из книги — отдаётся байтами, без копии. */
  async resource(id: string, path: string): Promise<Uint8Array | null> {
    const book = opened.get(id)
    if (!book) return null
    try {
      return book.resource(path)
    } catch {
      // Битая или отсутствующая картинка не повод ронять главу.
      return null
    }
  },

  /**
   * Читает файл книги как текст.
   *
   * Нужно ровно одному месту — книге снимков, которую дописывают страница за
   * страницей. Всё остальное читает главы через ядро.
   */
  async bookText(path: string): Promise<string> {
    const bytes = await readBook(path)
    return bytes ? new TextDecoder().decode(bytes) : ''
  },

  /** Закрывает книгу и отпускает её память. */
  async closeBook(id: string): Promise<void> {
    opened.get(id)?.free()
    opened.delete(id)
  },

  /** Стирает файл книги. Запись в библиотеке при этом остаётся помеченной. */
  async removeBookFile(id: string, path: string): Promise<void> {
    await api.closeBook(id)
    await removeFile(path || bookPath(id, ''))
  },
}

export type CoreApi = typeof api

/**
 * Имя книги из имени файла: расширение отрезается, подчёркивания
 * превращаются в пробелы. Настоящее название придёт из метаданных, если оно
 * там есть, а у TXT его не бывает вовсе.
 */
function nameOf(fileName: string): string {
  return fileName.replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').trim()
}

Comlink.expose(api)
