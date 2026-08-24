/**
 * Обложка книги.
 *
 * Настоящая, если она есть в файле; иначе набранная — название антиквой,
 * автор капителью, тонкая линейка сверху. Серый прямоугольник с иконкой
 * говорит только «картинки нет»; набранная обложка говорит, что это за книга,
 * и на полке из двадцати корешков это разница между поиском и узнаванием.
 */

import { useEffect, useState } from 'react'

import * as bridge from '../core/bridge'
import type { LibraryBook } from '../core/types'
import { customCover, useCoverStamp } from './covers'
import styles from './library.module.css'

export function BookCover({ book }: { book: LibraryBook }) {
  const cover = useCover(book)
  const percent = Math.round(fraction(book) * 100)

  return (
    <div className={styles.cover}>
      {cover ? (
        <img src={cover} alt="" loading="lazy" decoding="async" />
      ) : (
        <>
          <div className={styles.cover__rule} />
          <div className={styles.cover__title}>{book.title}</div>
          {book.author && <div className={styles.cover__author}>{book.author}</div>}
        </>
      )}
      {percent > 0 && (
        <div className={styles.cover__progress}>
          <div className={styles.cover__progressBar} style={{ width: `${percent}%` }} />
        </div>
      )}
    </div>
  )
}

/**
 * Достаёт обложку книги.
 *
 * Порядок важен: сначала та, что поставил читатель, и только потом та, что
 * лежит внутри EPUB. Своя обложка — это осознанный выбор, и издательская
 * картинка не должна его перебивать; книга, у которой читатель заменил
 * обложку, обязана выглядеть так, как он решил, а не так, как решило
 * издательство.
 *
 * Открывать книгу ради картинки дорого, поэтому обложка тянется лениво и
 * только у книг, которые видно на экране. Не нашлась ни одна — набираем: это
 * не ошибка, у TXT обложек не бывает вовсе.
 */
function useCover(book: LibraryBook): string | null {
  const [url, setUrl] = useState<string | null>(null)
  const stamp = useCoverStamp((state) => state.stamps[book.id] ?? 0)

  useEffect(() => {
    let alive = true
    let created: string | null = null

    const show = (bytes: Uint8Array) => {
      if (!alive) return false
      created = URL.createObjectURL(new Blob([bytes as BlobPart]))
      setUrl(created)
      return true
    }

    void (async () => {
      try {
        const own = await customCover(book.id)
        if (own && own.byteLength > 0) {
          show(own)
          return
        }
        if (!book.path || book.format !== 'epub') return
        const bytes = await bridge.cover(book.path)
        if (bytes) show(bytes)
      } catch {
        // Битая или отсутствующая обложка — обычное дело. Наберём свою.
      }
    })()

    return () => {
      alive = false
      if (created) URL.revokeObjectURL(created)
    }
  }, [book.id, book.path, book.format, stamp])

  return url
}

/** Прочитанная доля — тем же способом, что считает ядро. */
export function fraction(book: LibraryBook): number {
  if (book.chapters <= 0) return 0
  return Math.min(
    1,
    Math.max(0, (book.progress.chapter + book.progress.withinChapter) / book.chapters),
  )
}
