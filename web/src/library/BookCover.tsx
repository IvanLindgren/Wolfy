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
 * Достаёт обложку из файла книги.
 *
 * Открывать книгу ради картинки дорого, поэтому обложка тянется лениво и
 * только у книг, которые видно на экране. Не нашлась — набираем: это не
 * ошибка, у TXT обложек не бывает вовсе.
 */
function useCover(book: LibraryBook): string | null {
  const [url, setUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!book.path || book.format !== 'epub') return
    let alive = true
    let created: string | null = null

    void (async () => {
      try {
        const bytes = await bridge.cover(book.path)
        if (!bytes || !alive) return
        created = URL.createObjectURL(new Blob([bytes as BlobPart]))
        setUrl(created)
      } catch {
        // Битая или отсутствующая обложка — обычное дело. Наберём свою.
      }
    })()

    return () => {
      alive = false
      if (created) URL.revokeObjectURL(created)
    }
  }, [book.path, book.format])

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
