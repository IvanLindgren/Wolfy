/**
 * Легенда красок разбора.
 *
 * Цвет не имеет права быть единственным носителем смысла — это требование
 * доступности, но и просто честности: читатель, впервые открывший карточку,
 * видит пять оттенков и не знает, что синий — существительное, а подложка под
 * `-ed` вообще не часть речи. Подпись под каждым словом отвечает на вопрос
 * «что это за слово»; легенда отвечает на вопрос «что значат краски».
 *
 * Показывается **только то, что встретилось в этой фразе**. Полная таблица из
 * девяти частей речи и четырёх маркеров под каждой фразой — шум, который
 * перестают читать со второго раза, и тогда она не работает и в тот раз,
 * когда нужна.
 */

import { useMemo } from 'react'

import type { ContextPart, Marker, PosTag } from '../core/types'
import { MARKER_TITLES, POS_TITLES, posColor } from './grammarColors'
import styles from './card.module.css'

interface LegendProps {
  parts: ContextPart[]
  markers: Marker[]
}

export function ColorLegend({ parts, markers }: LegendProps) {
  const items = useMemo(() => {
    const shown: { key: string; title: string; tone: string; marker: boolean }[] = []
    const seen = new Set<string>()

    // Порядок — как в палитре, а не как в предложении: легенда должна
    // выглядеть одинаково под разными фразами, иначе её перечитывают заново.
    const order: PosTag[] = ['NOUN', 'VERB', 'ADJ', 'ADV', 'PRON']
    for (const tag of order) {
      if (!parts.some((part) => part.pos === tag)) continue
      const tone = posColor(tag)
      if (!tone) continue
      shown.push({ key: tag, title: POS_TITLES[tag] ?? tag, tone, marker: false })
    }

    // Подложка у всех маркеров одна, поэтому и строка в легенде одна: четыре
    // одинаковых образца с разными подписями объясняли бы разницу, которой на
    // экране нет. Виды перечислены внутри строки — в том порядке, в каком
    // встретились во фразе.
    const kinds: string[] = []
    for (const marker of markers) {
      if (seen.has(marker.kind)) continue
      seen.add(marker.kind)
      kinds.push(MARKER_TITLES[marker.kind] ?? marker.kind)
    }
    if (kinds.length > 0) {
      shown.push({
        key: 'markers',
        title: kinds.join(', '),
        tone: 'var(--ink)',
        marker: true,
      })
    }

    return shown
  }, [parts, markers])

  if (items.length === 0) return null

  return (
    <div className={styles.legend} aria-label="Что значат цвета в разборе">
      {items.map((item) => (
        <span key={item.key} className={styles.legend__item}>
          <span
            className={styles.legend__swatch}
            data-shape={item.marker ? 'marker' : 'pos'}
            style={{ ['--legend-tone' as string]: item.tone }}
            aria-hidden="true"
          />
          {item.title}
        </span>
      ))}
    </div>
  )
}
