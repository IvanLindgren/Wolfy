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

import type { Block, Token } from '../core/types'
import { StickerLayer, type StickerInfo } from './annotate'
import styles from './reader.module.css'
import type { TokenizedBlock } from './useChapter'

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
  /** Заметки-стикеры этой главы. */
  stickers?: StickerInfo[]
  /** Какой стикер сейчас открыт на редактирование. */
  editingSticker?: string | null
  onStickerOpen?: (id: string) => void
  onStickerClose?: () => void
  onStickerSave?: (id: string, note: string) => void
  onStickerDelete?: (id: string) => void
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
  stickers,
  editingSticker,
  onStickerOpen,
  onStickerClose,
  onStickerSave,
  onStickerDelete,
  quick = 180,
}: ChapterViewProps) {
  const column = useRef<HTMLDivElement>(null)

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
      <MarkLayer container={column} marks={marks} blocks={blocks} mode={mode} />
      <StickerLayer
        container={column}
        stickers={stickers ?? []}
        editing={editingSticker ?? null}
        mode={mode}
        quick={quick}
        onOpen={(id) => onStickerOpen?.(id)}
        onClose={() => onStickerClose?.()}
        onSave={(id, note) => onStickerSave?.(id, note)}
        onDelete={(id) => onStickerDelete?.(id)}
      />
      {blocks.map((item, position) => (
        <BlockView
          key={item.index}
          item={item}
          dropCap={dropCap && position === firstParagraph(blocks)}
          opening={position === 0 || isOpening(blocks, position)}
          images={images}
        />
      ))}
    </div>
  )
}

/** Номер первого абзаца — на нём и стоит буквица. */
function firstParagraph(blocks: TokenizedBlock[]): number {
  return blocks.findIndex((item) => item.block.kind === 'paragraph')
}

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
  dropCap,
  opening,
  images,
}: {
  item: TokenizedBlock
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
          <Tokens tokens={tokens} offset={offset} />
        </Tag>
      )
    }
    case 'quote':
      return (
        <blockquote className={`${styles.quote} ${blockClass}`} {...blockAttrs}>
          <Tokens tokens={tokens} offset={offset} />
        </blockquote>
      )
    case 'listItem':
      return (
        <p className={`${styles.listItem} ${blockClass}`} {...blockAttrs}>
          <Tokens tokens={tokens} offset={offset} />
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
          <Tokens tokens={tokens} offset={offset} />
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
function Tokens({ tokens, offset }: { tokens: Token[]; offset: number }) {
  const nodes: React.ReactNode[] = []
  let plain = ''

  tokens.forEach((token, index) => {
    if (token.kind === 'word') {
      if (plain) {
        nodes.push(plain)
        plain = ''
      }
      nodes.push(
        <span key={index} className={styles.token} data-t={offset + index}>
          {token.text}
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
}: {
  container: React.RefObject<HTMLDivElement | null>
  marks: TokenRange[]
  blocks: TokenizedBlock[]
  mode: 'pages' | 'scroll'
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
      })
    }

    setRects(next)
  }, [container, marks, blocks, mode])

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
    <div className={styles.marks} aria-hidden="true">
      {rects.map((rect) => {
        // Появление — только для свежих выделений: прямоугольник, который
        // уже был на экране, при повторном пересчёте не «вспыхивает» снова.
        const fresh = !seenRef.current.has(rect.key)
        seenRef.current.add(rect.key)
        return (
          <span
            key={rect.key}
            className={`${styles.mark} ${styles[`mark--${rect.kind}`]}${
              fresh && rect.kind === 'mark' ? ` ${styles.markPop}` : ''
            }`}
            style={{
              top: rect.top,
              left: rect.left,
              width: rect.width,
              height: rect.height,
              ...(rect.tone ? { background: rect.tone } : null),
            }}
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

      const first = tokenAt(range.startContainer, host, 'start')
      const last = tokenAt(range.endContainer, host, 'end')
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
        const first = tokenAt(range.startContainer, host, 'start')
        const last = tokenAt(range.endContainer, host, 'end')
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

/** Номер токена, внутри которого стоит узел выделения. */
function tokenAt(node: Node, host: HTMLElement, edge: 'start' | 'end'): number | null {
  let element: HTMLElement | null =
    node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as HTMLElement)

  const direct = element?.closest<HTMLElement>('[data-t]')
  if (direct) return Number(direct.dataset.t)

  // Край выделения попал между словами — на пробел или знак препинания.
  // Ищем ближайшее слово в нужную сторону: у начала вперёд, у конца назад.
  const all = Array.from(host.querySelectorAll<HTMLElement>('[data-t]'))
  if (!all.length) return null

  const target = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as HTMLElement)
  if (!target) return null

  const position = all.findIndex(
    (candidate) =>
      candidate.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING,
  )
  if (position === -1) {
    return edge === 'start' ? Number(all[0]!.dataset.t) : Number(all[all.length - 1]!.dataset.t)
  }
  const pick = edge === 'start' ? all[position] : all[Math.max(position - 1, 0)]
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
