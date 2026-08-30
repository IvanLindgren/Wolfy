/**
 * Помощь вниманию: окно чтения, ведущая строка и отрезок.
 *
 * Три приёма, которые решают три разные трудности, и ни один из них не
 * включён по умолчанию — навязанная помощь мешает тем, кому она не нужна.
 *
 * **Окно чтения.** Бумажная линейка с прорезью, только своя: всё, кроме
 * текущего предложения (или абзаца), притушено. Глаз перестаёт соскальзывать
 * на соседнюю строку и перечитывать её заново. Окно ведёт указатель — как
 * настоящую линейку водят пальцем, — а на телефоне касание.
 *
 * **Ведущая строка.** Окно едет само, со скоростью, которую задал читатель.
 * Смысл не в скорости: смысл в том, что решение «читать дальше» больше не
 * нужно принимать на каждой строке. Пауза по любому касанию — темп не должен
 * превращаться в гонку.
 *
 * **Отрезок.** У открытой книги нет видимого конца, и это отдельная трудность.
 * Отрезок его назначает: докуда читаем сейчас, сколько осталось, и честная
 * остановка в конце. Границу считает ядро — она обязана совпадать с телефоном.
 *
 * Притушено здесь шторками, а не прозрачностью текста и не маской: шторка —
 * обычный прямоугольник цвета бумаги поверх строк, она не трогает раскладку,
 * не создаёт слоёв композитора на весь текст главы и одинаково работает во
 * всех браузерах, включая те, где маска на прокручиваемом блоке подтормаживает.
 */

import { useCallback, useEffect, useRef, useState } from 'react'

import type { FocusMode, ReadingSegment, Sentence } from '../core/types'
import styles from './reader.module.css'

/** Где сейчас прорезь линейки, в пикселях от верха колонки. */
export interface FocusWindow {
  top: number
  bottom: number
}

/**
 * Ведёт окно чтения за указателем или за ведущей строкой.
 *
 * `driven` — токен, на который показывает ведущая строка. Пока она молчит
 * (`null`), окном правит указатель: это ровно то, как человек водит по
 * странице пальцем, и отбирать у него эту возможность ради автоматики
 * незачем.
 */
export function useFocusWindow(
  column: React.RefObject<HTMLDivElement | null>,
  mode: FocusMode,
  sentences: Sentence[],
  driven: number | null,
): FocusWindow | null {
  const [window_, setWindow] = useState<FocusWindow | null>(null)
  const frame = useRef<number | null>(null)

  // Ведущая строка рисует своё окно и при выключенном окне чтения.
  //
  // Раньше не рисовала: при mode === 'off' окно не считалось вовсе, и ведущая
  // строка честно отсчитывала темп и прокручивала главу, не подсвечивая ни
  // одной фразы. Со стороны настройка выглядела сломанной, а на деле молча
  // зависела от соседней. Ведущая строка — это и есть подсвеченная фраза;
  // без неё она не ведёт, а просто листает.
  const effective: FocusMode = mode === 'off' && driven !== null ? 'sentence' : mode

  /** Прямоугольник единицы чтения, внутри которой стоит токен. */
  const measure = useCallback(
    (token: number): FocusWindow | null => {
      const host = column.current
      if (!host) return null
      const base = host.getBoundingClientRect()

      if (effective === 'paragraph') {
        const element = host.querySelector<HTMLElement>(`[data-t="${token}"]`)
        const block = element?.closest<HTMLElement>('[data-block]')
        if (!block) return null
        const box = block.getBoundingClientRect()
        return { top: box.top - base.top, bottom: box.bottom - base.top }
      }

      const sentence = sentences.find(
        (item) => token >= item.firstToken && token < item.lastToken,
      )
      if (!sentence) return null

      const first = host.querySelector<HTMLElement>(`[data-t="${sentence.firstToken}"]`)
      const last = host.querySelector<HTMLElement>(`[data-t="${sentence.lastToken - 1}"]`)
      if (!first || !last) return null

      const range = document.createRange()
      try {
        range.setStartBefore(first)
        range.setEndAfter(last)
      } catch {
        return null
      }
      const box = range.getBoundingClientRect()
      if (box.height < 1) return null
      return { top: box.top - base.top, bottom: box.bottom - base.top }
    },
    [column, effective, sentences],
  )

  const schedule = useCallback(
    (token: number) => {
      if (frame.current !== null) return
      frame.current = requestAnimationFrame(() => {
        frame.current = null
        const next = measure(token)
        if (next) setWindow(next)
      })
    },
    [measure],
  )

  // Ведущая строка правит окном напрямую: у неё есть номер токена, и искать
  // его под указателем незачем.
  useEffect(() => {
    if (effective === 'off' || driven === null) return
    schedule(driven)
  }, [effective, driven, schedule])

  // Указатель ведёт окно, только пока молчит ведущая строка.
  useEffect(() => {
    const host = column.current
    if (!host || effective === 'off' || driven !== null) return

    const follow = (event: PointerEvent) => {
      const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-t]')
      if (!target) return
      const token = Number(target.dataset.t)
      if (Number.isNaN(token)) return
      schedule(token)
    }

    host.addEventListener('pointermove', follow, { passive: true })
    host.addEventListener('pointerdown', follow, { passive: true })
    return () => {
      host.removeEventListener('pointermove', follow)
      host.removeEventListener('pointerdown', follow)
    }
  }, [column, effective, driven, schedule])

  useEffect(() => {
    if (effective === 'off') setWindow(null)
  }, [effective])

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current)
    },
    [],
  )

  return effective === 'off' ? null : window_
}

/**
 * Шторки вокруг окна чтения.
 *
 * Две штуки, сверху и снизу, цвета бумаги. Края растушёваны: резкая граница
 * сама притягивает взгляд и работает против того, ради чего окно заведено.
 */
export function FocusCurtains({ window: view }: { window: FocusWindow | null }) {
  if (!view) return null
  return (
    <div className={styles.focus} aria-hidden="true">
      <div className={styles.focus__curtain} style={{ top: 0, height: Math.max(0, view.top) }} />
      <div
        className={styles.focus__curtain}
        data-side="bottom"
        style={{ top: view.bottom, bottom: 0 }}
      />
    </div>
  )
}

/**
 * Ведущая строка: сама переходит к следующему предложению.
 *
 * Пауза — состояние по умолчанию: строка едет только пока читатель этого
 * хочет, и любое его вмешательство её останавливает.
 *
 * Время предложения считается по числу слов в нём, а не по буквам и не по
 * фиксированному шагу: «Yes.» и придаточное на сорок слов — разное время,
 * и равный шаг сделал бы одно ожиданием, а другое гонкой.
 */
export function usePacer(
  sentences: Sentence[],
  wordsIn: (from: number, to: number) => number,
  wpm: number,
  running: boolean,
  from: number,
): { token: number | null; done: boolean; reset: (at: number) => void } {
  const [at, setAt] = useState(() => sentenceIndexAt(sentences, from))
  const [done, setDone] = useState(false)

  const reset = useCallback(
    (token: number) => {
      setAt(sentenceIndexAt(sentences, token))
      setDone(false)
    },
    [sentences],
  )

  // Смена главы: предложения приехали новые, а место осталось от старой.
  useEffect(() => {
    setAt(sentenceIndexAt(sentences, from))
    setDone(false)
    // Пересчитывать на каждое изменение `from` нельзя: читатель, ведущий
    // окно рукой, сбрасывал бы этим ведущую строку на каждое движение.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sentences])

  useEffect(() => {
    if (!running || wpm <= 0 || at < 0 || at >= sentences.length) return

    const sentence = sentences[at]!
    const words = Math.max(1, wordsIn(sentence.firstToken, sentence.lastToken))
    // Нижний предел в полсекунды: на «Yes.» окно иначе моргает быстрее, чем
    // глаз успевает его заметить.
    const delay = Math.max(500, (words / wpm) * 60_000)

    const timer = setTimeout(() => {
      if (at + 1 >= sentences.length) setDone(true)
      else setAt(at + 1)
    }, delay)
    return () => clearTimeout(timer)
  }, [running, wpm, at, sentences, wordsIn])

  const sentence = at >= 0 && at < sentences.length ? sentences[at] : undefined
  return { token: sentence ? sentence.firstToken : null, done, reset }
}

function sentenceIndexAt(sentences: Sentence[], token: number): number {
  const found = sentences.findIndex(
    (item) => token >= item.firstToken && token < item.lastToken,
  )
  if (found >= 0) return found
  return sentences.length > 0 ? 0 : -1
}

/**
 * Полоса помощи вниманию: отрезок и ведущая строка.
 *
 * Появляется, только если хотя бы одно из двух включено, и занимает одну
 * строку: панель управления вниманием, которая сама требует внимания, — это
 * ровно то, чего здесь быть не должно.
 */
export function AttentionBar({
  segment,
  read,
  wordsIn,
  pacing,
  pacerWpm,
  onPace,
  onNextSegment,
  onStopSegment,
}: {
  segment: ReadingSegment | null
  /** Токен, до которого читатель дошёл. */
  read: number
  wordsIn: (from: number, to: number) => number
  pacing: boolean
  pacerWpm: number
  onPace: (on: boolean) => void
  onNextSegment: () => void
  onStopSegment: () => void
}) {
  if (!segment && pacerWpm <= 0) return null

  const done = segment ? read >= segment.end : false
  const passed = segment ? wordsIn(segment.start, Math.min(read, segment.end)) : 0
  const share = segment && segment.words > 0 ? Math.min(1, passed / segment.words) : 0

  return (
    <div className={styles.attention} role="status">
      {segment && !done && (
        <>
          <span className={styles.attention__label}>Подход</span>
          <span className={styles.attention__track} aria-hidden="true">
            <span className={styles.attention__fill} style={{ width: `${share * 100}%` }} />
          </span>
          <span className={styles.attention__count}>
            {passed} из {segment.words} слов
          </span>
        </>
      )}

      {segment && done && (
        <>
          <span className={styles.attention__done}>
            {segment.last ? 'Глава дочитана' : 'Отрезок пройден'}
          </span>
          {!segment.last && (
            <button type="button" className={styles.attention__action} onClick={onNextSegment}>
              Ещё один
            </button>
          )}
          <button type="button" className={styles.attention__quiet} onClick={onStopSegment}>
            На сегодня хватит
          </button>
        </>
      )}

      {pacerWpm > 0 && (
        <button
          type="button"
          className={styles.attention__pace}
          data-active={pacing}
          onClick={() => onPace(!pacing)}
          aria-pressed={pacing}
          title={pacing ? 'Остановить ведущую строку' : `Вести строку, ${pacerWpm} слов в минуту`}
        >
          {pacing ? 'Пауза' : `Вести · ${pacerWpm}`}
        </button>
      )}
    </div>
  )
}
