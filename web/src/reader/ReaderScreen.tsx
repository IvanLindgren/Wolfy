/**
 * Читалка.
 *
 * Собирает вместе всё остальное: главу с токенами, слой подсветки, карточку,
 * оглавление, настройку вида, клавиши и жесты.
 *
 * Два правила, которые здесь легко нарушить и трудно заметить:
 *
 * 1. **Положение хранится в индексах токенов, а не в пикселях.** Смена кегля,
 *    темы или ширины окна не должна терять место в книге; пиксели теряют его
 *    при первом же повороте телефона.
 * 2. **Прогресс пишется с задержкой.** `rememberProgress` при каждом кадре
 *    прокрутки — это запись состояния десять раз в секунду, ровно та ошибка,
 *    из-за которой читалка и тормозила на десктопе.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AnimatePresence, motion as m } from 'motion/react'
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router'

import { useShortcuts } from '../app/shortcuts'
import { THEMES, applyTheme, motionFor } from '../app/theme'
import { WordCard, type CardTarget } from '../card/WordCard'
import * as bridge from '../core/bridge'
import { session, useSession } from '../core/session'
import type { LibraryBook, ReadingSegment, ThemeName } from '../core/types'
import { seconds } from '../theme/motion'
import { Button } from '../widgets/Button'
import {
  BackIcon,
  CloseIcon,
  ContentsIcon,
  NoteIcon,
  NotesIcon,
  DecksIcon,
  ForwardIcon,
  ReaderIcon,
  TuneIcon,
} from '../widgets/icons'
import { WolfyCompanion } from '../widgets/Wolfy'
import { ToolDock, lastPencilTone, savePencilTone, type OpenAnnotation, type Tool } from './annotate'
import { toneColor, useAnnotations, type Tone } from './annotations'
import { AttentionBar, usePacer } from './attention'
import { ChapterView, savedRanges, textOf, type TokenRange } from './ChapterView'
import { Paginator, type PagerHandle } from './Paginator'
import { readerFont, readerMeasure, readingMode, setReaderFont, setReaderMeasure, setReadingMode, type ReaderFont, type ReadingMode } from './preferences'
import styles from './reader.module.css'
import { sentenceAt, useChapter } from './useChapter'

/** Как часто записывать место в книге — ~3 секунды (политику native). */
const PROGRESS_DELAY = 3000

/** Сколько горит вспышка на месте, к которому пришли из заметок. */
const FLASH_MS = 1600

/** Цвет бумаги каждой темы — то, что видно на кружке выбора. */
const THEME_SWATCH: Record<string, string> = {
  Paper: '#fbf9f5',
  Sepia: '#f4efe6',
  Dark: '#231f20',
  Oled: '#000000',
}

export function ReaderScreen() {
  const { bookId } = useParams({ from: '/reader/$bookId' })
  const navigate = useNavigate()
  /*
   * Место, к которому пришли ссылкой из заметок.
   *
   * Держится в ref, а не в зависимости эффектов: ссылка отрабатывается один
   * раз при загрузке главы, а её «поглощение» (чистка адреса) не должно
   * перезапускать открытие книги.
   */
  const place = useSearch({ from: '/reader/$bookId' })
  const placeRef = useRef(place)
  placeRef.current = place

  const book = useSession((state) => state.library.books.find((item) => item.id === bookId))
  const libraryBooks = useSession((state) => state.library.books)
  const cards = useSession((state) => state.library.cards)
  const settings = useSession((state) => state.settings)
  const ready = useSession((state) => state.ready)
  const timing = motionFor(settings)

  // Не просто boolean: при переходе между `/reader/:bookId` компонент остаётся
  // смонтированным. Старое `true` позволяло useChapter запросить новую книгу
  // раньше, чем её успевал открыть worker.
  const [openedBookId, setOpenedBookId] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [chapterIndex, setChapterIndex] = useState(-1)
  const [mode, setMode] = useState<ReadingMode>(readingMode)
  const [contents, setContents] = useState(false)
  const [tuner, setTuner] = useState(false)
  const [card, setCard] = useState<CardTarget | null>(null)
  const [images, setImages] = useState<Map<string, string>>(new Map())
  const [recent, setRecent] = useState<string[]>([])
  const [chapterTitles, setChapterTitles] = useState<(string | null)[]>([])
  const [page, setPage] = useState({ page: 0, pages: 1 })

  // --- Инструменты отметок ---------------------------------------------------
  //
  // Карандаш и стикер — два режима поверх чтения. Включённый инструмент
  // отключает открытие карточек по слову: пока читатель «рисует», читалка не
  // должна отвечать на нажатия так, как отвечает при чтении.
  const [tool, setTool] = useState<Tool>(null)
  const [pencilTone, setPencilTone] = useState<Tone>(lastPencilTone)
  const [openNote, setOpenNote] = useState<string | null>(null)

  const scroller = useRef<HTMLDivElement>(null)
  const pager = useRef<PagerHandle | null>(null)
  const restored = useRef(false)
  const savedAt = useRef(0)
  // Токен, к которому обязана прокрутиться загруженная глава: из ссылки заметок.
  const pendingJump = useRef<number | null>(null)

  const handlePage = useCallback((current: number, total: number) => {
    setPage((previous) =>
      previous.page === current && previous.pages === total
        ? previous
        : { page: current, pages: total },
    )
  }, [])

  const opened = openedBookId === bookId
  const { chapter, error } = useChapter(bookId, Math.max(0, chapterIndex), opened)

  // --- Открытие книги -------------------------------------------------------

  useEffect(() => {
    if (!ready || !book) return
    setOpenedBookId(null)
    setFailure(null)
    if (!book.path) {
      setFailure(
        'Этой книги нет на этом устройстве: она приехала синхронизацией. Добавьте файл — прогресс и колода уже ждут её.',
      )
      return
    }
    // Заметки читаются рядом с книгой и по той же причине, что и она: до их
    // появления выделения в тексте просто не нарисуются.
    void useAnnotations.getState().open(book.id)

    let alive = true
    void bridge
      .openBook(book.id, book.path, book.title)
      .then((info) => {
        if (!alive) return
        setChapterTitles(info.chapters.map((item) => item.title))
        // Глава из ссылки сильнее сохранённого прогресса: читатель пришёл к
        // конкретному месту, а не «продолжить чтение».
        const wanted = placeRef.current.chapter
        setChapterIndex(
          Math.min(
            wanted !== undefined ? wanted : book.progress.chapter,
            Math.max(0, info.chapters.length - 1),
          ),
        )
        pendingJump.current = placeRef.current.token ?? null
        setOpenedBookId(book.id)
        // Книга открывается ровно там, где её закрыли, — включая место внутри
        // главы. Оно восстанавливается после того, как глава разложилась.
        restored.current = false
      })
      .catch((problem: unknown) => {
        if (alive) {
          setFailure(problem instanceof Error ? problem.message : 'Книгу не удалось открыть')
        }
      })

    return () => {
      alive = false
      void bridge.closeBook(book.id)
    }
  }, [ready, book?.id, book?.path, book?.title])

  // Недавно добавленные слова напоминаются при возвращении: читатель, открывший
  // книгу через неделю, не помнит, что именно он отмечал.
  useEffect(() => {
    if (!opened) return
    const week = Date.now() - 7 * 86_400_000
    const words = cards
      .filter((item) => item.bookId === bookId && !item.deleted && item.addedAt > week)
      .sort((a, b) => b.addedAt - a.addedAt)
      .slice(0, 8)
      .map((item) => item.surface)
    setRecent(words)
    // Список считается один раз на открытие книги: он про «что было раньше», а
    // не про то, что читатель отмечает прямо сейчас.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, bookId])

  // --- Картинки главы -------------------------------------------------------
  // §24: в scroll режиме грузим только видимые + 600px prefetch через
  // IntersectionObserver с лимитом 2 параллельных resource, в pages — eager
  // но тоже с лимитом 2 и батчем (один setImages) чтобы не дёргать пагинацию.
  useEffect(() => {
    const paths = chapter.blocks
      .map((item) => item.block)
      .filter((block) => block.kind === 'image' && block.path)
      .map((block) => block.path!)
    const uniqPaths = [...new Set(paths)]
    if (!uniqPaths.length) {
      setImages((prev) => {
        prev.forEach((url) => URL.revokeObjectURL(url))
        return new Map()
      })
      return
    }

    let alive = true
    const createdUrls: string[] = []
    const revokeCreated = () => createdUrls.forEach((u) => URL.revokeObjectURL(u))

    // page mode: eager батч
    if (mode === 'pages') {
      // очищаем предыдущие перед загрузкой новой главы
      setImages((prev) => {
        prev.forEach((u) => URL.revokeObjectURL(u))
        return new Map()
      })
      void (async () => {
        const { limitedResource } = await import('./imageLoader')
        const results = new Map<string, string>()
        // Запускаем все, но limitedResource держит семафор 2
        const pairs = await Promise.all(
          uniqPaths.map(async (p) => {
            try {
              const bytes = await limitedResource(bookId, p)
              if (!alive || !bytes) return null
              const url = URL.createObjectURL(new Blob([bytes as BlobPart]))
              createdUrls.push(url)
              return [p, url] as const
            } catch {
              return null
            }
          }),
        )
        if (!alive) {
          revokeCreated()
          return
        }
        for (const pair of pairs) if (pair) results.set(pair[0], pair[1])
        setImages(results)
      })()
      return () => {
        alive = false
        revokeCreated()
        // revoke уже выставленной карты при размонтаже/переключении режима
        setImages((prev) => {
          // не трогаем createdUrls двойным revoke — они уже в prev если успели
          return prev
        })
      }
    }

    // scroll mode: lazy по видимости
    setImages((prev) => {
      prev.forEach((u) => URL.revokeObjectURL(u))
      return new Map()
    })
    const loaded = new Map<string, string>()
    let observer: IntersectionObserver | null = null
    let raf = 0
    let scrollerEl: HTMLElement | null = scroller.current

    // фолбэк если наблюдателя нет — грузим с лимитом инкрементально
    const scheduleLoad = async (path: string) => {
      if (!alive || loaded.has(path)) return
      const { limitedResource } = await import('./imageLoader')
      try {
        const bytes = await limitedResource(bookId, path)
        if (!alive || !bytes) return
        const url = URL.createObjectURL(new Blob([bytes as BlobPart]))
        createdUrls.push(url)
        loaded.set(path, url)
        if (alive) setImages(new Map(loaded))
      } catch {
        // игнорируем битую картинку
      }
    }

    const observeTargets = () => {
      if (!alive) return
      scrollerEl = scroller.current
      const host: HTMLElement | null = scrollerEl
      if (!host) {
        raf = window.requestAnimationFrame(observeTargets)
        return
      }
      const figures = host.querySelectorAll<HTMLElement>('[data-image-path]')
      if (!figures.length) {
        // контент ещё не отрендерился — пробуем следующий кадр (до 10 попыток)
        raf = window.requestAnimationFrame(observeTargets)
        return
      }
      if (typeof IntersectionObserver === 'undefined') {
        // фолбэк: грузим всё с лимитом
        uniqPaths.forEach((p) => void scheduleLoad(p))
        return
      }
      observer = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (!entry.isIntersecting) continue
            const target = entry.target as HTMLElement
            const p = target.dataset.imagePath
            if (!p || loaded.has(p)) continue
            observer?.unobserve(target)
            void scheduleLoad(p)
          }
        },
        { root: host, rootMargin: '600px 0px', threshold: 0 },
      )
      figures.forEach((fig) => observer!.observe(fig))
    }

    raf = window.requestAnimationFrame(observeTargets)

    return () => {
      alive = false
      if (raf) cancelAnimationFrame(raf)
      observer?.disconnect()
      revokeCreated()
      loaded.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [chapter.blocks, bookId, mode])

  // --- Подсветка сохранённых слов -------------------------------------------

  const known = useMemo(() => {
    const set = new Set<string>()
    for (const item of cards) {
      if (item.deleted || item.kind !== 'word') continue
      if (item.lemma) set.add(item.lemma.toLowerCase())
      if (item.surface) set.add(item.surface.toLowerCase())
    }
    return set
  }, [cards])

  /*
   * Помощь вниманию: якоря основы, окно чтения, ведущая строка, отрезок.
   *
   * Всё три приёма выключены по умолчанию и живут в настройках, которые
   * ездят между устройствами: включив окно на телефоне, читатель ждёт его и
   * в браузере.
   */
  const [anchors, setAnchors] = useState<Uint16Array | undefined>(undefined)
  useEffect(() => {
    if (!settings.emphasizeStems || !opened || !chapter.tokens.length) {
      setAnchors(undefined)
      return
    }
    let alive = true
    void bridge
      .chapterAnchors(bookId, Math.max(0, chapterIndex))
      .then((found) => {
        // Пустой массив — ядро без этой функции. Тогда текст остаётся
        // обычным: выделение основы украшает чтение, а не держит его.
        if (alive && found.length > 0) setAnchors(found)
      })
      .catch(() => {
        if (alive) setAnchors(undefined)
      })
    return () => {
      alive = false
    }
  }, [settings.emphasizeStems, opened, bookId, chapterIndex, chapter.tokens.length])

  // Ведущая строка: едет, только пока читатель этого хочет.
  const [pacing, setPacing] = useState(false)
  useEffect(() => setPacing(false), [chapterIndex, bookId])
  useEffect(() => {
    if (settings.pacerWpm <= 0) setPacing(false)
  }, [settings.pacerWpm])

  const wordsIn = useCallback(
    (from: number, to: number) =>
      chapter.tokens.slice(from, to).filter((token) => token.kind === 'word').length,
    [chapter.tokens],
  )

  const pacer = usePacer(
    chapter.sentences,
    wordsIn,
    settings.pacerWpm,
    pacing,
    0,
  )
  useEffect(() => {
    if (pacer.done) setPacing(false)
  }, [pacer.done])

  /*
   * Отрезок чтения: докуда читаем сейчас.
   *
   * Границу считает ядро — она обязана совпадать с телефоном, иначе одна и
   * та же закладка дала бы на двух устройствах разные отрезки. Пересчёт
   * только при смене главы и по кнопке «ещё один»: отрезок, который сам
   * ползёт вслед за читателем, — это снова книга без конца.
   */
  const [segment, setSegment] = useState<ReadingSegment | null>(null)
  const [segmentFrom, setSegmentFrom] = useState(0)
  const [readToken, setReadToken] = useState(0)

  useEffect(() => {
    setSegmentFrom(0)
    setReadToken(0)
  }, [bookId, chapterIndex])

  useEffect(() => {
    if (settings.segmentWords <= 0 || !opened || !chapter.tokens.length) {
      setSegment(null)
      return
    }
    let alive = true
    void bridge
      .chapterSegment(bookId, Math.max(0, chapterIndex), segmentFrom, settings.segmentWords)
      .then((found) => {
        if (alive) setSegment(found)
      })
      .catch(() => {
        if (alive) setSegment(null)
      })
    return () => {
      alive = false
    }
  }, [
    settings.segmentWords,
    opened,
    bookId,
    chapterIndex,
    segmentFrom,
    chapter.tokens.length,
  ])

  const [phraseMark, setPhraseMark] = useState<TokenRange | null>(null)

  /*
   * Выделения и заметки этой главы.
   *
   * Заметка без цитаты (`end === start`) рисуется полоской на поле, поэтому
   * ей достаточно одного токена ширины — но взять его надо аккуратно: у
   * последнего токена главы `start + 1` уже за границей.
   */
  const annotations = useAnnotations((state) => state.annotations)
  const annotationMarks = useMemo<TokenRange[]>(
    () =>
      annotations
        .filter((item) => !item.deleted && item.chapter === chapterIndex)
        .map((item) => ({
          start: item.start,
          end: item.end > item.start ? item.end : Math.min(item.start + 1, chapter.tokens.length),
          kind: item.tone ? ('mark' as const) : ('note' as const),
          tone: item.tone ? toneColor(item.tone) : undefined,
          id: item.id,
          hasNote: item.note !== '',
        }))
        .filter((range) => range.end > range.start),
    [annotations, chapterIndex, chapter.tokens.length],
  )

  /*
   * Пометка, открытая на правку.
   *
   * Берётся из хранилища по номеру, а не запоминается снимком: заметку можно
   * менять и со страницы заметок, и с другого устройства, и панель обязана
   * показывать то, что есть сейчас.
   */
  const openAnnotation = useMemo<OpenAnnotation | null>(() => {
    if (!openNote) return null
    const item = annotations.find((entry) => entry.id === openNote && !entry.deleted)
    if (!item) return null
    return { id: item.id, quote: item.quote, note: item.note, tone: item.tone }
  }, [annotations, openNote])

  // Пометка могла исчезнуть — со страницы заметок или с другого устройства.
  useEffect(() => {
    if (openNote && !openAnnotation) setOpenNote(null)
  }, [openNote, openAnnotation])

  const saveNote = useCallback((id: string, note: string) => {
    void useAnnotations.getState().update(id, { note })
  }, [])

  const setNoteTone = useCallback((id: string, tone: Tone) => {
    void useAnnotations.getState().update(id, { tone })
  }, [])

  const deleteNote = useCallback((id: string) => {
    setOpenNote((current) => (current === id ? null : current))
    void useAnnotations.getState().remove(id)
  }, [])

  /**
   * Пометка под токеном — то, что сотрёт карандаш.
   *
   * Из нескольких берётся самая короткая: выделенное слово внутри выделенного
   * абзаца стирается первым, иначе до него было бы не добраться.
   */
  const annotationAt = useCallback(
    (token: number): string | null => {
      let found: { id: string; size: number } | null = null
      for (const item of annotations) {
        if (item.deleted || item.chapter !== chapterIndex) continue
        if (token < item.start || token >= item.end) continue
        const size = item.end - item.start
        if (!found || size < found.size) found = { id: item.id, size }
      }
      return found?.id ?? null
    },
    [annotations, chapterIndex],
  )

  const marks = useMemo<TokenRange[]>(() => {
    const list = [...savedRanges(chapter.tokens, known), ...annotationMarks]
    return phraseMark ? [...list, phraseMark] : list
  }, [chapter.tokens, known, annotationMarks, phraseMark])

  // --- Место в книге --------------------------------------------------------

  const currentToken = useCallback((): number => {
    if (mode === 'pages') return pager.current?.firstToken() ?? 0
    const host = scroller.current
    if (!host) return 0
    // §21: не сканировать 10k spans: сначала находим видимый block, потом токены внутри него
    const blocks = host.querySelectorAll<HTMLElement>('[data-block]')
    if (blocks.length) {
      const scrollerRect = host.getBoundingClientRect()
      const hasLayout = scrollerRect.height > 0 && scrollerRect.width > 0
      if (hasLayout) {
        const edgeTop = scrollerRect.top + 8
        let target: HTMLElement | null = null
        for (const b of Array.from(blocks)) {
          const r = b.getBoundingClientRect()
          // content-visibility:auto даёт 0×0 у offscreen — пропускаем, они не видимы
          if (r.width < 1 && r.height < 1) continue
          if (r.bottom >= edgeTop) {
            // блок с токенами либо первый после обреза
            const first = b.dataset.firstToken
            if (first !== undefined && first !== '-1') {
              target = b
              break
            }
            // блок без токенов (divider/image) — ищем следующий с токенами
            continue
          }
        }
        if (!target) {
          // внизу книги — берём последний блок с токенами
          for (let i = blocks.length - 1; i >= 0; i -= 1) {
            const b = blocks[i] as HTMLElement
            if (b.dataset.firstToken !== undefined && b.dataset.firstToken !== '-1') {
              target = b
              break
            }
          }
        }
        if (target) {
          const tokens = target.querySelectorAll<HTMLElement>('[data-t]')
          for (const el of Array.from(tokens)) {
            const r = el.getBoundingClientRect()
            if (r.width < 1 && r.height < 1) {
              if ((el as HTMLElement).offsetTop >= host.scrollTop + 8) return Number(el.dataset.t)
              continue
            }
            if (r.top >= edgeTop) return Number(el.dataset.t)
          }
          const first = target.dataset.firstToken
          if (first && first !== '-1') return Number(first)
        }
      } else {
        // jsdom / без layout: fallback через offsetTop, но только по блокам
        const edge = host.scrollTop + 8
        let target: HTMLElement | null = null
        for (const b of Array.from(blocks)) {
          if (b.dataset.firstToken === '-1') continue
          const top = (b as HTMLElement).offsetTop
          const h = (b as HTMLElement).offsetHeight || 260
          if (top + h >= edge || top >= edge) {
            target = b
            break
          }
        }
        if (target) {
          const tokens = target.querySelectorAll<HTMLElement>('[data-t]')
          for (const el of Array.from(tokens)) {
            if (el.offsetTop >= edge) return Number(el.dataset.t)
          }
          const ft = target.dataset.firstToken
          if (ft && ft !== '-1') return Number(ft)
        }
      }
    }
    // Fallback: глобальный скан только если block-путь не нашёл (безопасность)
    const edge = host.scrollTop + 8
    const all = host.querySelectorAll<HTMLElement>('[data-t]')
    for (const element of Array.from(all)) {
      const hostRect = host.getBoundingClientRect()
      if (hostRect.height > 0) {
        const r = element.getBoundingClientRect()
        if (r.width >= 1 && r.height >= 1) {
          if (r.top >= hostRect.top + 8) return Number(element.dataset.t)
          continue
        }
      }
      if (element.offsetTop >= edge) return Number(element.dataset.t)
    }
    return 0
  }, [mode])

  const remember = useCallback(
    (force = false) => {
      if (!book || !chapter.tokens.length) return
      const token = currentToken()
      // Отрезок показывает, сколько слов пройдено, и место ему нужно чаще,
      // чем оно уходит на диск. Обновляем только при настоящем сдвиге:
      // перерисовка полосы на каждый тик таймера ей ничего не добавит.
      setReadToken((known) => (Math.abs(known - token) > 2 ? token : known))
      const fraction = Math.min(1, Math.max(0, token / chapter.tokens.length))
      const now = Date.now()
      if (!force && now - savedAt.current < PROGRESS_DELAY) return
      savedAt.current = now
      void session.rememberProgress(book.id, Math.max(0, chapterIndex), fraction)
    },
    [book, chapter.tokens.length, chapterIndex, currentToken],
  )

  const flushProgress = useCallback(() => {
    savedAt.current = 0
    remember(true)
  }, [remember])

  // Место записывается по таймеру, а не на каждый кадр прокрутки
  useEffect(() => {
    const timer = window.setInterval(() => remember(false), PROGRESS_DELAY)
    return () => {
      window.clearInterval(timer)
      flushProgress()
    }
  }, [remember, flushProgress])

  // §22: flush при скрытии вкладки / уходе страницы (visibility hidden / pagehide)
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') flushProgress()
    }
    const onPageHide = () => flushProgress()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', onPageHide)
    }
  }, [flushProgress])



  // Возврат ровно туда, где закрыли: доля главы превращается обратно в токен.
  // Если пришла ссылка из заметок, она сильнее сохранённого места — и после
  // отработки стирается из адреса, чтобы перезагрузка не тянула к заметке
  // снова, а открывала книгу там, где читатель уже сам остановился.
  useEffect(() => {
    if (!book || restored.current || !chapter.tokens.length) return
    restored.current = true

    const jump = pendingJump.current
    if (jump !== null) {
      pendingJump.current = null
      showToken(Math.min(jump, chapter.tokens.length - 1))
      flashToken(Math.min(jump, chapter.tokens.length - 1))
      void navigate({
        to: '/reader/$bookId',
        params: { bookId },
        search: {},
        replace: true,
      })
      return
    }

    const target = Math.round(book.progress.withinChapter * chapter.tokens.length)
    if (target <= 0) return
    showToken(target)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [book?.id, chapter.tokens.length])

  const showToken = useCallback(
    (token: number) => {
      if (mode === 'pages') {
        pager.current?.showToken(token)
        return
      }
      const host = scroller.current
      const element = host?.querySelector<HTMLElement>(`[data-t="${token}"]`)
      element?.scrollIntoView({ block: 'start', behavior: 'auto' })
    },
    [mode],
  )

  /*
   * Короткая вспышка на месте, к которому пришли.
   *
   * Прокрутка показывает, *где* место, но не *что* это: страница токенов
   * выглядит одинаково в любую сторону. Полторы секунды подсветки отвечают
   * на второй вопрос, не трогая первый.
   */
  const flashToken = useCallback((token: number) => {
    if (mode === 'pages') return
    const host = scroller.current
    const element = host?.querySelector<HTMLElement>(`[data-t="${token}"]`)
    const flashClass = styles.flash
    if (!element || !flashClass) return
    element.classList.add(flashClass)
    window.setTimeout(() => element.classList.remove(flashClass), FLASH_MS)
  }, [mode])

  // --- Переходы -------------------------------------------------------------

  const chapters = chapterTitles.length || book?.chapters || 0

  const goChapter = useCallback(
    (index: number) => {
      if (index < 0 || index >= chapters) return
      // §22: фиксируем место старой главы до смены
      savedAt.current = 0
      // вызываем remember синхронно до смены индекса, чтобы доли считались для старой главы
      if (book && chapter.tokens.length) {
        const token = currentToken()
        const fraction = Math.min(1, Math.max(0, token / chapter.tokens.length))
        void session.rememberProgress(book.id, Math.max(0, chapterIndex), fraction)
        savedAt.current = Date.now()
      }
      restored.current = true
      setChapterIndex(index)
      setContents(false)
      scroller.current?.scrollTo({ top: 0 })
    },
    [chapters, book, chapter.tokens.length, chapterIndex, currentToken],
  )

  const turn = useCallback(
    (delta: number) => {
      if (mode === 'pages') {
        const handle = pager.current
        if (!handle) return
        const next = handle.page + delta
        if (next < 0) {
          goChapter(chapterIndex - 1)
          return
        }
        if (next >= handle.pages) {
          goChapter(chapterIndex + 1)
          return
        }
        handle.turn(delta)
        return
      }
      const host = scroller.current
      if (!host) return
      // В ленте «страница» — это экран минус две строки перекрытия: без него
      // глаз теряет место склейки.
      host.scrollBy({ top: delta * (host.clientHeight - 64), behavior: 'smooth' })
    },
    [mode, chapterIndex, goChapter],
  )

  // --- Выбор слова и фразы --------------------------------------------------

  /*
   * Приклеивает стикер к месту.
   *
   * Если на этом куске уже есть отметка, второй стикер не клеится — просто
   * открывается существующий. Иначе создаётся пустая заметка без краски, и
   * она сразу открывается на редактирование: стикер приклеили — на нём пишут.
   */
  const placeSticker = useCallback(
    async (start: number, end: number, quote: string) => {
      if (!book) return
      const existing = useAnnotations
        .getState()
        .annotations.find(
          (item) =>
            !item.deleted &&
            item.chapter === chapterIndex &&
            item.start === start &&
            item.end === end,
        )
      if (existing) {
        setOpenNote(existing.id)
        return
      }
      const created = await useAnnotations.getState().add({
        chapter: chapterIndex,
        start,
        end,
        tone: null,
        quote,
        note: '',
      })
      setOpenNote(created.id)
    },
    [book, chapterIndex],
  )

  const openWord = useCallback(
    (token: number, element: HTMLElement) => {
      /*
       * Карандаш работает и ластиком.
       *
       * Провёл — покрасил, нажал по покрашенному — снял. Отдельный
       * инструмент-ластик тут был бы лишней кнопкой: тем же карандашом
       * зачёркивают на бумаге.
       */
      if (tool === 'pencil') {
        const existing = annotationAt(token)
        if (existing) deleteNote(existing)
        return
      }
      const word = chapter.tokens[token]
      if (!word || !book) return
      if (tool === 'sticker') {
        window.getSelection()?.removeAllRanges()
        void placeSticker(token, token + 1, word.text)
        return
      }
      const sentence = sentenceAt(chapter, token)
      setPhraseMark(null)
      setCard({
        kind: 'word',
        bookId: book.id,
        surface: word.text,
        sentence: sentence?.text ?? word.text,
        tokens: sentence
          ? chapter.tokens.slice(sentence.firstToken, sentence.lastToken)
          : [word],
        offset: sentence?.firstToken ?? token,
        selectedToken: token - (sentence?.firstToken ?? token),
        chapter: chapterIndex,
        range: { start: token, end: token + 1 },
        quote: word.text,
        origin: element,
      })
    },
    [chapter, chapterIndex, book, tool, placeSticker, annotationAt, deleteNote],
  )

  const openPhrase = useCallback(
    (start: number, end: number) => {
      if (!book) return
      const text = textOf(chapter.tokens, start, end, chapter.text)
      if (!text.trim()) return

      // Карандаш: выделение становится маркером сразу, без карточек и полей.
      if (tool === 'pencil') {
        window.getSelection()?.removeAllRanges()
        setPhraseMark(null)
        void useAnnotations.getState().add({
          chapter: chapterIndex,
          start,
          end,
          tone: pencilTone,
          quote: text,
          note: '',
        })
        return
      }

      // Стикер: на выделенную фразу клеится заметка.
      if (tool === 'sticker') {
        window.getSelection()?.removeAllRanges()
        setPhraseMark(null)
        void placeSticker(start, end, text)
        return
      }

      setPhraseMark({ start, end, kind: 'phrase' })
      setCard({
        kind: 'phrase',
        bookId: book.id,
        surface: '',
        sentence: text,
        tokens: chapter.tokens.slice(start, end),
        offset: start,
        chapter: chapterIndex,
        range: { start, end },
        quote: text,
        origin: null,
      })
    },
    [book, chapterIndex, chapter.tokens, chapter.text, tool, pencilTone, placeSticker],
  )

  /*
   * След карандаша: пока читатель тянет, выделение уже красится выбранной
   * краской — как настоящий маркер, который оставляет цвет под собой.
   */
  const onPhraseDraft = useCallback(
    (start: number, end: number) => {
      if (tool !== 'pencil') return
      setPhraseMark({ start, end, kind: 'phrase', tone: toneColor(pencilTone) })
    },
    [tool, pencilTone],
  )

  const closeCard = useCallback(() => {
    setCard(null)
    setPhraseMark(null)
    window.getSelection()?.removeAllRanges()
  }, [])

  // --- Стикеры этой главы ----------------------------------------------------

  // --- Клавиши --------------------------------------------------------------

  // Перехват **до потомков**: читалка обязана забрать пробел и стрелки у
  // собственной прокрутки, иначе страница не перелистнётся, а прокрутится.
  useShortcuts(
    useMemo(
      () => [
        { key: 'ArrowRight', run: () => turn(1) },
        { key: 'PageDown', run: () => turn(1) },
        { key: ' ', shift: false, run: () => turn(1) },
        { key: 'ArrowLeft', run: () => turn(-1) },
        { key: 'PageUp', run: () => turn(-1) },
        { key: ' ', shift: true, run: () => turn(-1) },
        {
          key: 'Home',
          run: () => {
            if (mode === 'pages') pager.current?.go(0)
            else scroller.current?.scrollTo({ top: 0, behavior: 'smooth' })
          },
        },
        {
          key: 'End',
          run: () => {
            if (mode === 'pages') pager.current?.go((pager.current?.pages ?? 1) - 1)
            else
              scroller.current?.scrollTo({
                top: scroller.current.scrollHeight,
                behavior: 'smooth',
              })
          },
        },
        {
          // Лестница назад: стикер → инструмент → оглавление → карточка → книга.
          key: 'Escape',
          run: () => {
            if (openNote) setOpenNote(null)
            else if (tool) {
              setTool(null)
              setPhraseMark(null)
              window.getSelection()?.removeAllRanges()
            } else if (contents) setContents(false)
            else if (tuner) setTuner(false)
            else if (card) closeCard()
            else void navigate({ to: '/library' })
          },
        },
      ],
      [turn, mode, contents, tuner, card, closeCard, navigate, tool, openNote],
    ),
    { capture: true, enabled: !card && !openNote },
  )

  // --- Жесты ----------------------------------------------------------------

  const touch = useRef<{ x: number; y: number } | null>(null)

  const onTouchStart = useCallback((event: React.TouchEvent) => {
    const point = event.touches[0]
    touch.current = point ? { x: point.clientX, y: point.clientY } : null
  }, [])

  const onTouchEnd = useCallback(
    (event: React.TouchEvent) => {
      const start = touch.current
      const point = event.changedTouches[0]
      touch.current = null
      if (!start || !point) return

      const dx = point.clientX - start.x
      const dy = point.clientY - start.y
      // Порог по обеим осям: горизонтальный свайп не должен спорить с
      // вертикальной прокруткой, иначе лента станет неуправляемой.
      if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.6) return
      goChapter(chapterIndex + (dx < 0 ? 1 : -1))
    },
    [chapterIndex, goChapter],
  )

  // --- Отрисовка ------------------------------------------------------------

  if (!book) {
    return (
      <WolfyCompanion mood="kind" title="Книга не найдена">
        <p style={{ color: 'var(--ink-muted)' }}>
          Возможно, её удалили с этого устройства.
        </p>
        <Link to="/library">
          <Button variant="primary">К библиотеке</Button>
        </Link>
      </WolfyCompanion>
    )
  }

  if (failure) {
    return (
      <WolfyCompanion mood="kind" title="Книга не открывается">
        <p style={{ color: 'var(--ink-muted)', maxWidth: '32rem' }}>{failure}</p>
        <Link to="/library">
          <Button variant="primary">К библиотеке</Button>
        </Link>
      </WolfyCompanion>
    )
  }

  // Прогресс книги: пройденные главы плюс доля текущей. Доля берётся из
  // состояния страницы, а не из ручки пагинатора: читать чужой ref во время
  // отрисовки значит показывать вчерашнее число.
  const progress =
    chapters > 0
      ? ((chapterIndex + page.page / Math.max(1, page.pages)) / chapters) * 100
      : 0

  const content = (
    <ChapterView
      blocks={chapter.blocks}
      marks={marks}
      onWord={openWord}
      onPhrase={openPhrase}
      onPhraseDraft={onPhraseDraft}
      dropCap
      images={images}
      mode={mode}
      anchors={anchors}
      focusMode={settings.focusMode}
      sentences={chapter.sentences}
      focusToken={pacing ? pacer.token : null}
      openAnnotation={openAnnotation}
      onAnnotationOpen={(id) => setOpenNote((current) => (current === id ? null : id))}
      onAnnotationClose={() => setOpenNote(null)}
      onAnnotationNote={saveNote}
      onAnnotationTone={setNoteTone}
      onAnnotationDelete={deleteNote}
      quick={timing.quick}
    />
  )

  const readableBooks = libraryBooks.filter((item) => !item.deleted && item.path)

  return (
    <div className={styles.screen}>
      <div className={styles.bar}>
        <button
          type="button"
          className={styles.iconButton}
          onClick={() => setContents(true)}
          aria-label="Оглавление"
          title="Оглавление"
        >
          <ContentsIcon />
        </button>

        <div className={styles.bar__title}>
          <span className={styles.bar__book}>{book.title}</span>
          <span className={styles.bar__chapter}>
            {chapter.title ?? `Глава ${chapterIndex + 1}`} · {chapterIndex + 1} из {chapters}
          </span>
        </div>

        <div className={styles.bar__spacer} />

        <button
          type="button"
          className={styles.iconButton}
          data-active={tool === 'sticker'}
          onClick={() => setTool(tool === 'sticker' ? null : 'sticker')}
          aria-label="Наклеить стикер на место"
          title="Наклеить стикер на место"
        >
          <NoteIcon />
        </button>

        <Link
          to="/library/$bookId/notes"
          params={{ bookId }}
          className={styles.iconButton}
          aria-label="Заметки к книге"
          title="Заметки к книге"
        >
          <NotesIcon />
        </Link>

        <Link
          to="/library/$bookId/words"
          params={{ bookId }}
          className={styles.iconButton}
          aria-label="Слова этой книги"
          title="Слова этой книги"
        >
          <DecksIcon />
        </Link>

        <button
          type="button"
          className={styles.iconButton}
          onClick={() => goChapter(chapterIndex - 1)}
          disabled={chapterIndex <= 0}
          aria-label="Предыдущая глава"
        >
          <BackIcon />
        </button>
        <button
          type="button"
          className={styles.iconButton}
          onClick={() => goChapter(chapterIndex + 1)}
          disabled={chapterIndex >= chapters - 1}
          aria-label="Следующая глава"
        >
          <ForwardIcon />
        </button>
        <button
          type="button"
          className={styles.iconButton}
          data-active={tuner}
          onClick={() => setTuner((open) => !open)}
          aria-label="Вид страницы"
          title="Вид страницы"
        >
          <TuneIcon />
        </button>
      </div>

      <div className={styles.progress}>
        <div className={styles.progress__bar} style={{ width: `${progress}%` }} />
      </div>

      <AttentionBar
        segment={segment}
        read={readToken}
        wordsIn={wordsIn}
        pacing={pacing}
        pacerWpm={settings.pacerWpm}
        onPace={setPacing}
        onNextSegment={() => {
          if (segment) setSegmentFrom(segment.end)
        }}
        onStopSegment={() => void session.setSegmentWords(0)}
      />

      {recent.length > 0 && (
        <div className={styles.recent}>
          <span>Вы отмечали здесь:</span>
          {recent.map((word) => (
            <span key={word} className={styles.recent__word} lang="en">
              {word}
            </span>
          ))}
          <button
            type="button"
            className={styles.recent__close}
            onClick={() => setRecent([])}
            aria-label="Скрыть напоминание"
          >
            <CloseIcon size={16} />
          </button>
        </div>
      )}

      <div
        className={styles.stage}
        data-tool={tool ?? 'none'}
        onTouchStart={onTouchStart}
        onTouchEnd={onTouchEnd}
      >
        {error ? (
          <div className={styles.column}>
            <p style={{ color: 'var(--ink-muted)' }}>{error}</p>
          </div>
        ) : mode === 'pages' ? (
          <Paginator
            handle={pager}
            resetKey={`${bookId}:${chapterIndex}`}
            duration={timing.flight}
            onPage={handlePage}
          >
            {content}
          </Paginator>
        ) : (
          <div className={styles.scroller} ref={scroller} data-reader-scroller>
            {content}
            {chapterIndex < chapters - 1 && (
              <div className={styles.column}>
                <ChapterEnd onNext={() => goChapter(chapterIndex + 1)} />
              </div>
            )}
          </div>
        )}

        <AnimatePresence>
          {tuner && (
            <Tuner
              mode={mode}
              onMode={(next) => {
                setMode(next)
                setReadingMode(next)
              }}
              onClose={() => setTuner(false)}
              quick={timing.quick}
            />
          )}
        </AnimatePresence>

        <ToolDock
          tool={tool}
          tone={pencilTone}
          onTool={(next) => {
            setTool(next)
            setOpenNote(null)
            if (next) setPhraseMark(null)
          }}
          onTone={(tone) => {
            setPencilTone(tone)
            savePencilTone(tone)
          }}
          quick={timing.quick}
        />
      </div>

      <AnimatePresence>
        {contents && (
          <Contents
            titles={chapterTitles}
            current={chapterIndex}
            bookId={bookId}
            books={readableBooks}
            onPick={goChapter}
            onClose={() => setContents(false)}
            quick={timing.quick}
          />
        )}
      </AnimatePresence>

      <WordCard target={card} onClose={closeCard} />
    </div>
  )
}

function ChapterEnd({ onNext }: { onNext: () => void }) {
  return (
    <div className={styles.chapterEnd}>
      <WolfyCompanion mood="proud" title="Глава прочитана" size={92}>
        <p className={styles.chapterEnd__note}>
          Слова из неё уже ждут в колоде книги.
        </p>
        <Button variant="primary" onClick={onNext}>
          Дальше
        </Button>
      </WolfyCompanion>
    </div>
  )
}

function Contents({
  titles,
  current,
  bookId,
  books,
  onPick,
  onClose,
  quick,
}: {
  titles: (string | null)[]
  current: number
  bookId: string
  books: LibraryBook[]
  onPick: (index: number) => void
  onClose: () => void
  quick: number
}) {
  return (
    <>
      <m.div
        className={styles.contentsScrim}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: seconds(quick) }}
        onClick={onClose}
      />
      <m.aside
        className={styles.contents}
        role="dialog"
        aria-label="Оглавление"
        initial={{ x: '-100%' }}
        animate={{ x: 0 }}
        exit={{ x: '-100%' }}
        transition={{ duration: seconds(quick) }}
      >
        <header className={styles.contents__head}>
          <ReaderIcon />
          <h2 className={styles.contents__title}>Оглавление</h2>
          <div style={{ flex: 1 }} />
          <button
            type="button"
            className={styles.iconButton}
            onClick={onClose}
            aria-label="Закрыть оглавление"
          >
            <CloseIcon />
          </button>
        </header>
        <nav className={styles.contents__quick} aria-label="Навигация по книге">
          <Link to="/library" onClick={onClose}>Все книги</Link>
          <Link to="/library/$bookId/words" params={{ bookId }} onClick={onClose}>
            Слова книги
          </Link>
        </nav>
        {books.length > 1 && (
          <div className={styles.contents__books}>
            <span className={styles.contents__label}>Перейти к книге</span>
            {books.map((book) => (
              <Link
                key={book.id}
                to="/reader/$bookId"
                params={{ bookId: book.id }}
                className={styles.contents__book}
                data-current={book.id === bookId}
                onClick={onClose}
              >
                {book.title}
              </Link>
            ))}
          </div>
        )}
        <span className={styles.contents__label}>Главы</span>
        <div className={styles.contents__list}>
          {titles.map((title, index) => (
            <button
              key={index}
              type="button"
              className={styles.contents__item}
              data-current={index === current}
              onClick={() => onPick(index)}
            >
              <span className={styles.contents__number}>{index + 1}</span>
              <span>{title ?? `Глава ${index + 1}`}</span>
            </button>
          ))}
        </div>
      </m.aside>
    </>
  )
}

/**
 * Настройка вида прямо над страницей.
 *
 * Здесь же, а не в общих настройках: кегль подбирают, глядя на текст, и
 * уходить ради этого на другой экран — значит подбирать вслепую. Значения
 * при этом те же самые, что в настройках, и синхронизируются между
 * устройствами тем же объектом.
 */
function Tuner({
  mode,
  onMode,
  onClose,
  quick,
}: {
  mode: ReadingMode
  onMode: (mode: ReadingMode) => void
  onClose: () => void
  quick: number
}) {
  const settings = useSession((state) => state.settings)
  const [measure, setMeasure] = useState(readerMeasure)
  const [font, setFont] = useState<ReaderFont>(readerFont)

  return (
    <m.div
      className={styles.tuner}
      role="dialog"
      aria-label="Вид страницы"
      initial={{ opacity: 0, y: -8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0, y: -8 }}
      transition={{ duration: seconds(quick) }}
    >
      <div className={styles.tuner__row}>
        <span className={styles.tuner__label}>Кегль</span>
        <input
          type="range"
          min={0.8}
          max={1.6}
          step={0.05}
          value={settings.fontScale}
          onChange={(event) => void session.setFontScale(Number(event.target.value))}
          style={{ flex: 1 }}
          aria-label="Размер шрифта"
        />
        <span className={styles.tuner__value}>{Math.round(settings.fontScale * 100)}%</span>
      </div>

      <div className={styles.tuner__row}>
        <span className={styles.tuner__label}>Интерлиньяж</span>
        <input
          type="range"
          min={0.9}
          max={1.5}
          step={0.05}
          value={settings.lineScale}
          onChange={(event) => void session.setLineScale(Number(event.target.value))}
          style={{ flex: 1 }}
          aria-label="Межстрочный интервал"
        />
        <span className={styles.tuner__value}>{Math.round(settings.lineScale * 100)}%</span>
      </div>

      <div className={styles.tuner__row}>
        <span className={styles.tuner__label}>Ширина колонки</span>
        <input
          type="range"
          min={54}
          max={80}
          step={2}
          value={measure}
          onChange={(event) => { const value = Number(event.target.value); setMeasure(value); setReaderMeasure(value) }}
          style={{ flex: 1 }}
          aria-label="Ширина колонки в знаках"
        />
      </div>

      <div className={styles.tuner__row}>
        <span className={styles.tuner__label}>Шрифт</span>
        <div className={styles.segmented} style={{ flex: 1 }}>
          {(['serif', 'sans'] as ReaderFont[]).map((value) => <button type="button" key={value} data-active={font === value} onClick={() => { setFont(value); setReaderFont(value) }}>{value === 'serif' ? 'Книжный' : 'Простой'}</button>)}
        </div>
      </div>

      <div className={styles.tuner__row}>
        <span className={styles.tuner__label}>Как листать</span>
        <div className={styles.segmented} style={{ flex: 1 }}>
          <button type="button" data-active={mode === 'pages'} onClick={() => onMode('pages')}>
            Страницы
          </button>
          <button type="button" data-active={mode === 'scroll'} onClick={() => onMode('scroll')}>
            Лента
          </button>
        </div>
      </div>

      <div className={styles.tuner__themes}>
        {THEMES.map((theme) => (
          <button
            key={theme.name}
            type="button"
            className={styles.themeChip}
            data-active={settings.theme === theme.name}
            onClick={() => {
              // Тема ставится сразу, до ответа ядра: смена цвета — это то,
              // что читатель ждёт мгновенно.
              applyTheme(theme.name as ThemeName)
              void session.setTheme(theme.name as ThemeName)
            }}
            title={theme.hint}
          >
            <span
              className={styles.themeChip__swatch}
              style={{ background: THEME_SWATCH[theme.name] }}
            />
            {theme.title}
          </button>
        ))}
      </div>

      <div className={styles.tuner__row}>
        <Button variant="quiet" small onClick={onClose} wide>
          Готово
        </Button>
      </div>
    </m.div>
  )
}
