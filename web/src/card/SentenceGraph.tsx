/**
 * Граф синтаксических связей и дерево зависимостей.
 *
 * Два вида одного и того же: граф — стрелки между главными словами групп,
 * дерево — те же связи, разложенные по уровням. Первый отвечает на вопрос
 * «кто с кем связан», второй — «что от чего зависит»; читателю нужны оба, и
 * переключение между ними стоит одной кнопки.
 *
 * Строится **только по тому, что дало ядро**: группы (`Chunk`) с их главными
 * словами и ролями. Неоднозначная связь пропускается — неверный граф хуже
 * неполного, потому что читатель, увидевший неверный разбор, перестаёт верить
 * и верному.
 *
 * Стрелки разворачиваются по очереди с задержкой `stagger`: последовательность
 * и есть объяснение — сначала подлежащее и сказуемое, потом всё остальное.
 */

import { useMemo } from 'react'
import { motion as m } from 'motion/react'

import type { Chunk, RoleName, Token } from '../core/types'
import { curves, seconds } from '../theme/motion'
import { ROLE_SHORT, ROLE_TITLES, posColor } from './grammarColors'
import styles from './card.module.css'

interface GraphProps {
  tokens: Token[]
  chunks: Chunk[]
  mode: 'graph' | 'tree'
  /** Длительности движения. Ноль — «меньше движения». */
  stagger: number
  duration: number
}

interface Node {
  role: RoleName
  text: string
  head: number
}

/**
 * Кто от кого зависит.
 *
 * Сказуемое — корень: в английском предложении именно оно держит всё
 * остальное. Подлежащее, дополнения и обстоятельства ведут к нему; связка
 * стоит между частями и потому тоже цепляется к сказуемому.
 */
function linksOf(nodes: Node[]): { from: number; to: number }[] {
  const root = nodes.findIndex((node) => node.role === 'predicate')
  if (root === -1) return []
  return nodes
    .map((_, index) => ({ from: index, to: root }))
    .filter((link) => link.from !== root)
}

export function SentenceGraph({
  tokens,
  chunks,
  mode,
  stagger,
  duration,
}: GraphProps) {
  const nodes = useMemo<Node[]>(
    () =>
      chunks.map((chunk) => ({
        role: chunk.role,
        text: tokens[chunk.head]?.text ?? headText(tokens, chunk),
        head: chunk.head,
      })),
    [chunks, tokens],
  )

  const links = useMemo(() => linksOf(nodes), [nodes])

  if (nodes.length < 2) {
    return (
      <p className={styles.graph__empty}>
        Связей во фразе не нашлось: ядро пропускает неоднозначные — неверный
        граф хуже неполного.
      </p>
    )
  }

  return mode === 'graph' ? (
    <GraphArrows nodes={nodes} links={links} stagger={stagger} duration={duration} />
  ) : (
    <DependencyTree nodes={nodes} stagger={stagger} duration={duration} />
  )
}

function headText(tokens: Token[], chunk: Chunk): string {
  const words = tokens
    .slice(Math.max(0, chunk.start), Math.max(0, chunk.end))
    .filter((token) => token.kind === 'word')
  return words[words.length - 1]?.text ?? ''
}

/** Граф: слова в ряд, дуги между ними. */
function GraphArrows({
  nodes,
  links,
  stagger,
  duration,
}: {
  nodes: Node[]
  links: { from: number; to: number }[]
  stagger: number
  duration: number
}) {
  const width = Math.max(280, nodes.length * 116)
  const height = 132
  const slot = width / nodes.length
  const baseline = height - 34

  const at = (index: number) => slot * index + slot / 2

  return (
    <div className={styles.graph}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className={styles.graph__svg}
        role="img"
        aria-label="Связи между членами предложения"
      >
        <defs>
          <marker
            id="wolfy-arrow"
            viewBox="0 0 8 8"
            refX="7"
            refY="4"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path d="M0 0.5 L8 4 L0 7.5 z" fill="var(--ink-muted)" />
          </marker>
        </defs>

        {links.map((link, index) => {
          const from = at(link.from)
          const to = at(link.to)
          const lift = Math.min(78, 26 + Math.abs(link.to - link.from) * 18)
          const path = `M ${from} ${baseline - 26} C ${from} ${baseline - 26 - lift}, ${to} ${baseline - 26 - lift}, ${to} ${baseline - 26}`
          return (
            <m.path
              key={`${link.from}-${link.to}`}
              d={path}
              fill="none"
              stroke="var(--ink-muted)"
              strokeWidth="1.4"
              markerEnd="url(#wolfy-arrow)"
              initial={{ pathLength: 0, opacity: 0 }}
              animate={{ pathLength: 1, opacity: 1 }}
              transition={{
                duration: seconds(duration),
                delay: seconds(index * stagger),
                ease: curves.paper,
              }}
            />
          )
        })}

        {nodes.map((node, index) => (
          <g key={index}>
            <text
              x={at(index)}
              y={baseline}
              textAnchor="middle"
              className={styles.graph__word}
              fill={posColor(roleTint(node.role)) ?? 'var(--ink)'}
            >
              {node.text}
            </text>
            <text
              x={at(index)}
              y={baseline + 18}
              textAnchor="middle"
              className={styles.graph__role}
              fill="var(--ink-muted)"
            >
              {ROLE_SHORT[node.role]}
            </text>
          </g>
        ))}
      </svg>
    </div>
  )
}

/** Дерево: сказуемое сверху, зависимые под ним. */
function DependencyTree({
  nodes,
  stagger,
  duration,
}: {
  nodes: Node[]
  stagger: number
  duration: number
}) {
  const root = nodes.find((node) => node.role === 'predicate')
  const rest = nodes.filter((node) => node.role !== 'predicate')

  return (
    <div className={styles.tree}>
      {root && (
        <m.div
          className={styles.tree__root}
          initial={{ opacity: 0, y: -6 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: seconds(duration), ease: curves.paper }}
        >
          <span style={{ color: posColor('VERB') }}>{root.text}</span>
          <small>{ROLE_TITLES[root.role]}</small>
        </m.div>
      )}
      <div className={styles.tree__branches}>
        {rest.map((node, index) => (
          <m.div
            key={index}
            className={styles.tree__branch}
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{
              duration: seconds(duration),
              delay: seconds((index + 1) * stagger),
              ease: curves.paper,
            }}
          >
            <span style={{ color: posColor(roleTint(node.role)) }}>{node.text}</span>
            <small>{ROLE_TITLES[node.role]}</small>
          </m.div>
        ))}
      </div>
    </div>
  )
}

/**
 * Цвет роли — через часть речи, а не своим набором.
 *
 * Так же, как в ядре (`Role::tint`): подлежащее и дополнения красятся цветом
 * существительного, сказуемое — глагола, обстоятельство — наречия. Свой набор
 * цветов для ролей рядом с набором для частей речи означал бы два разных
 * ответа на один вопрос «что здесь синее».
 */
function roleTint(role: RoleName) {
  switch (role) {
    case 'predicate':
      return 'VERB' as const
    case 'adverbial':
      return 'ADV' as const
    case 'connector':
      return 'PRON' as const
    default:
      return 'NOUN' as const
  }
}
