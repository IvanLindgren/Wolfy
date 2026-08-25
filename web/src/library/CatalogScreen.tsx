/**
 * Каталог Открытой библиотеки.
 *
 * Третий способ пополнить библиотеку — рядом с файлом и ссылкой, но без того
 * и без другого: набрал «Sherlock Holmes», нажал «скачать», читаешь. Поиск и
 * скачивание идут через свой сервер, как и всё, что ходит наружу; сам файл
 * после скачивания живёт в браузере, как всякая книга библиотеки.
 *
 * Повторяет `CatalogScreen` клиента на Kotlin — вплоть до порядка попыток
 * скачивания: у находки бывает несколько адресов, сначала EPUB из архива,
 * затем послойный текст, и пробуем по порядку до первой удачи.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'

import { ApiError, OfflineError, searchCatalogue, type CatalogueBook } from '../api/client'
import type { LibraryBook } from '../core/types'
import { Appear } from '../widgets/Appear'
import { Button, buttonClassName } from '../widgets/Button'
import { BackIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { SearchField } from '../widgets/SearchField'
import { WolfyCompanion } from '../widgets/Wolfy'
import { addURL } from './import'
import styles from './catalog.module.css'

type Search =
  | { state: 'idle' }
  | { state: 'searching' }
  | { state: 'ready'; books: CatalogueBook[] }
  | { state: 'failed'; message: string }

export function CatalogScreen() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState<Search>({ state: 'idle' })
  /** Что уже скачивается и что уже скачано — по номеру работы в каталоге. */
  const [downloading, setDownloading] = useState<Set<string>>(new Set())
  const [added, setAdded] = useState<Map<string, LibraryBook>>(new Map())
  const [problem, setProblem] = useState<string | null>(null)

  // Прошлый запрос отменяется, иначе медленный ответ первого поиска
  // перепишет результаты второго.
  const running = useRef<AbortController | null>(null)
  useEffect(() => () => running.current?.abort(), [])

  const run = useCallback(async () => {
    const clean = query.trim()
    if (!clean) return

    running.current?.abort()
    const controller = new AbortController()
    running.current = controller

    setSearch({ state: 'searching' })
    setProblem(null)
    try {
      const books = await searchCatalogue(clean, controller.signal)
      if (controller.signal.aborted) return
      setSearch({ state: 'ready', books })
    } catch (error) {
      if (controller.signal.aborted) return
      setSearch({ state: 'failed', message: reason(error) })
    }
  }, [query])

  /**
   * Скачивает находку и добавляет её в библиотеку.
   *
   * Адреса пробуются по порядку: у части отсканированных изданий производного
   * EPUB нет, и послойный текст лучше пустого ответа.
   */
  const download = useCallback(
    async (book: CatalogueBook) => {
      setDownloading((busy) => new Set(busy).add(book.id))
      setProblem(null)

      let failure = 'Книгу не удалось скачать.'
      try {
        for (const address of book.urls) {
          const result = await addURL(address)
          if (result.kind === 'refused') {
            failure = result.message
            continue
          }
          setAdded((known) => new Map(known).set(book.id, result.book))
          return
        }
        setProblem(failure)
      } finally {
        setDownloading((busy) => {
          const next = new Set(busy)
          next.delete(book.id)
          return next
        })
      }
    },
    [],
  )

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Открытая библиотека</div>
          <h1 className={page.title}>Каталог книг</h1>
          <p className={page.subtitle}>
            Свободные книги на английском — от Конан Дойла до Уэллса. Скачанная
            книга остаётся в этом браузере, как всякая книга библиотеки.
          </p>
        </div>
        <div className={page.headActions}>
          <Link to="/library" className={buttonClassName()}>
            <BackIcon size={15} /> Все книги
          </Link>
        </div>
      </header>

      <form
        className={styles.search}
        onSubmit={(event) => {
          event.preventDefault()
          void run()
        }}
      >
        <SearchField
          value={query}
          onChange={setQuery}
          label="Поиск по Открытой библиотеке"
          placeholder="Что почитать? Например, «Sherlock Holmes»"
        />
        <Button
          type="submit"
          variant="primary"
          disabled={!query.trim() || search.state === 'searching'}
        >
          {search.state === 'searching' ? 'Ищем…' : 'Искать'}
        </Button>
      </form>

      {problem && <p className={page.notice}>{problem}</p>}

      {search.state === 'failed' && <p className={page.notice}>{search.message}</p>}

      {search.state === 'idle' && (
        <p className={page.muted}>
          Каталог ищет по названию и по автору. Книги приходят целиком — файлом,
          а не ссылкой на чужой сайт.
        </p>
      )}

      {search.state === 'ready' && search.books.length === 0 && (
        <WolfyCompanion mood="calm" title="Ничего не нашлось" />
      )}

      {search.state === 'ready' && search.books.length > 0 && (
        <ul className={styles.results}>
          {search.books.map((book, index) => (
            <Appear as="li" key={book.id} index={index} className={styles.found}>
              <div className={styles.found__cover} aria-hidden="true">
                <span className={styles.found__coverRule} />
                <span className={styles.found__coverTitle}>{book.title}</span>
                {book.author && (
                  <span className={styles.found__coverAuthor}>{book.author}</span>
                )}
              </div>

              <div className={styles.found__body}>
                <h2 className={styles.found__title} lang="en">
                  {book.title}
                </h2>
                <p className={styles.found__meta}>{subtitleOf(book)}</p>

                {added.has(book.id) ? (
                  <Button
                    small
                    variant="secondary"
                    onClick={() =>
                      void navigate({
                        to: '/reader/$bookId',
                        params: { bookId: added.get(book.id)!.id },
                      })
                    }
                  >
                    В библиотеке — открыть
                  </Button>
                ) : (
                  <Button
                    small
                    variant="primary"
                    disabled={downloading.has(book.id) || book.urls.length === 0}
                    onClick={() => void download(book)}
                  >
                    {downloading.has(book.id)
                      ? 'Скачивается…'
                      : book.urls.length === 0
                        ? 'Файла нет'
                        : 'Скачать'}
                  </Button>
                )}
              </div>
            </Appear>
          ))}
        </ul>
      )}
    </div>
  )
}

function subtitleOf(book: CatalogueBook): string {
  const parts = [book.author, book.year > 0 ? String(book.year) : ''].filter(Boolean)
  return parts.length ? parts.join(' · ') : 'Автор неизвестен'
}

function reason(error: unknown): string {
  if (error instanceof OfflineError) return 'Сети нет. Каталог подождёт до связи.'
  if (error instanceof ApiError) return error.message
  return 'Каталог сейчас недоступен.'
}
