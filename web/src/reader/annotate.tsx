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
import { CheckIcon, PencilIcon, StickerIcon, TrashIcon } from '../widgets/icons'
import { TONES, toneColor, type Tone } from './annotations'
import type { MarkAnchor } from './ChapterView'
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

/** Пометка, открытая на правку: то, что показывает панель. */
export interface OpenAnnotation {
  id: string
  quote: string
  note: string
  tone: Tone | null
}

/**
 * Панель пометки.
 *
 * Одно место, где с выделением можно сделать всё: дописать заметку, сменить
 * краску, снять совсем. Раньше заметка жила бумажным листком на поле колонки,
 * а снять выделение из книги было нельзя вовсе — только уйдя на страницу
 * заметок. Листок при этом попадал то в соседнюю колонку, то под другой
 * листок, и разбирал страницу на части.
 *
 * Панель встаёт под пометкой и уезжает вместе с ней при прокрутке: якорь
 * пересчитывается тем же слоем, что рисует подсветку.
 */
export function AnnotationPanel({
  anchor,
  annotation,
  quick,
  onNote,
  onTone,
  onDelete,
  onClose,
}: {
  anchor: MarkAnchor
  annotation: OpenAnnotation
  quick: number
  onNote: (note: string) => void
  onTone: (tone: Tone) => void
  onDelete: () => void
  onClose: () => void
}) {
  const [draft, setDraft] = useState(annotation.note)
  const box = useRef<HTMLDivElement>(null)

  useEffect(() => setDraft(annotation.note), [annotation.note])

  const save = useCallback(() => {
    const text = draft.trim()
    if (text !== annotation.note.trim()) onNote(text)
  }, [draft, annotation.note, onNote])

  /*
   * Нажатие мимо панели закрывает её и сохраняет написанное.
   *
   * Именно закрывает, а не «отменяет»: читатель, который дописал мысль и
   * вернулся к тексту, ждёт, что мысль осталась. Потерять её потому, что
   * кнопку «готово» не нажали, — худшее, что панель может сделать.
   */
  useEffect(() => {
    const away = (event: PointerEvent) => {
      const host = box.current
      if (!host) return
      const target = event.target as Node | null
      if (target && host.contains(target)) return
      // Нажатие по ручке пометки разбирает она сама: иначе панель закрылась бы
      // здесь и тут же открылась обратно, и повторное нажатие не закрывало бы
      // ничего.
      if (target instanceof Element && target.closest('[data-handle]')) return
      save()
      onClose()
    }
    document.addEventListener('pointerdown', away, true)
    return () => document.removeEventListener('pointerdown', away, true)
  }, [save, onClose])

  // Панель шире колонки не бывает, а к её краю прижимается — иначе на узком
  // экране половина уезжает за поле и до кнопок не дотянуться.
  const width = Math.min(PANEL_W, Math.max(200, anchor.hostWidth - 16))
  const left = Math.max(8, Math.min(anchor.left, anchor.hostWidth - width - 8))

  return (
    <m.div
      ref={box}
      className={styles.notePanel}
      initial={{ opacity: 0, y: -6, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0, scale: 0.98 }}
      transition={{ type: 'spring', stiffness: 380, damping: 28, duration: quick }}
      style={{ top: anchor.bottom + 8, left, width }}
      role="dialog"
      aria-label="Пометка"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          save()
          onClose()
        }
      }}
    >
      {annotation.quote && (
        <blockquote className={styles.notePanel__quote} lang="en">
          {annotation.quote}
        </blockquote>
      )}

      <textarea
        className={styles.notePanel__input}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={save}
        placeholder="Что вы об этом думаете"
        aria-label="Текст заметки"
        rows={3}
        autoFocus
      />

      {/*
       * Краски прямо здесь: сменить цвет уже поставленного выделения иначе
       * было нельзя — только снять и провести заново.
       */}
      <div className={styles.notePanel__tones} role="group" aria-label="Краска">
        {TONES.map((item) => (
          <button
            key={item.tone}
            type="button"
            className={styles.notePanel__tone}
            style={{ background: toneColor(item.tone) }}
            data-active={annotation.tone === item.tone ? 'true' : undefined}
            aria-label={item.title}
            aria-pressed={annotation.tone === item.tone}
            title={item.title}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onTone(item.tone)}
          />
        ))}
      </div>

      <div className={styles.notePanel__row}>
        <button
          type="button"
          className={styles.notePanel__delete}
          onMouseDown={(event) => event.preventDefault()}
          onClick={onDelete}
        >
          <TrashIcon size={15} />
          Удалить
        </button>
        <button
          type="button"
          className={styles.notePanel__done}
          onMouseDown={(event) => event.preventDefault()}
          onClick={() => {
            save()
            onClose()
          }}
          aria-label="Готово"
          title="Готово"
        >
          <CheckIcon size={15} />
        </button>
      </div>
    </m.div>
  )
}

const PANEL_W = 268
