/**
 * Маркер и заметка к выделенному куску книги.
 *
 * Стоит в карточке, а не отдельным всплывающим меню над выделением, и это
 * осознанный выбор. Карточка и так открывается на каждое выделение — это
 * место, где читатель уже решает, что с этим куском делать («в колоду»).
 * Второе меню поверх текста означало бы, что на одно и то же движение руки
 * приходят два разных ответа, и один из них закрывает второй.
 *
 * Десять красок показаны кружками без подписей: цвет — это и есть подпись,
 * а десять слов подряд («жёлтый, оранжевый, розовый…») читатель не читает.
 * Название всё же есть — в `title` и `aria-label`, для тех, кому цвета
 * недостаточно.
 *
 * Заметка пишется здесь же и цитату получает автоматически: просить читателя
 * скопировать в неё то, что он только что выделил, значит заставить его
 * сделать работу, которую программа уже сделала.
 */

import { useEffect, useState } from 'react'

import { TONES, toneColor, type Annotation, type Tone } from '../reader/annotations'
import { Button } from '../widgets/Button'
import { TrashIcon } from '../widgets/icons'
import styles from './card.module.css'

interface HighlighterProps {
  /** Уже существующая отметка на этом же куске, если она есть. */
  existing: Annotation | undefined
  quote: string
  onHighlight: (tone: Tone | null) => void
  onNote: (note: string) => void
  onRemove: () => void
}

export function Highlighter({
  existing,
  quote,
  onHighlight,
  onNote,
  onRemove,
}: HighlighterProps) {
  const [writing, setWriting] = useState(false)
  const [draft, setDraft] = useState(existing?.note ?? '')

  // Карточка переиспользуется на разные куски текста: без сброса черновик
  // прошлого выделения уехал бы в заметку к следующему.
  useEffect(() => {
    setDraft(existing?.note ?? '')
    setWriting(Boolean(existing?.note))
  }, [existing?.id, existing?.note])

  const save = () => {
    onNote(draft.trim())
    if (draft.trim() === '') setWriting(false)
  }

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

      {writing ? (
        <div className={styles.noteEditor}>
          {quote && (
            <blockquote className={styles.noteQuote} lang="en">
              {quote}
            </blockquote>
          )}
          <textarea
            className={styles.noteInput}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="Что вы об этом думаете"
            aria-label="Заметка к этому месту"
            rows={3}
            autoFocus
          />
          <div className={styles.highlighter__row}>
            <Button variant="primary" small onClick={save}>
              Сохранить заметку
            </Button>
            {existing && (
              <Button
                variant="quiet"
                small
                onClick={onRemove}
                aria-label="Удалить заметку и выделение"
                title="Удалить заметку и выделение"
              >
                <TrashIcon size={15} />
              </Button>
            )}
          </div>
        </div>
      ) : (
        <div className={styles.highlighter__row}>
          <Button small onClick={() => setWriting(true)}>
            {existing?.note ? 'Изменить заметку' : 'Заметка'}
          </Button>
          {existing && (
            <Button
              variant="quiet"
              small
              onClick={onRemove}
              aria-label="Снять выделение"
              title="Снять выделение"
            >
              <TrashIcon size={15} />
            </Button>
          )}
        </div>
      )}
    </section>
  )
}
