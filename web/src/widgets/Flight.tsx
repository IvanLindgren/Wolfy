/**
 * Полёт слова в колоду.
 *
 * Действие обязано быть видимым. Читатель, нажавший «в колоду» и не увидевший
 * ничего, нажмёт кнопку второй раз — и решит, что приложение сломано, когда
 * во второй раз тоже ничего не произойдёт. Дуга от карточки к значку раздела
 * отвечает на вопрос «куда оно делось» раньше, чем этот вопрос успевает
 * возникнуть.
 *
 * Дуга, а не прямая: прямая линия читается как перемещение элемента, дуга —
 * как бросок. Кривая `toss` довершает: почти без разгона в начале и с резкой
 * остановкой в конце.
 */

import { AnimatePresence, motion as m } from 'motion/react'
import { create } from 'zustand'

import { curves, motion, seconds } from '../theme/motion'
import styles from './Flight.module.css'

interface FlyingWord {
  id: number
  text: string
  from: { x: number; y: number }
  to: { x: number; y: number }
}

interface FlightState {
  flying: FlyingWord[]
  /** Где сейчас значок колод: полёт целится в него. */
  target: { x: number; y: number } | null
  setTarget: (point: { x: number; y: number } | null) => void
  launch: (text: string, from: { x: number; y: number }) => void
  land: (id: number) => void
}

let counter = 0

export const useFlight = create<FlightState>((set, get) => ({
  flying: [],
  target: null,

  setTarget(point) {
    set({ target: point })
  },

  launch(text, from) {
    const target = get().target
    // Цели нет — значит, раздел колод сейчас не на экране. Полёт без цели
    // никому ничего не объяснит, и его просто не будет.
    if (!target) return
    const id = ++counter
    set((state) => ({ flying: [...state.flying, { id, text, from, to: target }] }))
  },

  land(id) {
    set((state) => ({ flying: state.flying.filter((word) => word.id !== id) }))
  },
}))

/** Запускает слово в полёт от элемента, по которому нажали. */
export function flyToDeck(text: string, origin: HTMLElement | null): void {
  if (!origin) return
  const box = origin.getBoundingClientRect()
  useFlight.getState().launch(text, {
    x: box.left + box.width / 2,
    y: box.top + box.height / 2,
  })
}

/** Слой полёта. Живёт в оболочке — поверх всего и вне любой прокрутки. */
export function FlightLayer({ quiet }: { quiet: boolean }) {
  const flying = useFlight((state) => state.flying)
  const land = useFlight((state) => state.land)

  return (
    <div className={styles.layer} aria-hidden="true">
      <AnimatePresence>
        {flying.map((word) => {
          // Вершина дуги — выше обеих точек: бросок идёт вверх и падает в
          // цель, а не проползает по экрану.
          const peakX = (word.from.x + word.to.x) / 2
          const peakY = Math.min(word.from.y, word.to.y) - 90

          return (
            <m.span
              key={word.id}
              className={styles.word}
              initial={{ x: word.from.x, y: word.from.y, opacity: 1, scale: 1 }}
              animate={{
                x: [word.from.x, peakX, word.to.x],
                y: [word.from.y, peakY, word.to.y],
                scale: [1, 1.08, 0.55],
                opacity: [1, 1, 0],
              }}
              transition={{
                duration: seconds(quiet ? 0 : motion.flight),
                ease: curves.toss,
                times: [0, 0.45, 1],
              }}
              onAnimationComplete={() => land(word.id)}
            >
              {word.text}
            </m.span>
          )
        })}
      </AnimatePresence>
    </div>
  )
}
