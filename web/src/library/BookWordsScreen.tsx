/**
 * Словарь книги: всё, что читатель из неё отметил.
 *
 * Фильтры здесь не украшение. Колода в двести слов — это список, в котором
 * нельзя ничего найти; «к повторению», «выучено» и поиск превращают его в
 * рабочий инструмент. Прочность карточки показана числом и полосой: читатель
 * должен видеть, что слово, которое он помнит, действительно уходит.
 */

import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from '@tanstack/react-router'

import { toast } from '../app/toasts'
import { session, useSession } from '../core/session'
import { dueDay, now } from '../core/clock'
import type { Card } from '../core/types'
import { SearchField } from '../widgets/SearchField'
import { Appear } from '../widgets/Appear'
import { Button, buttonClassName } from '../widgets/Button'
import { TrashIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './library.module.css'

type Filter = 'all' | 'due' | 'learned' | 'phrases'

const FILTERS: { id: Filter; title: string }[] = [
  { id: 'all', title: 'Все' },
  { id: 'due', title: 'К повторению' },
  { id: 'learned', title: 'Выучено' },
  { id: 'phrases', title: 'Фразы' },
]

export function BookWordsScreen() {
  const { bookId } = useParams({ from: '/library/$bookId/words' })
  const book = useSession((state) => state.library.books.find((item) => item.id === bookId))
  const books = useSession((state) => state.library.books)
  const cards = useSession((state) => state.library.cards)
  const navigate = useNavigate()

  const [filter, setFilter] = useState<Filter>('all')
  const [query, setQuery] = useState('')

  const deck = useMemo(
    () => cards.filter((card) => card.bookId === bookId && !card.deleted),
    [cards, bookId],
  )

  const shown = useMemo(() => {
    const moment = now()
    const search = query.trim().toLowerCase()
    return deck
      .filter((card) => {
        if (filter === 'due') return card.dueAt <= moment && card.hp > 0
        if (filter === 'learned') return card.hp <= 0
        if (filter === 'phrases') return card.kind === 'phrase'
        return true
      })
      .filter(
        (card) =>
          !search ||
          card.surface.toLowerCase().includes(search) ||
          card.lemma.toLowerCase().includes(search) ||
          card.translation.toLowerCase().includes(search),
      )
      .sort((a, b) => b.addedAt - a.addedAt)
  }, [deck, filter, query])

  if (!book) {
    return (
      <WolfyCompanion mood="kind" title="Книга не найдена">
        <Link to="/library" className={buttonClassName({ variant: 'primary' })}>К библиотеке</Link>
      </WolfyCompanion>
    )
  }

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Словарь книги</div>
          <h1 className={page.title}>{book.title}</h1>
        </div>
        <div className={page.headActions}>
          <Link to="/library/words" className={buttonClassName()}>Все слова</Link>
          <Link
            to="/reader/$bookId"
            params={{ bookId }}
            className={buttonClassName({ variant: 'primary' })}
          >
            Читать дальше
          </Link>
        </div>
      </header>

      <label className={styles.bookPicker}>
        <span>Словарь книги</span>
        <select
          className={page.input}
          value={bookId}
          onChange={(event) =>
            void navigate({
              to: '/library/$bookId/words',
              params: { bookId: event.target.value },
            })
          }
        >
          {books
            .filter((item) => !item.deleted)
            .map((item) => (
              <option key={item.id} value={item.id}>{item.title}</option>
            ))}
        </select>
      </label>

      {deck.length === 0 ? (
        <WolfyCompanion mood="calm" title="Из этой книги пока ничего не отмечено">
          <p className={page.muted} style={{ maxWidth: '32rem' }}>
            Тапните по слову в тексте — карточка покажет разбор, а кнопка «в
            колоду книги» отправит слово на повторение.
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
          <div className={styles.filters} role="group" aria-label="Фильтр карточек книги">
            {FILTERS.map((item) => (
              <button
                key={item.id}
                type="button"
                className={styles.filter}
                data-active={filter === item.id}
                aria-pressed={filter === item.id}
                onClick={() => setFilter(item.id)}
              >
                {item.title}
              </button>
            ))}
          </div>

          <SearchField
            value={query}
            onChange={setQuery}
            label="Поиск по словарю книги"
            placeholder="Слово, перевод или контекст"
          />

          <div className={styles.words}>
            {shown.map((card, index) => (
              <WordRow key={card.id} card={card} index={index} />
            ))}
            {shown.length === 0 && (
              <p className={page.muted}>Ничего не нашлось — попробуйте другой фильтр.</p>
            )}
          </div>
        </>
      )}
    </div>
  )
}

function WordRow({ card, index }: { card: Card; index: number }) {
  return (
    <Appear index={index} as="div" className={styles.word}>
      <div>
        <span className={styles.word__surface} lang="en">
          {card.surface}
        </span>
        {card.lemma && card.lemma.toLowerCase() !== card.surface.toLowerCase() && (
          <span className={styles.word__lemma} lang="en">
            {card.lemma}
          </span>
        )}
      </div>

      <div className={styles.word__translation}>
        {card.translation || <span className={page.muted}>перевода нет</span>}
      </div>

      <div className={styles.word__hp}>
        <span title="Прочность карточки: падает при уверенном знании">
          {card.hp <= 0 ? 'выучено' : `${card.hp} HP`}
        </span>
        <span>{dueDay(card.dueAt)}</span>
        <Button
          variant="quiet"
          small
          aria-label={`Убрать «${card.surface}» из колоды`}
          onClick={() => {
            void session.removeWord(card.bookId, card.lemma)
            toast(`«${card.surface}» убрано из колоды`)
          }}
        >
          <TrashIcon size={15} />
        </Button>
      </div>

      {card.context && (
        <p className={styles.word__context} lang="en">
          {card.context}
        </p>
      )}
    </Appear>
  )
}
