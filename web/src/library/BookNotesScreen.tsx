/**
 * Заметки к книге: всё, что читатель отметил маркером и написал.
 *
 * Порядок — читательский, а не хронологический: заметки идут так, как
 * встречаются в тексте, потому что ищут их по книге («то место про волков»),
 * а не по дате, когда до него добрались. Внутри — по главам, и глава названа
 * своим именем, а не номером, если ядро его знает.
 *
 * Каждая заметка ведёт обратно в книгу, на своё место. Список заметок, из
 * которого нельзя вернуться к тексту, — это выписки, а выписки читатель и так
 * умеет делать в тетради; ценность здесь именно в том, что место рядом.
 *
 * Текст заметки правится прямо в списке. Открывать ради двух слов книгу,
 * листать до места и ждать карточку — это четыре действия там, где нужно
 * одно.
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'

import { toast } from '../app/toasts'
import * as bridge from '../core/bridge'
import { useSession } from '../core/session'
import {
  TONES,
  inReadingOrder,
  toneColor,
  useAnnotations,
  type Annotation,
  type Tone,
} from '../reader/annotations'
import { Appear } from '../widgets/Appear'
import { Button, buttonClassName } from '../widgets/Button'
import { TrashIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { SearchField } from '../widgets/SearchField'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './notes.module.css'

export function BookNotesScreen() {
  const { bookId } = useParams({ from: '/library/$bookId/notes' })
  const book = useSession((state) => state.library.books.find((item) => item.id === bookId))
  const ready = useSession((state) => state.ready)

  const annotations = useAnnotations((state) => state.annotations)
  const loadedBook = useAnnotations((state) => state.bookId)
  const [titles, setTitles] = useState<(string | null)[]>([])
  const [query, setQuery] = useState('')

  useEffect(() => {
    void useAnnotations.getState().open(bookId)
  }, [bookId])

  /*
   * Названия глав достаются из самой книги, но открывать её ради заголовков
   * дорого, а закрывать обязательно: иначе следующее открытие в читалке
   * получит уже занятый идентификатор.
   */
  useEffect(() => {
    if (!ready || !book?.path) return
    let alive = true
    void bridge
      .bookChapters(book.path)
      .then((chapters) => {
        if (alive) setTitles(chapters)
      })
      .catch(() => {
        // Названий не будет — номера глав никуда не денутся.
      })
    return () => {
      alive = false
    }
  }, [ready, book?.path])

  /*
   * Пометки удалений в списке не участвуют: они нужны только слиянию, чтобы
   * удаление с одного устройства доехало до другого. Здесь это просто
   * мусор, который читателю видеть не нужно.
   */
  const live = useMemo(() => annotations.filter((item) => !item.deleted), [annotations])

  const shown = useMemo(() => {
    const search = query.trim().toLowerCase()
    const ordered = inReadingOrder(live)
    if (!search) return ordered
    return ordered.filter(
      (item) =>
        item.quote.toLowerCase().includes(search) ||
        item.note.toLowerCase().includes(search),
    )
  }, [live, query])

  const byChapter = useMemo(() => {
    const groups = new Map<number, Annotation[]>()
    for (const item of shown) {
      const list = groups.get(item.chapter)
      if (list) list.push(item)
      else groups.set(item.chapter, [item])
    }
    return [...groups.entries()]
  }, [shown])

  const withNotes = live.filter((item) => item.note !== '').length

  if (!book) {
    return (
      <WolfyCompanion mood="kind" title="Книга не найдена">
        <Link to="/library" className={buttonClassName({ variant: 'primary' })}>
          К библиотеке
        </Link>
      </WolfyCompanion>
    )
  }

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Заметки к книге</div>
          <h1 className={page.title}>{book.title}</h1>
          {live.length > 0 && (
            <p className={page.subtitle}>
              Отмечено мест: {live.length}
              {withNotes > 0 && `, из них с заметкой ${withNotes}`}
            </p>
          )}
        </div>
        <div className={page.headActions}>
          <Link to="/library/$bookId/words" params={{ bookId }} className={buttonClassName()}>
            Слова книги
          </Link>
          <Link
            to="/reader/$bookId"
            params={{ bookId }}
            className={buttonClassName({ variant: 'primary' })}
          >
            Читать дальше
          </Link>
        </div>
      </header>

      {loadedBook === bookId && live.length === 0 ? (
        <WolfyCompanion mood="calm" title="Здесь пока ничего не отмечено">
          <p className={page.muted} style={{ maxWidth: '34rem' }}>
            Выделите кусок текста в книге — в карточке появится{' '}
            <strong>маркер десяти цветов</strong> и поле для заметки. Заметку
            можно оставить и к месту без цитаты — значком в панели читалки.
          </p>
          <Link
            to="/reader/$bookId"
            params={{ bookId }}
            className={buttonClassName({ variant: 'primary' })}
          >
            Открыть книгу
          </Link>
        </WolfyCompanion>
      ) : (
        <>
          {live.length > 4 && (
            <SearchField
              value={query}
              onChange={setQuery}
              label="Поиск по заметкам книги"
              placeholder="Цитата или ваша заметка"
            />
          )}

          {byChapter.map(([chapter, items]) => (
            <section key={chapter} className={styles.chapter}>
              <div className={page.sectionHead}>
                <h2 className={page.sectionTitle}>
                  {titles[chapter] ?? `Глава ${chapter + 1}`}
                </h2>
                <span className={page.sectionRule} />
                <span className={styles.count}>{items.length}</span>
              </div>
              {items.map((item, index) => (
                <NoteRow key={item.id} annotation={item} bookId={bookId} index={index} />
              ))}
            </section>
          ))}

          {shown.length === 0 && live.length > 0 && (
            <p className={page.muted}>По этому запросу ничего не нашлось.</p>
          )}
        </>
      )}
    </div>
  )
}

function NoteRow({
  annotation,
  bookId,
  index,
}: {
  annotation: Annotation
  bookId: string
  index: number
}) {
  const [draft, setDraft] = useState(annotation.note)
  const [editing, setEditing] = useState(false)

  const save = useCallback(() => {
    const text = draft.trim()
    if (text === annotation.note) {
      setEditing(false)
      return
    }
    // Отметка без краски и без текста не значит ничего: ни в книге её не
    // видно, ни здесь читать нечего.
    if (text === '' && annotation.tone === null) {
      void useAnnotations.getState().remove(annotation.id)
      toast('Заметка удалена')
      return
    }
    void useAnnotations.getState().update(annotation.id, { note: text })
    setEditing(false)
  }, [draft, annotation])

  return (
    <Appear index={index} as="article" className={styles.note}>
      <span
        className={styles.note__tone}
        style={{ ['--tone' as string]: annotation.tone ? toneColor(annotation.tone) : 'var(--rule)' }}
        aria-hidden="true"
      />

      <div className={styles.note__body}>
        {annotation.quote && (
          <blockquote className={styles.note__quote} lang="en">
            {annotation.quote}
          </blockquote>
        )}

        {editing ? (
          <>
            <textarea
              className={styles.note__input}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              placeholder="Что вы об этом думаете"
              aria-label="Текст заметки"
              rows={3}
              autoFocus
            />
            <div className={styles.note__actions}>
              <Button variant="primary" small onClick={save}>
                Сохранить
              </Button>
              <Button
                variant="quiet"
                small
                onClick={() => {
                  setDraft(annotation.note)
                  setEditing(false)
                }}
              >
                Отменить
              </Button>
            </div>
          </>
        ) : (
          <>
            {annotation.note ? (
              <p className={styles.note__text}>{annotation.note}</p>
            ) : (
              <p className={styles.note__empty}>Без заметки — только выделение.</p>
            )}
            <div className={styles.note__actions}>
              <Link
                to="/reader/$bookId"
                params={{ bookId }}
                search={{ chapter: annotation.chapter, token: annotation.start }}
                className={buttonClassName({ small: true })}
              >
                К месту в книге
              </Link>
              <Button small variant="quiet" onClick={() => setEditing(true)}>
                {annotation.note ? 'Изменить' : 'Написать заметку'}
              </Button>
              <ToneSwitch annotation={annotation} />
              <Button
                variant="quiet"
                small
                aria-label="Удалить отметку"
                title="Удалить отметку"
                onClick={() => {
                  void useAnnotations.getState().remove(annotation.id)
                  toast('Отметка удалена')
                }}
              >
                <TrashIcon size={15} />
              </Button>
            </div>
          </>
        )}
      </div>
    </Appear>
  )
}

/** Перекрасить отметку, не открывая книгу. */
function ToneSwitch({ annotation }: { annotation: Annotation }) {
  const [open, setOpen] = useState(false)

  if (!open) {
    return (
      <Button small variant="quiet" onClick={() => setOpen(true)}>
        Цвет
      </Button>
    )
  }

  const pick = (tone: Tone | null) => {
    void useAnnotations.getState().update(annotation.id, { tone })
    setOpen(false)
  }

  return (
    <div className={styles.tones} role="group" aria-label="Цвет маркера">
      {TONES.map((item) => (
        <button
          key={item.tone}
          type="button"
          className={styles.tone}
          data-active={annotation.tone === item.tone}
          style={{ ['--tone' as string]: toneColor(item.tone) }}
          title={item.title}
          aria-label={item.title}
          onClick={() => pick(annotation.tone === item.tone ? null : item.tone)}
        />
      ))}
    </div>
  )
}
