/**
 * Инструменты отметок: карандаш-маркер и заметки-стикеры.
 *
 * Два жеста, которыми читатель отмечает книгу, и ничего больше:
 *
 * - **Карандаш** — включается одной кнопкой, после чего читатель сам ведёт по
 *   тексту, как маркером по бумаге: выделение красится сразу, пока палец или
 *   мышь ещё едут, и остаётся после отпускания. Никаких карточек и полей —
 *   только жест и цвет.
 * - **Стикер** — включается второй кнопкой; следующее нажатие по слову (или
 *   готовое выделение) приклеивает к этому месту заметку-стикер, как листок
 *   на полях. Стикер открывается сам и пишется сразу; текст появляется при
 *   нажатии на него.
 *
 * Оба инструмента живут поверх текста и не спорят с обычным чтением:
 * включённый инструмент отключает открытие карточки по слову, а Esc
 * возвращает читалку в обычный режим.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion as m } from 'motion/react'

import { seconds } from '../theme/motion'
import { CheckIcon, CloseIcon, PencilIcon, StickerIcon, TrashIcon } from '../widgets/icons'
import { TONES, toneColor, type Tone } from './annotations'
import styles from './reader.module.css'

export type Tool = null | 'pencil' | 'sticker'

/** Тон карандаша по умолчанию — жёлтый; читатель перекрашивает его у док-станции. */
const TONE_KEY = 'wolfy.highlighter.tone'

export function lastPencilTone(): Tone {
  const raw = Number(localStorage.getItem(TONE_KEY))
  return raw >= 1 && raw <= 10 ? (raw as Tone) : 1
}

export function savePencilTone(tone: Tone): void {
  localStorage.setItem(TONE_KEY, String(tone))
}

/**
 * Плавающая док-станция инструментов.
 *
 * Висит над нижним краем читалки и не мешает ни тексту, ни прогрессу. Когда
 * инструмент включён, рядом выезжает его палитра или подсказка, а сам текст
 * меняет курсор — читатель видит, что сейчас «рисует», а не читает.
 */
export function ToolDock({
  tool,
  tone,
  onTool,
  onTone,
  quick,
}: {
  tool: Tool
  tone: Tone
  onTool: (tool: Tool) => void
  onTone: (tone: Tone) => void
  quick: number
}) {
  const t = seconds(quick)
  return (
    <m.div
      className={styles.dock}
      initial={{ y: 24, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      transition={{ duration: t }}
      role="toolbar"
      aria-label="Отметки"
    >
      <button
        type="button"
        className={styles.dock__tool}
        data-active={tool === 'pencil'}
        aria-pressed={tool === 'pencil'}
        aria-label="Маркер: вести по тексту"
        title="Маркер — выделяйте, ведя по тексту"
        onClick={() => onTool(tool === 'pencil' ? null : 'pencil')}
      >
        <PencilIcon size={19} />
      </button>

      <AnimatePresence initial={false}>
        {tool === 'pencil' && (
          <m.div
            className={styles.dock__tones}
            initial={{ width: 0, opacity: 0 }}
            animate={{ width: 'auto', opacity: 1 }}
            exit={{ width: 0, opacity: 0 }}
            transition={{ duration: t }}
            role="group"
            aria-label="Цвет маркера"
          >
            {TONES.map((item) => (
              <button
                key={item.tone}
                type="button"
                className={styles.dock__tone}
                data-active={tone === item.tone}
                style={{ ['--tone' as string]: toneColor(item.tone) }}
                title={item.title}
                aria-label={item.title}
                aria-pressed={tone === item.tone}
                onClick={() => onTone(item.tone)}
              />
            ))}
          </m.div>
        )}
      </AnimatePresence>

      <button
        type="button"
        className={styles.dock__tool}
        data-active={tool === 'sticker'}
        aria-pressed={tool === 'sticker'}
        aria-label="Стикер: наклеить на место"
        title="Стикер — наклейте заметку на место"
        onClick={() => onTool(tool === 'sticker' ? null : 'sticker')}
      >
        <StickerIcon size={19} />
      </button>

      <AnimatePresence>
        {tool && (
          <m.span
            className={styles.dock__hint}
            initial={{ opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: t }}
          >
            {tool === 'pencil'
              ? 'Ведите по тексту — он будет выделен'
              : 'Нажмите на слово — стикер приклеится'}
            <kbd>Esc</kbd>
          </m.span>
        )}
      </AnimatePresence>
    </m.div>
  )
}

export interface StickerInfo {
  id: string
  start: number
  end: number
  note: string
  quote: string
}

const TAB_W = 34
const TAB_H = 46

interface Anchor {
  top: number
  left: number
  /** Где привязан хвостик стикера — для выравнивания редактора. */
  tabLeft: number
  tabTop: number
}

/**
 * Слой стикеров поверх текста.
 *
 * Родственник MarkLayer: те же прямоугольники, тот же пересчёт при изменении
 * размеров и прокрутке. Стикер висит на правом поле колонки (в режиме страниц —
 * в желобе между колонками), как бумажный листок, приклеенный к строке.
 */
export function StickerLayer({
  container,
  stickers,
  editing,
  mode,
  quick,
  onOpen,
  onClose,
  onSave,
  onDelete,
}: {
  container: React.RefObject<HTMLDivElement | null>
  stickers: StickerInfo[]
  editing: string | null
  mode: 'pages' | 'scroll'
  quick: number
  onOpen: (id: string) => void
  onClose: () => void
  onSave: (id: string, note: string) => void
  onDelete: (id: string) => void
}) {
  const [placed, setPlaced] = useState<(StickerInfo & Anchor)[]>([])
  const frameRef = useRef<number | null>(null)

  const schedule = useCallback((fn: () => void) => {
    if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
    frameRef.current = requestAnimationFrame(() => {
      frameRef.current = null
      fn()
    })
  }, [])

  const recompute = useCallback(() => {
    const host = container.current
    if (!host) {
      setPlaced([])
      return
    }
    const base = host.getBoundingClientRect()
    const next: (StickerInfo & Anchor)[] = []
    for (const sticker of stickers) {
      const last = host.querySelector<HTMLElement>(`[data-t="${Math.max(sticker.start, sticker.end - 1)}"]`)
      if (!last) continue
      const rect = last.getBoundingClientRect()
      if (rect.width < 1 && rect.height < 1) continue
      // В ленте — правое поле колонки; в страницах — желоб между колонками.
      const left =
        mode === 'scroll'
          ? base.width - TAB_W - 10
          : Math.min(rect.right + 8, base.width - TAB_W - 4)
      const top = Math.max(4, rect.top - base.top - 6)
      next.push({
        ...sticker,
        top,
        left,
        tabLeft: left,
        tabTop: top,
      })
    }
    setPlaced(next)
  }, [container, stickers, mode])

  useEffect(() => {
    const frame = requestAnimationFrame(recompute)
    return () => cancelAnimationFrame(frame)
  }, [recompute])

  useEffect(() => {
    const host = container.current
    if (!host || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => schedule(recompute))
    observer.observe(host)
    return () => observer.disconnect()
  }, [container, schedule, recompute])

  useEffect(() => {
    if (mode !== 'scroll') return
    const host = container.current
    if (!host) return
    let scroller = host.parentElement
    while (scroller && !(scroller.scrollHeight > scroller.clientHeight + 4 && scroller.clientHeight > 0)) {
      scroller = scroller.parentElement
    }
    if (!scroller) return
    const onScroll = () => schedule(recompute)
    scroller.addEventListener('scroll', onScroll, { passive: true })
    return () => scroller.removeEventListener('scroll', onScroll)
  }, [container, mode, schedule, recompute])

  useEffect(
    () => () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
    },
    [],
  )

  const t = seconds(quick)

  return (
    <div className={styles.stickers} aria-hidden={placed.length === 0}>
      {placed.map((sticker) => (
        <m.button
          key={sticker.id}
          type="button"
          className={styles.stickerTab}
          data-empty={sticker.note === ''}
          initial={{ scale: 0.4, rotate: -16, opacity: 0 }}
          animate={{ scale: 1, rotate: -2, opacity: 1 }}
          transition={{ type: 'spring', stiffness: 420, damping: 24 }}
          style={{ top: sticker.top, left: sticker.left, width: TAB_W, height: TAB_H }}
          aria-label={sticker.note ? `Заметка: ${sticker.note}` : 'Пустая заметка-стикер'}
          title={sticker.note || 'Пустой стикер — нажмите, чтобы написать'}
          onClick={() => (editing === sticker.id ? onClose() : onOpen(sticker.id))}
        />
      ))}

      <AnimatePresence>
        {editing &&
          (() => {
            const sticker = placed.find((item) => item.id === editing)
            if (!sticker) return null
            return (
              <StickerEditor
                key={sticker.id}
                sticker={sticker}
                quick={t}
                onClose={onClose}
                onSave={(note) => onSave(sticker.id, note)}
                onDelete={() => onDelete(sticker.id)}
              />
            )
          })()}
      </AnimatePresence>
    </div>
  )
}

/**
 * Редактор стикера.
 *
 * Открывается рядом со стикером и пишется сразу — автокурсор стоит в поле.
 * Сохранение по потере фокуса или по Enter; пустой текст просто закрывает
 * редактор, не стирая сам стикер (стикер убирается только корзиной).
 */
function StickerEditor({
  sticker,
  quick,
  onClose,
  onSave,
  onDelete,
}: {
  sticker: StickerInfo & Anchor
  quick: number
  onClose: () => void
  onSave: (note: string) => void
  onDelete: () => void
}) {
  const [draft, setDraft] = useState(sticker.note)
  const [left, setLeft] = useState(() => Math.max(8, sticker.left - 236))

  useEffect(() => setDraft(sticker.note), [sticker.note])
  useEffect(() => setLeft(Math.max(8, sticker.left - 236)), [sticker.left])

  const save = useCallback(() => {
    const text = draft.trim()
    if (text !== sticker.note.trim()) onSave(text)
    onClose()
  }, [draft, sticker.note, onSave, onClose])

  return (
    <m.div
      className={styles.stickerEditor}
      initial={{ scale: 0.6, opacity: 0, y: 8 }}
      animate={{ scale: 1, opacity: 1, y: 0 }}
      exit={{ scale: 0.6, opacity: 0 }}
      transition={{ type: 'spring', stiffness: 380, damping: 26, duration: quick }}
      style={{ top: sticker.top, left }}
      role="dialog"
      aria-label="Заметка-стикер"
    >
      {sticker.quote && (
        <blockquote className={styles.stickerEditor__quote} lang="en">
          {sticker.quote}
        </blockquote>
      )}
      <textarea
        className={styles.stickerEditor__input}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={save}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault()
            save()
          }
          if (event.key === 'Escape') {
            event.preventDefault()
            onClose()
          }
        }}
        placeholder="Что вы об этом думаете"
        aria-label="Текст заметки"
        rows={4}
        autoFocus
      />
      <div className={styles.stickerEditor__row}>
        <button
          type="button"
          className={styles.stickerEditor__delete}
          onMouseDown={(event) => event.preventDefault()}
          onClick={onDelete}
          aria-label="Снять стикер"
          title="Снять стикер"
        >
          <TrashIcon size={15} />
        </button>
        <button
          type="button"
          className={styles.stickerEditor__done}
          onMouseDown={(event) => event.preventDefault()}
          onClick={save}
          aria-label="Готово"
          title="Готово"
        >
          <CheckIcon size={15} />
        </button>
        <button
          type="button"
          className={styles.stickerEditor__close}
          onClick={onClose}
          aria-label="Закрыть без сохранения"
          title="Закрыть"
        >
          <CloseIcon size={14} />
        </button>
      </div>
    </m.div>
  )
}
