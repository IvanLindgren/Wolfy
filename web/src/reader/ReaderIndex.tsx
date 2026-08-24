/**
 * Раздел «Читалка» без открытой книги.
 *
 * Не пустой экран с надписью «выберите книгу», а книга, к которой стоит
 * вернуться: её называет ядро (`continueReading`), потому что это то же
 * правило, по которому телефон и настольная версия выбирают «продолжить».
 */

import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'

import { session, useSession } from '../core/session'
import type { LibraryBook } from '../core/types'
import { SearchField } from '../widgets/SearchField'
import { buttonClassName } from '../widgets/Button'
import { Appear } from '../widgets/Appear'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import { BookCover, fraction } from '../library/BookCover'
import styles from './ReaderIndex.module.css'

export function ReaderIndex() {
  const ready = useSession((state) => state.ready)
  const revision = useSession((state) => state.library.revision)
  const books = useSession((state) => state.library.books)
  const navigate = useNavigate()
  const [suggested, setSuggested] = useState<LibraryBook | null>(null)
  const [query, setQuery] = useState('')

  const readable = useMemo(
    () => books.filter((book) => !book.deleted && book.path),
    [books],
  )
  const shown = useMemo(() => {
    const search = query.trim().toLowerCase()
    if (!search) return readable
    return readable.filter(
      (book) =>
        book.title.toLowerCase().includes(search) ||
        (book.author?.toLowerCase().includes(search) ?? false),
    )
  }, [readable, query])

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
    if (readable.length === 1 && readable[0]) {
      void navigate({
        to: '/reader/$bookId',
        params: { bookId: readable[0].id },
        replace: true,
      })
    }
  }, [readable, navigate])

  if (ready && !books.some((book) => !book.deleted)) {
    return (
      <WolfyCompanion mood="calm" title="Читать пока нечего">
        <p className={page.muted} style={{ maxWidth: '32rem' }}>
          Добавьте <strong>EPUB, TXT или PDF</strong>. Дальше всё работает без сети.
        </p>
        <Link to="/library" className={buttonClassName({ variant: 'primary' })}>В библиотеку</Link>
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
        <div className={page.headActions}>
          <Link to="/library/words" className={buttonClassName()}>Все слова</Link>
          <Link to="/library" className={buttonClassName({ variant: 'primary' })}>Библиотека</Link>
        </div>
      </header>

      {suggested ? (
        <section className={styles.continueCard}>
          <div className={styles.continueCover}>
            <BookCover book={suggested} />
          </div>
          <div className={styles.continueBody}>
            <span className={styles.eyebrow}>Последняя открытая</span>
            <h2 className={styles.continueTitle}>{suggested.title}</h2>
            {suggested.author && <p className={page.muted}>{suggested.author}</p>}
            <p className={styles.progressText}>
              Прочитано {Math.round(fraction(suggested) * 100)}% · глава{' '}
              {suggested.progress.chapter + 1} из {suggested.chapters}
            </p>
            <Link
              to="/reader/$bookId"
              params={{ bookId: suggested.id }}
              className={buttonClassName({ variant: 'primary' })}
            >
              Открыть там, где закрыли
            </Link>
          </div>
        </section>
      ) : (
        <p className={page.muted}>
          Выберите книгу в библиотеке — она откроется ровно там, где её закрыли.
        </p>
      )}

      {readable.length > 1 && (
        <section className={styles.allBooks}>
          <div className={page.sectionHead}>
            <h2 className={page.sectionTitle}>Все книги</h2>
            <span className={page.sectionRule} />
          </div>
          {/*
            Поиск стоит отдельной строкой, а не внутри заголовка секции: поле
            в одной строке с антиквой заголовка ломает её ритм и тянет на себя
            вес, которого у поиска нет.
          */}
          <SearchField
            value={query}
            onChange={setQuery}
            label="Поиск книги"
            placeholder="Название или автор"
          />
          <div className={styles.bookGrid}>
            {shown.map((book, index) => (
              <Appear key={book.id} index={index}>
                <Link
                  to="/reader/$bookId"
                  params={{ bookId: book.id }}
                  className={styles.bookLink}
                >
                  <div className={styles.bookCover}>
                    <BookCover book={book} />
                  </div>
                  <span className={styles.bookTitle}>{book.title}</span>
                  <span className={styles.bookMeta}>
                    {Math.round(fraction(book) * 100)}% · {book.author || 'Автор не указан'}
                  </span>
                </Link>
              </Appear>
            ))}
          </div>
          {shown.length === 0 && <p className={page.muted}>Таких книг не нашлось.</p>}
        </section>
      )}
    </div>
  )
}
