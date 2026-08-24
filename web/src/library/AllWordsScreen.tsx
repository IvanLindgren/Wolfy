import { useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'

import { dueDay, now } from '../core/clock'
import { useSession } from '../core/session'
import type { Card } from '../core/types'
import { Appear } from '../widgets/Appear'
import { buttonClassName } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import styles from './AllWordsScreen.module.css'

type Filter = 'all' | 'word' | 'phrase' | 'due' | 'learned'

const FILTERS: { id: Filter; title: string }[] = [
  { id: 'all', title: 'Все' },
  { id: 'word', title: 'Слова' },
  { id: 'phrase', title: 'Фразы' },
  { id: 'due', title: 'К повторению' },
  { id: 'learned', title: 'Выучено' },
]

export function AllWordsScreen() {
  const books = useSession((state) => state.library.books)
  const cards = useSession((state) => state.library.cards)
  const [filter, setFilter] = useState<Filter>('all')
  const [bookId, setBookId] = useState('all')
  const [query, setQuery] = useState('')

  const bookById = useMemo(
    () => new Map(books.filter((book) => !book.deleted).map((book) => [book.id, book])),
    [books],
  )
  const deck = useMemo(
    () => cards.filter((card) => !card.deleted && card.kind !== 'rule'),
    [cards],
  )
  const shown = useMemo(() => {
    const moment = now()
    const search = query.trim().toLowerCase()
    return deck
      .filter((card) => bookId === 'all' || card.bookId === bookId)
      .filter((card) => {
        if (filter === 'word' || filter === 'phrase') return card.kind === filter
        if (filter === 'due') return card.dueAt <= moment && card.hp > 0
        if (filter === 'learned') return card.hp <= 0
        return true
      })
      .filter((card) => {
        if (!search) return true
        const book = bookById.get(card.bookId)
        return [card.surface, card.lemma, card.translation, card.context, book?.title ?? '']
          .some((value) => value.toLowerCase().includes(search))
      })
      .sort((a, b) => b.addedAt - a.addedAt)
  }, [deck, bookId, filter, query, bookById])

  return (
    <div className={`${page.page} ${page['page--wide']}`}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Вся библиотека</div>
          <h1 className={page.title}>Слова и фразы</h1>
          <p className={page.subtitle}>Поиск сразу по всем книгам и контекстам.</p>
        </div>
        <div className={page.headActions}>
          <Link to="/library" className={buttonClassName()}>К книгам</Link>
        </div>
      </header>

      {deck.length === 0 ? (
        <WolfyCompanion mood="calm" title="Здесь появятся ваши находки">
          <p className={page.muted}>Отмечайте слова и фразы во время чтения.</p>
          <Link to="/reader" className={buttonClassName({ variant: 'primary' })}>К чтению</Link>
        </WolfyCompanion>
      ) : (
        <>
          <div className={styles.toolbar}>
            <div className={styles.filters} role="group" aria-label="Фильтр карточек">
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
            <select
              className={page.input}
              value={bookId}
              onChange={(event) => setBookId(event.target.value)}
              aria-label="Фильтр по книге"
            >
              <option value="all">Все книги</option>
              {[...bookById.values()].map((book) => (
                <option key={book.id} value={book.id}>{book.title}</option>
              ))}
            </select>
            <input
              className={page.input}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Слово, перевод или контекст"
              aria-label="Поиск по всем словам"
              type="search"
            />
          </div>

          <div className={styles.grid}>
            {shown.map((card, index) => (
              <WordTile
                key={card.id}
                card={card}
                bookTitle={bookById.get(card.bookId)?.title ?? 'Книга'}
                index={index}
              />
            ))}
          </div>
          {shown.length === 0 && <p className={page.muted}>Ничего не нашлось.</p>}
        </>
      )}
    </div>
  )
}

function WordTile({
  card,
  bookTitle,
  index,
}: {
  card: Card
  bookTitle: string
  index: number
}) {
  const title = card.surface || card.context || (card.kind === 'phrase' ? 'Фраза' : card.lemma)

  return (
    <Appear as="article" index={index} className={styles.card}>
      <div className={styles.cardHead}>
        <span className={styles.kind}>{card.kind === 'phrase' ? 'Фраза' : card.pos || 'Слово'}</span>
        <span className={styles.status}>{card.hp <= 0 ? 'выучено' : dueDay(card.dueAt)}</span>
      </div>
      <h2 className={styles.surface} lang="en">{title}</h2>
      {card.lemma && card.lemma.toLowerCase() !== title.toLowerCase() && (
        <span className={styles.lemma} lang="en">{card.lemma}</span>
      )}
      <p className={styles.translation}>{card.translation || 'Перевод ещё не сохранён'}</p>
      {card.context && card.context !== title && (
        <p className={styles.context} lang="en">{card.context}</p>
      )}
      <Link
        to="/library/$bookId/words"
        params={{ bookId: card.bookId }}
        className={styles.bookLink}
      >
        {bookTitle} →
      </Link>
    </Appear>
  )
}
