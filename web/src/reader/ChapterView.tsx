/**
 * Разметка главы.
 *
 * Глава — это три-десять тысяч кликабельных токенов, и наивная реализация
 * ломается именно здесь. Правила, из которых состоит этот файл:
 *
 * 1. **Токены рисуются один раз и больше не перерисовываются.** Внутри токена
 *    нет ни состояния выделения, ни признака «уже в колоде»: абзац
 *    мемоизирован по номеру и данным блока, и React его не трогает.
 * 2. **Нажатие ловится делегированием** на всей полосе, слово определяется по
 *    `data-t`. Десять тысяч обработчиков не нужны — нужен один.
 * 3. **Подсветка рисуется отдельным слоем** — прямоугольниками из
 *    `Range.getClientRects()` поверх текста. Так найденные слова и текущее
 *    выделение не заставляют пересобирать DOM текста.
 * 4. **Виртуализация по абзацам**, а не по строкам: абзац — естественная
 *    единица, и его высота не меняется от прокрутки.
 */

import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence } from 'motion/react'

import type { Block, FocusMode, Sentence, Token } from '../core/types'
import { AnnotationPanel, type OpenAnnotation } from './annotate'
import type { Tone } from './annotations'
import { FocusCurtains, useFocusWindow } from './attention'
import styles from './reader.module.css'
import type { TokenizedBlock } from './useChapter'

/** Пустой список предложений: постоянная ссылка, чтобы не дёргать мемоизацию. */
const EMPTY_SENTENCES: Sentence[] = []

export interface TokenRange {
  start: number
  /** Полуинтервал: `end` не входит. */
  end: number
  kind: 'saved' | 'phrase' | 'mark' | 'note'
  /**
   * Краска маркера — `var(--hl-N)` из темы.
   *
   * Задаётся значением, а не классом, потому что красок десять, и десять
   * почти одинаковых правил в файле стилей — это девять шансов разойтись с
   * тем, что хранится в заметке.
   */
  tone?: string
  /** Чем отличать прямоугольники двух выделений одного цвета подряд. */
  id?: string
  /** Есть ли у пометки текст заметки: по нему ручка получает загнутый угол. */
  hasNote?: boolean
}

/**
 * Где на странице лежит открытая пометка.
 *
 * Считается там же, где прямоугольники подсветки, и пересчитывается вместе с
 * ними: панель заметки обязана ехать за строкой при прокрутке и перевёрстке,
 * иначе она отвяжется от того места, к которому относится.
 */
export interface MarkAnchor {
  left: number
  right: number
  top: number
  bottom: number
  /** Ширина колонки — по ней панель прижимается к краю, а не уезжает за него. */
  hostWidth: number
}

interface ChapterViewProps {
  blocks: TokenizedBlock[]
  /** Что подсветить: сохранённые слова и текущее выделение фразы. */
  marks: TokenRange[]
  onWord: (token: number, element: HTMLElement) => void
  onPhrase: (start: number, end: number) => void
  /**
   * Промежуточные границы выделения, пока читатель тянет карандашом.
   *
   * Приходит на каждый кадр протягивания — по нему слой подсветки красит
   * выделение ещё до отпускания, как след настоящего маркера.
   */
  onPhraseDraft?: (start: number, end: number) => void
  /** Ставить ли буквицу: только в начале главы. */
  dropCap: boolean
  /** Картинки главы: путь внутри книги → `blob:`-адрес. */
  images: Map<string, string>
  /** Режим чтения: влияет на виртуализацию и фильтрацию подсветки. */
  mode?: 'pages' | 'scroll'
  /**
   * Открытая пометка: её заметка, краска и цитата.
   *
   * Раньше на каждую заметку висел бумажный листок на поле колонки. В режиме
   * страниц он попадал в текст соседней колонки, в ленте — прижимался к краю,
   * и два листка на соседних строках наезжали друг на друга. Теперь у пометки
   * есть маленькая ручка в конце фразы, а всё остальное живёт в панели,
   * которая открывается по ней и закрывается совсем.
   */
  openAnnotation?: OpenAnnotation | null
  onAnnotationOpen?: (id: string) => void
  onAnnotationClose?: () => void
  onAnnotationNote?: (id: string, note: string) => void
  onAnnotationTone?: (id: string, tone: Tone) => void
  onAnnotationDelete?: (id: string) => void
  /**
   * Якоря полужирной основы: по числу на токен главы.
   *
   * Приходит из ядра одним массивом на главу. Пустой массив означает
   * «выключено»: проверять настройку здесь незачем, её уже проверил тот, кто
   * решал, запрашивать якоря или нет.
   */
  anchors?: Uint16Array
  /** Окно чтения: что оставить светлым. `off` — окна нет. */
  focusMode?: FocusMode
  /** Предложения главы: по ним окно понимает, где кончается единица чтения. */
  sentences?: Sentence[]
  /**
   * Токен, на который показывает ведущая строка.
   *
   * `null` — ведущая строка молчит, и окном правит указатель: человек водит
   * по странице так же, как водил бы бумажной линейкой.
   */
  focusToken?: number | null
  /** Скорость анимаций интерфейса, мс. */
  quick?: number
}

export function ChapterView({
  blocks,
  marks,
  onWord,
  onPhrase,
  onPhraseDraft,
  dropCap,
  images,
  mode = 'scroll',
  openAnnotation,
  onAnnotationOpen,
  onAnnotationClose,
  onAnnotationNote,
  onAnnotationTone,
  onAnnotationDelete,
  anchors,
  focusMode = 'off',
  sentences,
  focusToken = null,
  quick = 180,
}: ChapterViewProps) {
  const column = useRef<HTMLDivElement>(null)
  const [anchor, setAnchor] = useState<MarkAnchor | null>(null)
  const focus = useFocusWindow(column, focusMode, sentences ?? EMPTY_SENTENCES, focusToken)

  /**
   * Одно нажатие на всю главу.
   *
   * `closest` вместо сравнения `event.target`: внутри слова может оказаться
   * курсив или сноска из исходной разметки, и целью придёт вложенный элемент.
   */
  const handleClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      // Читатель, выделивший фразу, не нажимал по слову — он тянул мышью.
      const selection = window.getSelection()
      if (selection && !selection.isCollapsed) return

      const target = (event.target as HTMLElement).closest<HTMLElement>('[data-t]')
      if (!target) return
      const index = Number(target.dataset.t)
      if (Number.isNaN(index)) return
      onWord(index, target)
    },
    [onWord],
  )

  usePhraseSelection(column, onPhrase, onPhraseDraft)

  return (
    <div
      ref={column}
      className={`${styles.column} ${styles.text}`}
      onClick={handleClick}
      lang="en"
    >
      <MarkLayer
        container={column}
        marks={marks}
        blocks={blocks}
        mode={mode}
        openId={openAnnotation?.id ?? null}
        onOpen={(id) => onAnnotationOpen?.(id)}
        onAnchor={setAnchor}
      />
      <FocusCurtains window={focus ?? null} />
      <AnimatePresence>
        {openAnnotation && anchor && (
          <AnnotationPanel
            key={openAnnotation.id}
            anchor={anchor}
            annotation={openAnnotation}
            quick={quick}
            onNote={(note) => onAnnotationNote?.(openAnnotation.id, note)}
            onTone={(tone) => onAnnotationTone?.(openAnnotation.id, tone)}
            onDelete={() => onAnnotationDelete?.(openAnnotation.id)}
            onClose={() => onAnnotationClose?.()}
          />
        )}
      </AnimatePresence>
      {blocks.map((item, position) => (
        <BlockView
          key={item.index}
          item={item}
          anchors={anchors}
          dropCap={dropCap && position === firstParagraph(blocks)}
          opening={position === 0 || isOpening(blocks, position)}
          images={images}
        />
      ))}
    </div>
  )
}

/**
 * Номер абзаца, открывающего главу буквицей.
 *
 * Первый настоящий, а не просто первый. Первым текстовым блоком главы часто
 * оказывается строка вроде названия книги или пометки переводчика, и литера в
 * три строки рядом с одной строкой текста выглядит поломкой вёрстки, а не
 * открытием главы. Порог тот же, что у клиента на Compose.
 */
function firstParagraph(blocks: TokenizedBlock[]): number {
  return blocks.findIndex(
    (item) => item.block.kind === 'paragraph' && (item.block.text?.length ?? 0) >= DROP_CAP_MIN_CHARS,
  )
}

/** Примерно четыре строки набора: меньше буквица не открывает. */
const DROP_CAP_MIN_CHARS = 180

/** Абзац после заголовка или отбивки начинается без красной строки. */
function isOpening(blocks: TokenizedBlock[], position: number): boolean {
  const previous = blocks[position - 1]?.block.kind
  return previous === 'heading' || previous === 'divider' || previous === 'image'
}

/**
 * Один блок. Мемоизирован по данным: главный смысл всей конструкции в том,
 * что абзац, однажды нарисованный, больше не рисуется никогда.
 *
 * Для §21 каждый корень несёт data-first/last-token и data-block: по ним
 * прогресс и подсветка определяют видимый диапазон за O(blocks), а не
 * O(tokens). Для §20 в scroll режиме на нём включается content-visibility.
 */
const BlockView = memo(function BlockView({
  item,
  anchors,
  dropCap,
  opening,
  images,
}: {
  item: TokenizedBlock
  /*
   * Массив якорей на всю главу. Мемоизация от него не страдает: он приходит
   * из ядра один раз на главу и не пересоздаётся на перерисовках, поэтому
   * сравнение по ссылке остаётся верным.
   */
  anchors: Uint16Array | undefined
  dropCap: boolean
  opening: boolean
  images: Map<string, string>
}) {
  const { block, tokens, offset } = item
  const first = tokens.length ? offset : -1
  const last = tokens.length ? offset + tokens.length - 1 : -1
  const blockAttrs = {
    'data-block': String(item.index),
    'data-first-token': String(first),
    'data-last-token': String(last),
  } as const
  const blockClass: string = (styles as Record<string, string>).block ?? 'block'

  switch (block.kind) {
    case 'heading': {
      const level = Math.min(Math.max(block.level ?? 2, 1), 3)
      const Tag = (level === 1 ? 'h2' : level === 2 ? 'h3' : 'h4') as 'h2'
      return (
        <Tag
          className={`${styles.heading} ${styles[`heading--${level}`]} ${blockClass}`}
          {...blockAttrs}
        >
          <Tokens tokens={tokens} offset={offset} anchors={anchors} />
        </Tag>
      )
    }
    case 'quote':
      return (
        <blockquote className={`${styles.quote} ${blockClass}`} {...blockAttrs}>
          <Tokens tokens={tokens} offset={offset} anchors={anchors} />
        </blockquote>
      )
    case 'listItem':
      return (
        <p className={`${styles.listItem} ${blockClass}`} {...blockAttrs}>
          <Tokens tokens={tokens} offset={offset} anchors={anchors} />
        </p>
      )
    case 'divider':
      return (
        <div className={`${styles.divider} ${blockClass}`} aria-hidden="true" {...blockAttrs}>
          <span className={styles.divider__mark}>◆</span>
        </div>
      )
    case 'image':
      return <Illustration block={block} images={images} attrs={blockAttrs} blockClass={blockClass} />
    default:
      return (
        <p
          className={[
            styles.paragraph,
            opening ? styles['paragraph--opening'] : '',
            dropCap ? styles.dropCap : '',
            blockClass,
          ]
            .filter(Boolean)
            .join(' ')}
          {...blockAttrs}
        >
          <Tokens tokens={tokens} offset={offset} anchors={anchors} />
        </p>
      )
  }
})

/**
 * Токены абзаца.
 *
 * Слова получают собственный элемент — они цели нажатия; пробелы и знаки
 * препинания идут текстом. Так на абзац приходится не сто элементов, а
 * столько, сколько в нём слов.
 */
function Tokens({
  tokens,
  offset,
  anchors,
}: {
  tokens: Token[]
  offset: number
  anchors: Uint16Array | undefined
}) {
  const nodes: React.ReactNode[] = []
  let plain = ''

  tokens.forEach((token, index) => {
    if (token.kind === 'word') {
      if (plain) {
        nodes.push(plain)
        plain = ''
      }
      const at = offset + index
      // Ноль означает «якоря нет»: у слова из одной буквы выделять нечего,
      // и лишний элемент внутри токена ему не нужен.
      const anchor = anchors && at < anchors.length ? anchors[at]! : 0
      nodes.push(
        <span key={index} className={styles.token} data-t={at}>
          {anchor > 0 && anchor < token.text.length ? (
            <>
              <b className={styles.anchor}>{token.text.slice(0, anchor)}</b>
              {token.text.slice(anchor)}
            </>
          ) : (
            token.text
          )}
        </span>,
      )
    } else {
      plain += token.text
    }
  })

  if (plain) nodes.push(plain)
  return <>{nodes}</>
}

function Illustration({
  block,
  images,
  attrs,
  blockClass,
}: {
  block: Block
  images: Map<string, string>
  attrs: Record<string, unknown>
  blockClass: string
}) {
  const path = block.path
  const source = path ? images.get(path) : undefined
  // Всегда рендерим figure, чтобы IntersectionObserver мог найти её даже до загрузки.
  // data-image-path нужен ленивому загрузчику в ReaderScreen.
  const figureAttrs: Record<string, string> = path ? { 'data-image-path': path } : {}
  return (
    <figure className={`${styles.image} ${blockClass}`} {...attrs} {...figureAttrs}>
      {source ? (
        <img src={source} alt={block.alt ?? ''} loading="lazy" decoding="async" />
      ) : (
        // Placeholder сохраняет место и даёт наблюдателю цель; высота минимальна
        // но при появлении картинки layout обновится один раз (scroll) или батчем (pages).
        <span className={styles.image__placeholder} aria-hidden="true" />
      )}
      {block.alt && <figcaption>{block.alt}</figcaption>}
    </figure>
  )
}

/**
 * Слой подсветки.
 *
 * Прямоугольники считаются из `Range.getClientRects()` — по одному на строку,
 * потому что слово или фраза могут переноситься. Пересчёт идёт при смене
 * набора отметок и при изменении размеров полосы: кегль, тема и ширина окна
 * двигают строки, и отметки обязаны переехать вместе с ними.
 *
 * В scroll режиме на слабом устройстве сотни сохранённых слов могли вызвать
 * тяжёлый forced layout: теперь считаются только видимые + небольшой overscan,
 * отфильтрованные по block token ranges до любых querySelector/getClientRects.
 * Resize / scroll батчатся через один rAF.
 */
function MarkLayer({
  container,
  marks,
  blocks,
  mode,
  openId,
  onOpen,
  onAnchor,
}: {
  container: React.RefObject<HTMLDivElement | null>
  marks: TokenRange[]
  blocks: TokenizedBlock[]
  mode: 'pages' | 'scroll'
  openId?: string | null
  onOpen?: (id: string) => void
  onAnchor?: (anchor: MarkAnchor | null) => void
}) {
  const [rects, setRects] = useState<
    {
      key: string
      kind: string
      tone?: string
      top: number
      left: number
      width: number
      height: number
      /** Заполнен у ручки: по нему пометка открывается и закрывается. */
      id?: string
      hasNote?: boolean
    }[]
  >([])

  const frameRef = useRef<number | null>(null)
  const seenRef = useRef<Set<string>>(new Set())
  const schedule = useCallback(
    (fn: () => void) => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
      frameRef.current = requestAnimationFrame(() => {
        frameRef.current = null
        fn()
      })
    },
    [],
  )

  const recompute = useCallback(() => {
    const host = container.current
    if (!host) {
      setRects([])
      return
    }
    const base = host.getBoundingClientRect()

    // §23: в scroll режиме фильтруем marks до DOM запросов.
    let visibleMarks = marks
    if (mode === 'scroll' && blocks.length) {
      const visible = visibleTokenRange(host, blocks)
      if (visible) {
        const [firstVisible, lastVisible] = visible
        // overscan ~ 800 токенов (примерно несколько абзацев каждая сторона)
        const OVERSCAN_TOKENS = 800
        const lo = Math.max(0, firstVisible - OVERSCAN_TOKENS)
        const hi = lastVisible + OVERSCAN_TOKENS
        visibleMarks = marks.filter((m) => m.end > lo && m.start < hi)
        // Если меток очень много, а видимых мало, мы только что сэкономили сотни Range.
      }
    } else if (mode === 'pages') {
      // В режиме страниц подсвечивать нужно только текущую и соседнюю страницу.
      // Без доступа к handle pages мы консервативно оставляем всё, но это
      // отдельный лёгкий путь: страниц всего несколько десятков токенов.
      // Оставлен как есть, чтобы не ломать пагинацию пока MarkLayer не знает page.
    }

    const next: typeof rects = []
    let anchor: MarkAnchor | null = null

    for (const mark of visibleMarks) {
      const first = host.querySelector<HTMLElement>(`[data-t="${mark.start}"]`)
      const last = host.querySelector<HTMLElement>(`[data-t="${mark.end - 1}"]`)
      if (!first || !last) continue

      // content-visibility:auto может сделать offscreen блоки не рендеримыми,
      // их getClientRects вернёт пусто — такие метки просто пропускаем, они
      // пересчитаются при прокрутке.
      const range = document.createRange()
      try {
        range.setStartBefore(first)
        range.setEndAfter(last)
      } catch {
        continue
      }

      let boxes: DOMRectList
      try {
        boxes = range.getClientRects()
      } catch {
        continue
      }
      let lastBox: DOMRect | null = null
      let bounds: MarkAnchor | null = null
      Array.from(boxes).forEach((box, index) => {
        // Схлопнутые прямоугольники приходят на разрывах строк и рисуют
        // паразитные полоски в ноль пикселей шириной.
        if (box.width < 1 || box.height < 1) return
        next.push({
          key: `${mark.id ?? mark.kind}:${mark.start}:${index}`,
          kind: mark.kind,
          tone: mark.tone,
          top: box.top - base.top,
          left: box.left - base.left,
          width: box.width,
          height: box.height,
        })
        lastBox = box
        bounds = bounds
          ? {
              left: Math.min(bounds.left, box.left - base.left),
              right: Math.max(bounds.right, box.right - base.left),
              top: Math.min(bounds.top, box.top - base.top),
              bottom: Math.max(bounds.bottom, box.bottom - base.top),
              hostWidth: base.width,
            }
          : {
              left: box.left - base.left,
              right: box.right - base.left,
              top: box.top - base.top,
              bottom: box.bottom - base.top,
              hostWidth: base.width,
            }
      })

      /*
       * Ручка пометки — единственное, за что её можно взять.
       *
       * Стоит в конце фразы, размером с точку: слой подсветки не должен
       * перехватывать нажатия целиком, иначе по выделенному тексту нельзя
       * будет ни протянуть новое выделение, ни открыть карточку слова.
       */
      if (mark.id && lastBox) {
        const box: DOMRect = lastBox
        const size = 11
        const left = Math.min(box.right - base.left + 2, base.width - size - 2)
        next.push({
          key: `handle:${mark.id}`,
          kind: 'handle',
          tone: mark.tone,
          top: box.top - base.top + (box.height - size) / 2,
          left: Math.max(0, left),
          width: size,
          height: size,
          id: mark.id,
          hasNote: mark.hasNote,
        })
      }

      if (mark.id && mark.id === openId) anchor = bounds
    }

    setRects(next)
    onAnchor?.(anchor)
  }, [container, marks, blocks, mode, openId, onAnchor])

  const scheduleRecompute = useCallback(() => schedule(recompute), [schedule, recompute])

  useEffect(() => {
    // Отметки считаются после того, как браузер разложил строки: до этого
    // прямоугольники ещё не на своих местах.
    const frame = requestAnimationFrame(recompute)
    return () => cancelAnimationFrame(frame)
  }, [recompute])

  useEffect(() => {
    const host = container.current
    if (!host || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => scheduleRecompute())
    observer.observe(host)
    // Также слушаем размеры scroll-контейнера, если он есть.
    const scroller = findScrollContainer(host)
    if (scroller) observer.observe(scroller)
    return () => observer.disconnect()
  }, [container, scheduleRecompute])

  useEffect(() => {
    if (mode !== 'scroll') return
    const host = container.current
    const scroller = host ? findScrollContainer(host) : null
    if (!scroller) return
    const onScroll = () => scheduleRecompute()
    scroller.addEventListener('scroll', onScroll, { passive: true })
    return () => scroller.removeEventListener('scroll', onScroll)
  }, [container, mode, scheduleRecompute])

  useEffect(() => {
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
    }
  }, [])

  return (
    <div className={styles.marks}>
      {rects.map((rect) => {
        // Появление — только для свежих выделений: прямоугольник, который
        // уже был на экране, при повторном пересчёте не «вспыхивает» снова.
        const fresh = !seenRef.current.has(rect.key)
        seenRef.current.add(rect.key)
        const box = {
          top: rect.top,
          left: rect.left,
          width: rect.width,
          height: rect.height,
        }

        if (rect.kind === 'handle' && rect.id) {
          const id = rect.id
          return (
            <button
              key={rect.key}
              type="button"
              className={styles.markHandle}
              data-handle="true"
              data-note={rect.hasNote ? 'true' : undefined}
              data-open={id === openId ? 'true' : undefined}
              style={{ ...box, ...(rect.tone ? { background: rect.tone } : null) }}
              aria-label={rect.hasNote ? 'Открыть заметку' : 'Открыть выделение'}
              title={rect.hasNote ? 'Заметка' : 'Выделение'}
              onMouseDown={(event) => event.stopPropagation()}
              onClick={(event) => {
                event.stopPropagation()
                onOpen?.(id)
              }}
            />
          )
        }

        return (
          <span
            key={rect.key}
            aria-hidden="true"
            className={`${styles.mark} ${styles[`mark--${rect.kind}`]}${
              fresh && rect.kind === 'mark' ? ` ${styles.markPop}` : ''
            }`}
            style={{ ...box, ...(rect.tone ? { background: rect.tone } : null) }}
          />
        )
      })}
    </div>
  )
}

/**
 * Выделение фразы.
 *
 * Браузерное выделение расширяется до границ слов: читатель тянет мышью
 * приблизительно, а разбирать половину слова бессмысленно. Обычное
 * копирование текста при этом не ломается — выделение остаётся выделением,
 * просто прилипает к словам.
 */
function usePhraseSelection(
  container: React.RefObject<HTMLDivElement | null>,
  onPhrase: (start: number, end: number) => void,
  onPhraseDraft?: (start: number, end: number) => void,
) {
  useEffect(() => {
    const host = container.current
    if (!host) return

    function settle() {
      const selection = window.getSelection()
      if (!selection || selection.isCollapsed || selection.rangeCount === 0) return

      const range = selection.getRangeAt(0)
      const host = container.current
      if (!host || !host.contains(range.commonAncestorContainer)) return

      const first = tokenAt(range.startContainer, range.startOffset, host, 'start')
      const last = tokenAt(range.endContainer, range.endOffset, host, 'end')
      if (first === null || last === null) return

      const start = Math.min(first, last)
      const end = Math.max(first, last) + 1
      // Одно слово — это нажатие, а не фраза: у него своя карточка.
      if (end - start < 2) return

      // Прилипание к границам слов: выделение поправляется на месте, чтобы
      // читатель видел ровно то, что уйдёт в разбор.
      const from = host.querySelector<HTMLElement>(`[data-t="${start}"]`)
      const to = host.querySelector<HTMLElement>(`[data-t="${end - 1}"]`)
      if (from && to) {
        const snapped = document.createRange()
        snapped.setStartBefore(from)
        snapped.setEndAfter(to)
        selection.removeAllRanges()
        selection.addRange(snapped)
      }

      onPhrase(start, end)
    }

    // След карандаша: пока читатель тянет, слой подсветки обновляется на
    // каждый кадр. Отдельно от settle — тот ждёт отпускания, а это — живой
    // предпросмотр, и без него выделение появлялось бы только после руки.
    let draftFrame: number | null = null
    function draft() {
      if (!onPhraseDraft) return
      if (draftFrame !== null) return
      draftFrame = requestAnimationFrame(() => {
        draftFrame = null
        const selection = window.getSelection()
        if (!selection || selection.isCollapsed || selection.rangeCount === 0) return
        const range = selection.getRangeAt(0)
        const host = container.current
        if (!host || !host.contains(range.commonAncestorContainer)) return
        const first = tokenAt(range.startContainer, range.startOffset, host, 'start')
        const last = tokenAt(range.endContainer, range.endOffset, host, 'end')
        if (first === null || last === null) return
        const start = Math.min(first, last)
        const end = Math.max(first, last) + 1
        if (end - start < 2) return
        onPhraseDraft(start, end)
      })
    }

    // `selectionchange` срабатывает на каждый пиксель протягивания; ждать
    // отпускания дешевле и точнее — до него выделение ещё не закончено.
    document.addEventListener('mouseup', settle)
    document.addEventListener('touchend', settle)
    document.addEventListener('selectionchange', draft)
    return () => {
      document.removeEventListener('mouseup', settle)
      document.removeEventListener('touchend', settle)
      document.removeEventListener('selectionchange', draft)
      if (draftFrame !== null) cancelAnimationFrame(draftFrame)
    }
  }, [container, onPhrase, onPhraseDraft])
}

/**
 * Номер токена, к которому прилипает край выделения.
 *
 * Край, попавший внутрь слова, — это само слово. Край, попавший на пробел или
 * знак препинания между словами, ищется в нужную сторону: у начала выделения
 * вперёд, у конца — назад.
 *
 * Сравнивается именно граница `(container, offset)`, а не её родительский
 * элемент: родителем пробела оказывается весь абзац, и тогда «ближайшим»
 * словом становилось первое слово главы, а выделение фразы красило текст от
 * самого её начала. Список `[data-t]` идёт в порядке документа, поэтому
 * граница ищется делением пополам, а не перебором всех слов главы.
 */
function tokenAt(
  container: Node,
  offset: number,
  host: HTMLElement,
  edge: 'start' | 'end',
): number | null {
  const element: HTMLElement | null =
    container.nodeType === Node.TEXT_NODE
      ? container.parentElement
      : (container as HTMLElement)

  const direct = element?.closest<HTMLElement>('[data-t]')
  if (direct) return Number(direct.dataset.t)

  const all = host.querySelectorAll<HTMLElement>('[data-t]')
  if (!all.length) return null

  const point = document.createRange()
  try {
    point.setStart(container, offset)
    point.collapse(true)
  } catch {
    return null
  }

  // Первое слово, начало которого стоит не раньше границы выделения.
  let low = 0
  let high = all.length - 1
  let after = all.length
  while (low <= high) {
    const middle = (low + high) >> 1
    let side: number
    try {
      side = point.comparePoint(all[middle]!, 0)
    } catch {
      return null
    }
    if (side >= 0) {
      after = middle
      high = middle - 1
    } else {
      low = middle + 1
    }
  }

  const pick =
    edge === 'start'
      ? all[Math.min(after, all.length - 1)]
      : all[Math.max(after - 1, 0)]
  return pick ? Number(pick.dataset.t) : null
}

/**
 * Диапазоны сохранённых слов главы — то, что подсвечено прямо в тексте.
 *
 * Сверяются и начальная форма, и та словоформа, которая стояла в тексте:
 * читатель отметил «children», а через страницу встретит «child», и не
 * подсветить второе значило бы соврать, что слова в колоде нет.
 */
export function savedRanges(tokens: Token[], known: Set<string>): TokenRange[] {
  if (!known.size) return []
  const ranges: TokenRange[] = []
  tokens.forEach((token, index) => {
    if (token.kind !== 'word') return
    if (known.has(token.text.toLowerCase())) {
      ranges.push({ start: index, end: index + 1, kind: 'saved' })
    }
  })
  return ranges
}

/** Текст диапазона токенов — то, что уходит в разбор фразы. */
export function textOf(tokens: Token[], start: number, end: number, whole: string): string {
  const from = tokens[start]
  const to = tokens[end - 1]
  if (!from || !to) return ''
  return whole.slice(from.start, to.end)
}

function findScrollContainer(host: HTMLElement): HTMLElement | null {
  let cur: HTMLElement | null = host.parentElement
  while (cur) {
    // В веб-читалке скроллит именно .scroller; у остальных overflow не auto.
    const style = cur.style ? window.getComputedStyle(cur) : null
    const overflowY = style?.overflowY ?? ''
    if (
      (overflowY === 'auto' || overflowY === 'scroll' || cur.dataset.readerScroller !== undefined) &&
      cur.scrollHeight > cur.clientHeight + 4
    ) {
      return cur
    }
    // Fallback: у scroller есть характерный scrollHeight > clientHeight даже без стилей в jsdom
    if (cur.scrollHeight > cur.clientHeight + 16 && cur.clientHeight > 0) return cur
    cur = cur.parentElement
  }
  return null
}

/**
 * Видимый диапазон токенов в scroll режиме, определённый по блокам.
 *
 * Берём первый и последний видимый block по геометрии, затем расширяем до
 * их токен-интервалов. Возвращает [firstToken, lastTokenExcl) или null если
 * блоки не нашлись (тогда фильтрация отключается).
 */
function visibleTokenRange(host: HTMLElement, _blocks: TokenizedBlock[]): [number, number] | null {
  const scroller = findScrollContainer(host)
  const blockNodes = Array.from(host.querySelectorAll<HTMLElement>('[data-block]')) as HTMLElement[]
  if (!blockNodes.length) return null

  // Если есть скроллер, сравниваем rect видимости с небольшим overscan (300px)
  let scrollerRect: DOMRect | null = null
  if (scroller) scrollerRect = scroller.getBoundingClientRect()
  const OVERSCAN_PX = 400
  let firstSeen: HTMLElement | null = null
  let lastSeen: HTMLElement | null = null
  for (const node of blockNodes) {
    let visible = false
    if (scrollerRect) {
      const r = node.getBoundingClientRect()
      // content-visibility:auto может дать нулевую высоту у невидимых блоков до измерения;
      // такие считаем невидимыми
      if (r.height < 1 && r.width < 1) continue
      const top = r.top - OVERSCAN_PX
      const bottom = r.bottom + OVERSCAN_PX
      if (bottom > scrollerRect.top && top < scrollerRect.bottom) visible = true
    } else {
      // fallback без скроллера: считаем блоки видимыми если их offset внутри host viewport приближённо
      const top = (node as HTMLElement).offsetTop
      const h = (node as HTMLElement).offsetHeight || 200
      // без скроллера host — весь документ, считаем всё видимым
      visible = true
      void top
      void h
    }
    if (visible) {
      if (!firstSeen) firstSeen = node
      lastSeen = node
    }
  }
  if (!firstSeen || !lastSeen) return null
  const firstToken = Number(firstSeen.dataset.firstToken ?? -1)
  const lastToken = Number(lastSeen.dataset.lastToken ?? -1)
  if (Number.isNaN(firstToken) || Number.isNaN(lastToken) || firstToken < 0 || lastToken < 0) {
    // Блоки без токенов — пробуем найти ближайшие с токенами по порядку блоков
    return null
  }
  // lastToken включительно, возвращаем полуинтервал
  return [firstToken, lastToken + 1]
}
