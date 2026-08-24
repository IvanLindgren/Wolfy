/**
 * Разбор фразы по членам предложения.
 *
 * Раньше здесь было два вида — дуги между словами и дерево зависимостей, — и
 * оба оказались плохи по одной причине: они отвечают на вопрос лингвиста
 * («какое ребро между какими вершинами»), а читателю нужен ответ школьного
 * разбора — «кто здесь подлежащее, что сказуемое, а остальное к чему». Дуги
 * вдобавок ломались на длинной фразе: восемь групп не помещались в строку,
 * съезжали в горизонтальную прокрутку, а дужки над ними накладывались друг на
 * друга и переставали читаться вовсе.
 *
 * Поэтому вид один и он вертикальный. Вертикаль не кончается: сколько бы
 * групп ни нашло ядро, каждая получает свою строку, и последняя выглядит так
 * же, как первая. Строки идут **в порядке фразы**, а не по важности роли —
 * читатель сверяет разбор с текстом, который только что прочёл, и любой
 * другой порядок заставляет его искать.
 *
 * Информации здесь больше, чем давали дуги: группа показана целиком, а не
 * одним главным словом, и главное слово внутри неё выделено. «over the lazy
 * dog» как дополнение объясняет фразу; одно «dog» со стрелкой — нет.
 *
 * Строится **только по тому, что дало ядро**: группы (`Chunk`) с их ролями,
 * границами и цветом. Неоднозначная связь пропускается — неверный разбор хуже
 * неполного, потому что читатель, увидевший неверный, перестаёт верить и
 * верному.
 */

import { useMemo } from 'react'
import { motion as m } from 'motion/react'

import type { Chunk, Token } from '../core/types'
import { curves, seconds } from '../theme/motion'
import { ROLE_TITLES, posColor } from './grammarColors'
import styles from './card.module.css'

interface GraphProps {
  tokens: Token[]
  chunks: Chunk[]
  /** Длительности движения. Ноль — «меньше движения». */
  stagger: number
  duration: number
}

interface Member {
  chunk: Chunk
  /** Слова группы с отметкой главного. */
  words: { text: string; head: boolean }[]
}

export function SentenceGraph({ tokens, chunks, stagger, duration }: GraphProps) {
  const members = useMemo<Member[]>(
    () =>
      [...chunks]
        .sort((a, b) => a.start - b.start)
        .map((chunk) => ({ chunk, words: wordsOf(tokens, chunk) }))
        .filter((member) => member.words.length > 0),
    [chunks, tokens],
  )

  if (members.length < 2) {
    return (
      <p className={styles.graph__empty}>
        Разобрать фразу по членам не вышло: ядро пропускает неоднозначные
        связи — неверный разбор хуже неполного.
      </p>
    )
  }

  return (
    <ol className={styles.members}>
      {members.map((member, index) => {
        const root = member.chunk.role === 'predicate'
        return (
          <m.li
            key={`${member.chunk.start}-${member.chunk.role}`}
            className={styles.member}
            data-root={root}
            style={{ ['--member-tone' as string]: posColor(member.chunk.tint) ?? 'var(--ink)' }}
            initial={{ opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{
              duration: seconds(duration),
              delay: seconds(index * stagger),
              ease: curves.paper,
            }}
          >
            <span className={styles.member__role}>
              {member.chunk.title || ROLE_TITLES[member.chunk.role]}
            </span>
            <span className={styles.member__words} lang="en">
              {member.words.map((word, position) => (
                <span
                  key={position}
                  className={styles.member__word}
                  data-head={word.head}
                >
                  {word.text}
                </span>
              ))}
            </span>
          </m.li>
        )
      })}
    </ol>
  )
}

/**
 * Слова группы с отметкой главного.
 *
 * Границы приходят от ядра полуинтервалом по индексам токенов, и знаки
 * препинания внутри группы отбрасываются: «dog,» в разборе — это «dog», а
 * запятая принадлежит фразе, а не члену предложения.
 */
function wordsOf(tokens: Token[], chunk: Chunk): { text: string; head: boolean }[] {
  const from = Math.max(0, chunk.start)
  const to = Math.min(tokens.length, Math.max(from, chunk.end))
  const words: { text: string; head: boolean }[] = []

  for (let index = from; index < to; index += 1) {
    const token = tokens[index]
    if (!token || token.kind !== 'word') continue
    words.push({ text: token.text, head: index === chunk.head })
  }

  // Группа без единого слова-токена не бывает, но пустой массив здесь стоил бы
  // пустой строки в разборе — заметнее промолчать.
  if (words.length > 0 && !words.some((word) => word.head)) {
    const last = words[words.length - 1]
    if (last) last.head = true
  }
  return words
}
