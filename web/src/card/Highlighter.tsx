/**
 * Маркер и стикер к выделенному куску книги.
 *
 * Здесь только краски маркера и кнопка «приклеить стикер»: читатель выбрал
 * кусок, и ему нужны два быстрых жеста, а не формы. Сам текст заметки живёт
 * на стикере в книге — стикер открывается нажатием и пишется на месте, как
 * бумажный листок.
 *
 * Десять красок показаны кружками без подписей: цвет — это и есть подпись,
 * а десять слов подряд («жёлтый, оранжевый, розовый…») читатель не читает.
 * Название всё же есть — в `title` и `aria-label`.
 */

import { TONES, toneColor, type Annotation, type Tone } from '../reader/annotations'
import { StickerIcon } from '../widgets/icons'
import { TrashIcon } from '../widgets/icons'
import styles from './card.module.css'

interface HighlighterProps {
  /** Уже существующая отметка на этом же куске, если она есть. */
  existing: Annotation | undefined
  quote: string
  onHighlight: (tone: Tone | null) => void
  /** Приклеить стикер к этому куску. */
  onSticker: () => void
  onRemove: () => void
}

export function Highlighter({
  existing,
  onHighlight,
  onSticker,
  onRemove,
}: HighlighterProps) {
  return (
    <section className={styles.highlighter}>
      <div className={styles.highlighter__row}>
        <span className={styles.highlighter__label}>Маркер</span>
        <div className={styles.tones} role="group" aria-label="Цвет маркера">
          {TONES.map((item) => (
            <button
              key={item.tone}
              type="button"
              className={styles.tone}
              data-active={existing?.tone === item.tone}
              style={{ ['--tone' as string]: toneColor(item.tone) }}
              title={item.title}
              aria-label={`Выделить: ${item.title.toLowerCase()}`}
              aria-pressed={existing?.tone === item.tone}
              onClick={() =>
                onHighlight(existing?.tone === item.tone ? null : item.tone)
              }
            />
          ))}
        </div>
      </div>

      <div className={styles.highlighter__row}>
        {existing?.note ? (
          <span className={styles.highlighter__sticker} title={existing.note}>
            <StickerIcon size={14} />
            {existing.note.length > 42 ? `${existing.note.slice(0, 42)}…` : existing.note}
          </span>
        ) : (
          <button
            type="button"
            className={styles.highlighter__stickerButton}
            onClick={onSticker}
            aria-label="Наклеить стикер на этот кусок"
            title="Наклеить стикер — заметка появится в книге"
          >
            <StickerIcon size={15} /> Наклеить стикер
          </button>
        )}
        {existing && (
          <button
            type="button"
            className={styles.highlighter__remove}
            onClick={onRemove}
            aria-label="Снять отметку"
            title="Снять отметку"
          >
            <TrashIcon size={15} />
          </button>
        )}
      </div>
    </section>
  )
}
