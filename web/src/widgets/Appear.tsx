/**
 * Появление списка каскадом.
 *
 * Fade-in с микро-сдвигом вверх, по элементу с задержкой `stagger`. Правило
 * для всех анимаций приложения соблюдается и здесь: **движение не задерживает
 * содержимое**. Элемент уже в разметке и уже занимает своё место — меняются
 * только прозрачность и полтора десятка пикселей смещения. Читатель,
 * попросивший тишины, видит готовый список мгновенно, и ничего при этом не
 * ломается.
 */

import { motion as m } from 'motion/react'
import type { ReactNode } from 'react'

import { useSession } from '../core/session'
import { motionFor } from '../app/theme'
import { curves, seconds } from '../theme/motion'

interface AppearProps {
  children: ReactNode
  /** Место в каскаде. Задержка — `index × stagger`, но не больше четверти секунды. */
  index?: number
  className?: string
  as?: 'div' | 'li' | 'article' | 'section'
}

/** Дольше этого каскад превращается в ожидание. */
const MAX_DELAY = 250

export function Appear({ children, index = 0, className, as = 'div' }: AppearProps) {
  const settings = useSession((state) => state.settings)
  const timing = motionFor(settings)
  const Tag = m[as]

  return (
    <Tag
      className={className}
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        duration: seconds(timing.calm),
        delay: seconds(Math.min(index * timing.stagger, MAX_DELAY)),
        ease: curves.paper,
      }}
    >
      {children}
    </Tag>
  )
}
