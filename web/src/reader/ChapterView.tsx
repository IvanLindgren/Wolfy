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
  /** Ставить ли буквицу: только в начале главы. */
  dropCap: boolean
  /** Картинки главы: путь внутри книги → `blob:`-адрес. */
  images: Map<string, string>
}

export function ChapterView({
  blocks,
  marks,
  onWord,
  onPhrase,
  dropCap,
  images,
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

  usePhraseSelection(column, onPhrase)

  return (
    <div
      ref={column}
      className={`${styles.column} ${styles.text}`}
      onClick={handleClick}
      lang="en"
    >
      <MarkLayer container={column} marks={marks} />
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

  switch (block.kind) {
    case 'heading': {
      const level = Math.min(Math.max(block.level ?? 2, 1), 3)
      const Tag = (level === 1 ? 'h2' : level === 2 ? 'h3' : 'h4') as 'h2'
      return (
        <Tag className={`${styles.heading} ${styles[`heading--${level}`]}`}>
          <Tokens tokens={tokens} offset={offset} />
        </Tag>
      )
    }
    case 'quote':
      return (
        <blockquote className={styles.quote}>
          <Tokens tokens={tokens} offset={offset} />
        </blockquote>
      )
    case 'listItem':
      return (
        <p className={styles.listItem}>
          <Tokens tokens={tokens} offset={offset} />
        </p>
      )
    case 'divider':
      return (
        <div className={styles.divider} aria-hidden="true">
          <span className={styles.divider__mark}>◆</span>
        </div>
      )
    case 'image':
      return <Illustration block={block} images={images} />
    default:
      return (
        <p
          className={[
            styles.paragraph,
            opening ? styles['paragraph--opening'] : '',
            dropCap ? styles.dropCap : '',
          ]
            .filter(Boolean)
            .join(' ')}
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

function Illustration({ block, images }: { block: Block; images: Map<string, string> }) {
  const source = block.path ? images.get(block.path) : undefined
  if (!source) return null
  return (
    <figure className={styles.image}>
      <img src={source} alt={block.alt ?? ''} loading="lazy" />
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
 */
function MarkLayer({
  container,
  marks,
}: {
  container: React.RefObject<HTMLDivElement | null>
  marks: TokenRange[]
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

  const recompute = useCallback(() => {
    const host = container.current
    if (!host) {
      setRects([])
      return
    }
    const base = host.getBoundingClientRect()
    const next: typeof rects = []

    for (const mark of marks) {
      const first = host.querySelector<HTMLElement>(`[data-t="${mark.start}"]`)
      const last = host.querySelector<HTMLElement>(`[data-t="${mark.end - 1}"]`)
      if (!first || !last) continue

      const range = document.createRange()
      range.setStartBefore(first)
      range.setEndAfter(last)

      Array.from(range.getClientRects()).forEach((box, index) => {
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
  }, [container, marks])

  useEffect(() => {
    // Отметки считаются после того, как браузер разложил строки: до этого
    // прямоугольники ещё не на своих местах.
    const frame = requestAnimationFrame(recompute)
    return () => cancelAnimationFrame(frame)
  }, [recompute])

  useEffect(() => {
    const host = container.current
    if (!host || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => recompute())
    observer.observe(host)
    return () => observer.disconnect()
  }, [container, recompute])

  return (
    <div className={styles.marks} aria-hidden="true">
      {rects.map((rect) => (
        <span
          key={rect.key}
          className={`${styles.mark} ${styles[`mark--${rect.kind}`]}`}
          style={{
            top: rect.top,
            left: rect.left,
            width: rect.width,
            height: rect.height,
            ...(rect.tone ? { background: rect.tone } : null),
          }}
        />
      ))}
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

    // `selectionchange` срабатывает на каждый пиксель протягивания; ждать
    // отпускания дешевле и точнее — до него выделение ещё не закончено.
    document.addEventListener('mouseup', settle)
    document.addEventListener('touchend', settle)
    return () => {
      document.removeEventListener('mouseup', settle)
      document.removeEventListener('touchend', settle)
    }
  }, [container, onPhrase])
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
