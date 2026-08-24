/**
 * Фраза с разбором: цвет частей речи, маркеры и подстрочник.
 *
 * Три слоя поверх одного текста, и все три приходят из ядра:
 *
 * - **части речи** красят слово;
 * - **маркеры** — вспомогательный глагол, окончание, частица, предлог —
 *   подчёркиваются. Именно они несут грамматику английского: не род, которого
 *   в языке нет, а `-ed`, `-s`, `will`, `have`, `to`, `by`. Подчёркиваются, а
 *   не заливаются: заливка спорит с чтением;
 * - **подстрочник** — короткий русский эквивалент мелким шрифтом сверху,
 *   из офлайн-словаря.
 *
 * Цвет никогда не единственный носитель смысла: под каждым цветным словом
 * есть подпись, а у маркера — всплывающее пояснение.
 */

import { useEffect, useState } from 'react'

import * as bridge from '../core/bridge'
import type { ContextPart, Marker, PosTag, Token } from '../core/types'
import { MARKER_TITLES, POS_TITLES, posColor } from './grammarColors'
import styles from './card.module.css'

interface PhraseTextProps {
  tokens: Token[]
  markers: Marker[]
  /** Части речи в индексах локального массива `tokens`. */
  parts?: ContextPart[]
  /** Показывать ли подстрочник. */
  interlinear: boolean
  /** Подписать часть речи под каждым словом. */
  showParts?: boolean
}

interface Gloss {
  lemma: string
  translation: string
  pos: string
}

export function PhraseText({
  tokens,
  markers,
  parts = [],
  interlinear,
  showParts = false,
}: PhraseTextProps) {
  const words = tokens.filter((token) => token.kind === 'word').map((token) => token.text)
  const [glosses, setGlosses] = useState<Gloss[]>([])

  useEffect(() => {
    if ((!interlinear && !showParts) || !words.length) {
      setGlosses([])
      return
    }
    let alive = true
    void bridge.gloss(words).then((found) => {
      if (alive) setGlosses(found)
    })
    return () => {
      alive = false
    }
    // Ключ по самой фразе: пересчитывать подстрочник при каждой перерисовке
    // незачем, а массив слов — новый объект на каждый рендер.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [interlinear, showParts, words.join(' ')])

  let wordNumber = -1

  return (
    <p className={styles.phrase} data-parts={showParts} lang="en">
      {tokens.map((token, index) => {
        if (token.kind !== 'word') {
          return <span key={index}>{token.text}</span>
        }
        wordNumber += 1
        const gloss = glosses[wordNumber]
        const mine = markers.filter((marker) => marker.token === index)
        const tag = parts.find((part) => part.token === index)?.pos ??
          ((gloss?.pos || '') as PosTag)

        return (
          <span key={index} className={styles.phrase__word}>
            {interlinear && (
              <span className={styles.phrase__gloss} aria-hidden="true">
                {gloss?.translation ?? ''}
              </span>
            )}
            <span
              className={styles.phrase__surface}
              style={{ color: posColor(tag) }}
              title={POS_TITLES[tag] ?? undefined}
            >
              {marked(token.text, mine)}
            </span>
            {showParts && tag && (
              <span
                className={styles.phrase__pos}
                lang="ru"
                title={POS_TITLES[tag] ?? tag}
                aria-label={POS_TITLES[tag] ?? tag}
              >
                {POS_SHORT[tag] ?? tag.toLowerCase()}
              </span>
            )}
          </span>
        )
      })}
    </p>
  )
}

const POS_SHORT: Partial<Record<PosTag, string>> = {
  NOUN: 'сущ.',
  VERB: 'гл.',
  ADJ: 'прил.',
  ADV: 'нар.',
  PRON: 'мест.',
  DET: 'опр.',
  ADP: 'предл.',
  CONJ: 'союз',
  PART: 'част.',
  PRT: 'част.',
  NUM: 'числ.',
}

/**
 * Подчёркивает маркеры внутри слова.
 *
 * Маркер несёт свои границы внутри слова (`from`/`to`), потому что окончание
 * `-ed` — это две последние буквы, а не всё слово. Подчеркнуть слово целиком
 * значило бы сказать читателю неправду о том, что именно несёт грамматику.
 */
function marked(text: string, markers: Marker[]): React.ReactNode {
  if (!markers.length) return text

  const sorted = [...markers].sort((a, b) => a.from - b.from)
  const parts: React.ReactNode[] = []
  let at = 0

  sorted.forEach((marker, index) => {
    const from = Math.max(0, Math.min(marker.from, text.length))
    const to = Math.max(from, Math.min(marker.to, text.length))
    if (from > at) parts.push(text.slice(at, from))
    parts.push(
      <mark
        key={index}
        className={styles.marker}
        data-kind={marker.kind}
        title={`${MARKER_TITLES[marker.kind] ?? marker.kind}: ${marker.note}`}
      >
        {text.slice(from, to)}
      </mark>,
    )
    at = to
  })

  if (at < text.length) parts.push(text.slice(at))
  return parts
}
