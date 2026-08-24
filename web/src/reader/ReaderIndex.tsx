/**
 * Раздел «Читалка» без открытой книги.
 *
 * Не пустой экран с надписью «выберите книгу», а книга, к которой стоит
 * вернуться: её называет ядро (`continueReading`), потому что это то же
 * правило, по которому телефон и настольная версия выбирают «продолжить».
 */

import { useEffect, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'

import { session, useSession } from '../core/session'
import type { LibraryBook } from '../core/types'
import { Button } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import { BookCover, fraction } from '../library/BookCover'

export function ReaderIndex() {
  const ready = useSession((state) => state.ready)
  const revision = useSession((state) => state.library.revision)
  const books = useSession((state) => state.library.books)
  const navigate = useNavigate()
  const [suggested, setSuggested] = useState<LibraryBook | null>(null)

  useEffect(() => {
    if (!ready) return
    let alive = true
    void session.continueReading().then((book) => {
      if (alive) setSuggested(book ?? null)
    })
    return () => {
      alive = false
    }
  }, [ready, revision])

  // Одна книга и есть ответ: незачем показывать выбор из одного.
  useEffect(() => {
    const readable = books.filter((book) => !book.deleted && book.path)
    if (readable.length === 1 && readable[0]) {
      void navigate({
        to: '/reader/$bookId',
        params: { bookId: readable[0].id },
        replace: true,
      })
    }
  }, [books, navigate])

  if (ready && !books.some((book) => !book.deleted)) {
    return (
      <WolfyCompanion mood="calm" title="Читать пока нечего">
        <p className={page.muted} style={{ maxWidth: '32rem' }}>
          Добавьте книгу — перетащите файл в окно или выберите его в
          библиотеке. Дальше всё работает без сети.
        </p>
        <Link to="/library">
          <Button variant="primary">В библиотеку</Button>
        </Link>
      </WolfyCompanion>
    )
  }

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Читалка</div>
          <h1 className={page.title}>Продолжить</h1>
        </div>
      </header>

      {suggested ? (
        <div style={{ display: 'flex', gap: '1.5rem', alignItems: 'flex-start' }}>
          <div style={{ width: '10rem', flexShrink: 0 }}>
            <BookCover book={suggested} />
          </div>
          <div>
            <h2 style={{ fontSize: '1.5rem', marginBottom: '0.35rem' }}>
              {suggested.title}
            </h2>
            {suggested.author && <p className={page.muted}>{suggested.author}</p>}
            <p className={page.muted} style={{ margin: '0.6rem 0 1rem' }}>
              Прочитано {Math.round(fraction(suggested) * 100)}% · глава{' '}
              {suggested.progress.chapter + 1} из {suggested.chapters}
            </p>
            <Link to="/reader/$bookId" params={{ bookId: suggested.id }}>
              <Button variant="primary">Открыть там, где закрыли</Button>
            </Link>
          </div>
        </div>
      ) : (
        <p className={page.muted}>
          Выберите книгу в библиотеке — она откроется ровно там, где её закрыли.
        </p>
      )}
    </div>
  )
}
