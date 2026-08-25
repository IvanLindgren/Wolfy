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
  inspectWord as wasmInspectWord,
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
  InspectResult,
  LibraryState,
  Outcome,
  PreparedChapter,
  ReadingSegment,
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
  PRACTICE_PATH,
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
      const candidates: Array<{ lib: string | null; set: string | null; prac: string | null }> = [
        { lib: raw.libraryPrimary, set: raw.settingsPrimary, prac: raw.practicePrimary },
        { lib: raw.libraryBackup, set: raw.settingsPrimary, prac: raw.practicePrimary },
        { lib: raw.libraryPrimary, set: raw.settingsBackup, prac: raw.practicePrimary },
        { lib: raw.libraryPrimary, set: raw.settingsPrimary, prac: raw.practiceBackup },
        { lib: raw.libraryBackup, set: raw.settingsBackup, prac: raw.practicePrimary },
        { lib: raw.libraryBackup, set: raw.settingsPrimary, prac: raw.practiceBackup },
        { lib: raw.libraryPrimary, set: raw.settingsBackup, prac: raw.practiceBackup },
        { lib: raw.libraryBackup, set: raw.settingsBackup, prac: raw.practiceBackup },
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
            tryNew?: (lib?: string, set?: string, prac?: string) => WolfySession
          }).tryNew
          if (typeof tryNew === 'function') {
            session = tryNew.call(WolfySession, c.lib ?? undefined, c.set ?? undefined, c.prac ?? undefined)
          } else {
            // Fallback для старой wasm без strict: пробуем lenient только если
            // primary не был битым по нашей JS-проверке (чтобы не маскировать битый).
            const isLibCorrupted =
              c.lib !== null && c.lib.trim() !== '' && !isValidJson(c.lib)
            const isSetCorrupted =
              c.set !== null && c.set.trim() !== '' && !isValidJson(c.set)
            const isPracCorrupted =
              c.prac !== null && c.prac.trim() !== '' && !isValidJson(c.prac)
            if (isLibCorrupted || isSetCorrupted || isPracCorrupted) continue
            session = new WolfySession(c.lib ?? undefined, c.set ?? undefined, c.prac ?? undefined)
          }
          if (c.lib !== raw.libraryPrimary || c.set !== raw.settingsPrimary || c.prac !== raw.practicePrimary) {
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
              if (c.prac !== raw.practicePrimary) {
                // practice может отсутствовать в старой сессии — проверяем наличие метода
                const maybePractice = (cur as unknown as { practice?: () => string }).practice
                if (typeof maybePractice === 'function') {
                  try {
                    await writeStateAtomic(PRACTICE_PATH, maybePractice.call(cur))
                  } catch {}
                }
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
          raw.practicePrimary,
          raw.practiceBackup,
        ].some((v) => v !== null && v.trim() !== '')
        if (hasAnyData) {
          throw new Error(
            `Состояние библиотеки повреждено и не восстановлено из бэкапа: ${String(lastError)}`,
          )
        }
        // Совсем нет данных — первый запуск, открываем пустую сессию.
        const tryNew = (WolfySession as unknown as {
          tryNew?: (lib?: string, set?: string, prac?: string) => WolfySession
        }).tryNew
        if (typeof tryNew === 'function') {
          session = tryNew.call(WolfySession, undefined, undefined, undefined)
        } else {
          session = new WolfySession(undefined, undefined, undefined)
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
/**
 * Очередь записей на каждый путь состояния.
 *
 * `command` — обычный метод comlink, и главный поток вправе позвать две
 * команды, не дожидаясь первой. Тогда две записи в один и тот же файл идут
 * внахлёст, а OPFS решает спор по времени закрытия дескриптора: снимок
 * поколения N мог лечь поверх N+1. Поколения этого не ловят — `ackSaved`
 * берёт максимум и снял бы пометку с состояния, которого на диске нет.
 * Поэтому записи в один файл выстраиваются в очередь.
 */
const writeQueues = new Map<string, Promise<unknown>>()

function writeSerial(path: string, data: string): Promise<void> {
  const previous = writeQueues.get(path) ?? Promise.resolve()
  // Провал прошлой записи не должен обрывать очередь: следующая всё равно
  // должна попробовать — она несёт более свежее состояние.
  const next = previous.then(
    () => writeStateAtomic(path, data),
    () => writeStateAtomic(path, data),
  )
  writeQueues.set(
    path,
    next.catch(() => undefined),
  )
  return next
}

async function persist(outcome: Outcome): Promise<void> {
  if (!outcome.changed) return
  const core = current()
  // Generation-aware (§17): snapshot generation N + ackSaved(N) — не теряем N+1 пока пишется N.
  const libGen = outcome.libraryGeneration ?? -1
  const setGen = outcome.settingsGeneration ?? -1
  const pracGen = outcome.practiceGeneration ?? -1

  const writes: Promise<void>[] = []
  if (outcome.libraryChanged) {
    // Сериализация уже на воркере, не на главном потоке — приемлемо.
    // Снимок берётся здесь, синхронно после команды: он отвечает ровно
    // тому поколению, которое подтвердит `ackSaved`.
    const libJson = core.library()
    writes.push(writeSerial(LIBRARY_PATH, libJson))
  }
  if (outcome.settingsChanged) {
    const setJson = core.settings()
    writes.push(writeSerial(SETTINGS_PATH, setJson))
  }
  if (outcome.practiceChanged) {
    const maybePractice = (core as unknown as { practice?: () => string }).practice
    if (typeof maybePractice === 'function') {
      try {
        const pracJson = maybePractice.call(core)
        writes.push(writeSerial(PRACTICE_PATH, pracJson))
      } catch {}
    }
  }
  await Promise.all(writes)
  // Ack только те домены, которые действительно записали, с их поколением.
  // Если за время записи пришло новое изменение (generation N+1), ack(N) оставит dirty true.
  const ack = (core as unknown as { ackSaved?: (l: bigint, s: bigint, p: bigint) => void; saved?: (l: boolean, s: boolean) => void; savedWithPractice?: (l: boolean, s: boolean, p: boolean) => void })
  if (typeof ack.ackSaved === 'function') {
    // wasm-bindgen ждёт i64 как BigInt, а не number — иначе
    // «Cannot convert 1 to a BigInt» и команда падает целиком.
    ack.ackSaved(
      BigInt(outcome.libraryChanged ? libGen : -1),
      BigInt(outcome.settingsChanged ? setGen : -1),
      BigInt(outcome.practiceChanged ? pracGen : -1),
    )
  } else if (typeof ack.savedWithPractice === 'function' && outcome.practiceChanged) {
    ack.savedWithPractice(!!outcome.libraryChanged, !!outcome.settingsChanged, !!outcome.practiceChanged)
  } else if (typeof ack.saved === 'function') {
    ack.saved(!!outcome.libraryChanged, !!outcome.settingsChanged)
  }
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

  async inspectWord(word: string, sentence: string): Promise<InspectResult> {
    await ensure()
    return JSON.parse(wasmInspectWord(word, sentence)) as InspectResult
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

  /** Читает главу вместе с токенами — один тяжёлый переход (§15). */
  async preparedChapter(id: string, index: number): Promise<PreparedChapter> {
    const book = opened.get(id)
    if (!book) throw new Error('книга не открыта')
    // Предпочитаем новый API, фолбэк на старый если WASM без него
    const maybe = (book as unknown as { preparedChapter?: (i: number) => string })
    if (typeof maybe.preparedChapter === 'function') {
      return JSON.parse(maybe.preparedChapter(index)) as PreparedChapter
    }
    // Fallback: собрать из старой главы + токенизации
    const raw = JSON.parse(book.chapter(index)) as Chapter
    const text = raw.blocks
      .filter((b) => b.text)
      .map((b) => b.text)
      .join('\n\n')
    const parsed = JSON.parse(wasmTokenize(text)) as TokenizedText
    return {
      title: raw.title,
      blocks: raw.blocks,
      tokens: parsed.tokens.map((t) => ({ kind: t.kind, start: t.start, end: t.end })),
      sentences: parsed.sentences.map((s) => ({
        start: s.start,
        end: s.end,
        firstToken: s.firstToken,
        lastToken: s.lastToken,
      })),
    }
  },

  /**
   * Якоря полужирной основы: по числу на токен главы.
   *
   * Считается ядром на всю главу разом и возвращается типизированным
   * массивом: на десять тысяч токенов JSON весил бы под сорок килобайт
   * текста, который ещё надо разобрать.
   *
   * Ядро без этой функции (старый wasm рядом со свежей сборкой) отвечает
   * пустым массивом, а не падением: выделение — украшение чтения, и его
   * отсутствие не должно закрывать книгу.
   */
  async chapterAnchors(id: string, index: number): Promise<Uint16Array> {
    const book = opened.get(id)
    if (!book) throw new Error('книга не открыта')
    const maybe = (book as unknown as { chapterAnchors?: (i: number) => Uint16Array })
    if (typeof maybe.chapterAnchors !== 'function') return new Uint16Array()
    return maybe.chapterAnchors(index)
  },

  /**
   * Отрезок чтения: докуда честно читать за один подход.
   *
   * Границу выбирает ядро — она обязана совпадать с телефоном, иначе одна и
   * та же закладка даст на двух устройствах разные отрезки.
   */
  async chapterSegment(
    id: string,
    index: number,
    from: number,
    targetWords: number,
  ): Promise<ReadingSegment | null> {
    const book = opened.get(id)
    if (!book) throw new Error('книга не открыта')
    const maybe = (book as unknown as {
      chapterSegment?: (i: number, from: number, target: number) => string
    })
    if (typeof maybe.chapterSegment !== 'function') return null
    return JSON.parse(maybe.chapterSegment(index, from, targetWords)) as ReadingSegment
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

  /**
   * Переносит уже разобранную книгу под другой номер.
   *
   * Нужно из-за §5: файл кладётся под случайным номером, а ядро заменяет его
   * на канонический из `source_key`. Без переноса разобранная книга осталась
   * бы висеть в памяти под старым ключом до конца жизни воркера, а читалка
   * разбирала бы тот же файл заново.
   */
  async rekeyBook(from: string, to: string): Promise<void> {
    if (from === to) return
    const book = opened.get(from)
    if (!book) return
    opened.delete(from)
    // Под целевым номером что-то уже могло быть открыто — отпускаем.
    opened.get(to)?.free()
    opened.set(to, book)
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
