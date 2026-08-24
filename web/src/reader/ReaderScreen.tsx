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
import { Link, useNavigate, useParams } from '@tanstack/react-router'

import { useShortcuts } from '../app/shortcuts'
import { THEMES, applyTheme, motionFor } from '../app/theme'
import { WordCard, type CardTarget } from '../card/WordCard'
import * as bridge from '../core/bridge'
import { session, useSession } from '../core/session'
import type { LibraryBook, ThemeName } from '../core/types'
import { seconds } from '../theme/motion'
import { Button } from '../widgets/Button'
import {
  BackIcon,
  CloseIcon,
  ContentsIcon,
  DecksIcon,
  ForwardIcon,
  ReaderIcon,
  TuneIcon,
} from '../widgets/icons'
import { WolfyCompanion } from '../widgets/Wolfy'
import { ChapterView, savedRanges, textOf, type TokenRange } from './ChapterView'
import { Paginator, type PagerHandle } from './Paginator'
import { readerFont, readerMeasure, readingMode, setReaderFont, setReaderMeasure, setReadingMode, type ReaderFont, type ReadingMode } from './preferences'
import styles from './reader.module.css'
import { sentenceAt, useChapter } from './useChapter'

/** Как часто записывать место в книге. */
const PROGRESS_DELAY = 900

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

  const scroller = useRef<HTMLDivElement>(null)
  const pager = useRef<PagerHandle | null>(null)
  const restored = useRef(false)
  const savedAt = useRef(0)

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
    let alive = true
    void bridge
      .openBook(book.id, book.path, book.title)
      .then((info) => {
        if (!alive) return
        setChapterTitles(info.chapters.map((item) => item.title))
        setChapterIndex(Math.min(book.progress.chapter, Math.max(0, info.chapters.length - 1)))
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

  useEffect(() => {
    const paths = chapter.blocks
      .map((item) => item.block)
      .filter((block) => block.kind === 'image' && block.path)
      .map((block) => block.path!)
    if (!paths.length) {
      setImages(new Map())
      return
    }

    let alive = true
    const urls: string[] = []
    void Promise.all(
      paths.map(async (path) => {
        const bytes = await bridge.resource(bookId, path)
        if (!bytes) return null
        const url = URL.createObjectURL(new Blob([bytes as BlobPart]))
        urls.push(url)
        return [path, url] as const
      }),
    ).then((pairs) => {
      if (!alive) {
        urls.forEach((url) => URL.revokeObjectURL(url))
        return
      }
      setImages(new Map(pairs.filter((pair): pair is [string, string] => !!pair)))
    })

    return () => {
      alive = false
      urls.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [chapter.blocks, bookId])

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

  const [phraseMark, setPhraseMark] = useState<TokenRange | null>(null)

  const marks = useMemo<TokenRange[]>(() => {
    const list = savedRanges(chapter.tokens, known)
    return phraseMark ? [...list, phraseMark] : list
  }, [chapter.tokens, known, phraseMark])

  // --- Место в книге --------------------------------------------------------

  const currentToken = useCallback((): number => {
    if (mode === 'pages') return pager.current?.firstToken() ?? 0
    const host = scroller.current
    if (!host) return 0
    const edge = host.scrollTop + 8
    const all = host.querySelectorAll<HTMLElement>('[data-t]')
    for (const element of all) {
      if (element.offsetTop >= edge) return Number(element.dataset.t)
    }
    return 0
  }, [mode])

  const remember = useCallback(() => {
    if (!book || !chapter.tokens.length) return
    const token = currentToken()
    const fraction = Math.min(1, Math.max(0, token / chapter.tokens.length))
    const now = Date.now()
    if (now - savedAt.current < PROGRESS_DELAY) return
    savedAt.current = now
    void session.rememberProgress(book.id, Math.max(0, chapterIndex), fraction)
  }, [book, chapter.tokens.length, chapterIndex, currentToken])

  // Место записывается по таймеру, а не на каждый кадр прокрутки: разница
  // между «раз в секунду» и «десять раз в секунду» для читателя нулевая, а
  // для главного потока — заметная.
  useEffect(() => {
    const timer = window.setInterval(remember, PROGRESS_DELAY)
    return () => {
      window.clearInterval(timer)
      // При уходе с экрана место записывается немедленно: иначе последняя
      // страница пропадёт.
      savedAt.current = 0
      remember()
    }
  }, [remember])

  // Возврат ровно туда, где закрыли: доля главы превращается обратно в токен.
  useEffect(() => {
    if (!book || restored.current || !chapter.tokens.length) return
    restored.current = true

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

  // --- Переходы -------------------------------------------------------------

  const chapters = chapterTitles.length || book?.chapters || 0

  const goChapter = useCallback(
    (index: number) => {
      if (index < 0 || index >= chapters) return
      restored.current = true
      setChapterIndex(index)
      setContents(false)
      scroller.current?.scrollTo({ top: 0 })
    },
    [chapters],
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

  const openWord = useCallback(
    (token: number, element: HTMLElement) => {
      const word = chapter.tokens[token]
      if (!word || !book) return
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
        origin: element,
      })
    },
    [chapter, book],
  )

  const openPhrase = useCallback(
    (start: number, end: number) => {
      if (!book) return
      const text = textOf(chapter.tokens, start, end, chapter.text)
      if (!text.trim()) return
      setPhraseMark({ start, end, kind: 'phrase' })
      setCard({
        kind: 'phrase',
        bookId: book.id,
        surface: '',
        sentence: text,
        tokens: chapter.tokens.slice(start, end),
        offset: start,
        origin: null,
      })
    },
    [book, chapter.tokens, chapter.text],
  )

  const closeCard = useCallback(() => {
    setCard(null)
    setPhraseMark(null)
    window.getSelection()?.removeAllRanges()
  }, [])

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
          // Лестница назад: оглавление → карточка → книга.
          key: 'Escape',
          run: () => {
            if (contents) setContents(false)
            else if (tuner) setTuner(false)
            else if (card) closeCard()
            else void navigate({ to: '/library' })
          },
        },
      ],
      [turn, mode, contents, tuner, card, closeCard, navigate],
    ),
    { capture: true, enabled: !card },
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
      dropCap
      images={images}
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

      <div className={styles.stage} onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
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
          <div className={styles.scroller} ref={scroller}>
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
