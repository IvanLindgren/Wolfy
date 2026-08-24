/**
 * Библиотека: книги, полки, добавление.
 *
 * Перетаскивание здесь делает две разные вещи, и обе — dnd-kit:
 * порядок книг внутри полки и перекладывание книги с полки на полку.
 * Сортировка «перетаскиванием» — единственный порядок, который читатель может
 * задать сам; всё остальное (по дате, по названию) приложение и так знает.
 *
 * Свой порядок книг хранится **на устройстве**: в ядре у книги нет поля
 * порядка, и заводить его только ради веба значило бы завести вторую модель
 * данных. Полка и прогресс синхронизируются; порядок корешков на экране — нет,
 * и это честнее, чем притворяться.
 */

import {
  useCallback,
  useMemo,
  useRef,
  useState,
  type DragEvent as ReactDragEvent,
  type FormEvent,
} from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'

import { toast } from '../app/toasts'
import { session, useSession } from '../core/session'
import type { LibraryBook } from '../core/types'
import { Appear } from '../widgets/Appear'
import { Button, buttonClassName } from '../widgets/Button'
import { CameraIcon, DecksIcon, PlusIcon, TrashIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import { BookCover, fraction } from './BookCover'
import { droppedFiles, isFileDrag } from './drop'
import { ACCEPTED, addFile, addURL, type ImportResult } from './import'
import styles from './library.module.css'
import { bookOrder, saveBookOrder } from './order'

/** Книги без полки лежат здесь. */
const LOOSE = '__loose__'

export function LibraryScreen() {
  const books = useSession((state) => state.library.books)
  const shelves = useSession((state) => state.library.shelves)
  const cards = useSession((state) => state.library.cards)
  const ready = useSession((state) => state.ready)
  const navigate = useNavigate()

  const [dragging, setDragging] = useState<LibraryBook | null>(null)
  const [order, setOrder] = useState<string[]>(bookOrder)
  const [fileOver, setFileOver] = useState(false)
  const [urlOpen, setURLOpen] = useState(false)
  const [address, setAddress] = useState('')
  const [urlBusy, setURLBusy] = useState(false)
  const chooser = useRef<HTMLInputElement>(null)
  const fileDragDepth = useRef(0)

  const sensors = useSensors(
    // Восемь пикселей до старта перетаскивания: без порога обычное нажатие на
    // книгу превращалось бы в микро-перетаскивание и не открывало бы её.
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const visible = useMemo(
    () => sortBooks(books.filter((book) => !book.deleted), order),
    [books, order],
  )

  const wordsOf = useCallback(
    (bookId: string) =>
      cards.filter((card) => card.bookId === bookId && !card.deleted).length,
    [cards],
  )

  const onDragStart = useCallback(
    (event: DragStartEvent) => {
      setDragging(visible.find((book) => book.id === event.active.id) ?? null)
    },
    [visible],
  )

  const onDragEnd = useCallback(
    (event: DragEndEvent) => {
      setDragging(null)
      const { active, over } = event
      if (!over) return

      const moved = visible.find((book) => book.id === active.id)
      if (!moved) return

      // Бросили на полку — перекладываем; бросили на книгу — меняем порядок.
      if (typeof over.id === 'string' && over.id.startsWith('shelf:')) {
        const shelf = over.id.slice('shelf:'.length)
        void session.moveToShelf(moved.id, shelf === LOOSE ? null : shelf)
        return
      }

      if (active.id === over.id) return
      const next = visible.map((book) => book.id)
      const from = next.indexOf(String(active.id))
      const to = next.indexOf(String(over.id))
      if (from < 0 || to < 0) return
      next.splice(to, 0, ...next.splice(from, 1))
      setOrder(next)
      saveBookOrder(next)
    },
    [visible],
  )

  const finishImport = useCallback((result: ImportResult) => {
    if (result.kind === 'refused') {
      toast(result.message)
    } else if (result.kind === 'known') {
      toast('Эта книга уже в библиотеке', {
        label: 'Открыть',
        run: () => void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } }),
      })
    } else {
      void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } })
    }
  }, [navigate])

  const pick = useCallback(async (files: FileList | File[] | null) => {
    if (!files) return
    for (const file of Array.from(files)) {
      finishImport(await addFile(file))
    }
  }, [finishImport])

  const onFileDragEnter = useCallback((event: ReactDragEvent<HTMLDivElement>) => {
    if (!isFileDrag(event.dataTransfer.types)) return
    event.preventDefault()
    // Shell умеет принимать файл на любом экране. В библиотеке владелец
    // импорта — эта зона: без остановки всплытия один drop обрабатывался дважды.
    event.stopPropagation()
    fileDragDepth.current += 1
    setFileOver(true)
  }, [])

  const onFileDragOver = useCallback((event: ReactDragEvent<HTMLDivElement>) => {
    if (!isFileDrag(event.dataTransfer.types)) return
    event.preventDefault()
    event.stopPropagation()
    event.dataTransfer.dropEffect = 'copy'
  }, [])

  const onFileDragLeave = useCallback((event: ReactDragEvent<HTMLDivElement>) => {
    if (fileDragDepth.current === 0) return
    event.preventDefault()
    event.stopPropagation()
    fileDragDepth.current = Math.max(0, fileDragDepth.current - 1)
    if (fileDragDepth.current === 0) setFileOver(false)
  }, [])

  const onFileDrop = useCallback((event: ReactDragEvent<HTMLDivElement>) => {
    const files = droppedFiles(event.dataTransfer)
    if (!files.length) return
    event.preventDefault()
    event.stopPropagation()
    fileDragDepth.current = 0
    setFileOver(false)
    void pick(files)
  }, [pick])

  const submitURL = useCallback(async (event: FormEvent) => {
    event.preventDefault()
    if (urlBusy) return
    setURLBusy(true)
    const result = await addURL(address)
    setURLBusy(false)
    finishImport(result)
    if (result.kind !== 'refused') {
      setAddress('')
      setURLOpen(false)
    }
  }, [address, finishImport, urlBusy])

  const groups = useMemo(() => {
    const named = shelves.map((shelf) => ({
      name: shelf.name,
      books: visible.filter((book) => book.shelf === shelf.name),
    }))
    return [
      { name: LOOSE, books: visible.filter((book) => !book.shelf) },
      ...named,
    ]
  }, [shelves, visible])

  return (
    <div
      className={`${page.page} ${page['page--wide']}`}
      onDragEnter={onFileDragEnter}
      onDragOver={onFileDragOver}
      onDragLeave={onFileDragLeave}
      onDrop={onFileDrop}
    >
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Библиотека</div>
          <h1 className={page.title}>Книги</h1>
        </div>
        <div className={page.headActions}>
          <Link to="/library/words" className={buttonClassName()}>
            <DecksIcon size={16} /> Все слова
          </Link>
          <Link to="/photo" className={buttonClassName()}>
            <CameraIcon size={16} /> Страница по фото
          </Link>
          <Button variant="primary" onClick={() => chooser.current?.click()}>
            <PlusIcon size={16} /> Добавить книгу
          </Button>
        </div>
      </header>

      <input
        ref={chooser}
        type="file"
        accept={ACCEPTED}
        multiple
        hidden
        onChange={(event) => {
          void pick(event.target.files)
          event.target.value = ''
        }}
      />

      <section className={styles.importer} data-file-over={fileOver}>
        <div className={styles.importer__prompt}>
          <span className={styles.importer__formats}>EPUB · PDF · TXT</span>
          <strong>{fileOver ? 'Отпускайте — добавим в библиотеку' : 'Перетащите книгу сюда'}</strong>
          <Button small variant="quiet" onClick={() => setURLOpen((open) => !open)}>
            {urlOpen ? 'Скрыть ссылку' : 'Добавить по ссылке'}
          </Button>
        </div>
        {urlOpen && (
          <form className={styles.importer__url} onSubmit={(event) => void submitURL(event)}>
            <input
              className={page.input}
              type="url"
              inputMode="url"
              value={address}
              onChange={(event) => setAddress(event.target.value)}
              placeholder="https://example.org/book.epub"
              aria-label="HTTPS-ссылка на книгу"
              required
              autoFocus
            />
            <Button type="submit" variant="primary" disabled={urlBusy}>
              {urlBusy ? 'Загружаем…' : 'Добавить'}
            </Button>
          </form>
        )}
      </section>

      {ready && visible.length === 0 ? (
        <WolfyCompanion mood="calm" title="Здесь пока пусто" />
      ) : (
        <DndContext sensors={sensors} onDragStart={onDragStart} onDragEnd={onDragEnd}>
          {groups.map((group) =>
            group.name === LOOSE ? (
              <Loose key={LOOSE} books={group.books} wordsOf={wordsOf} />
            ) : (
              <Shelf
                key={group.name}
                name={group.name}
                books={group.books}
                wordsOf={wordsOf}
              />
            ),
          )}

          <NewShelf />

          <DragOverlay>
            {dragging && (
              <div style={{ width: '9.5rem', pointerEvents: 'none' }}>
                <BookCover book={dragging} />
              </div>
            )}
          </DragOverlay>
        </DndContext>
      )}
    </div>
  )
}

function Loose({
  books,
  wordsOf,
}: {
  books: LibraryBook[]
  wordsOf: (id: string) => number
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `shelf:${LOOSE}` })

  return (
    <section className={page.section} ref={setNodeRef} data-over={isOver}>
      <div className={page.sectionHead}>
        <h2 className={page.sectionTitle}>Все книги</h2>
        <span className={page.sectionRule} />
        <span className={styles.shelf__count}>{books.length}</span>
      </div>
      <BookGrid books={books} wordsOf={wordsOf} />
    </section>
  )
}

function Shelf({
  name,
  books,
  wordsOf,
}: {
  name: string
  books: LibraryBook[]
  wordsOf: (id: string) => number
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `shelf:${name}` })

  return (
    <section className={styles.shelf} ref={setNodeRef} data-over={isOver}>
      <div className={styles.shelf__head}>
        <h2 className={styles.shelf__name}>{name}</h2>
        <span className={styles.shelf__count}>
          {books.length ? `${books.length}` : 'пусто'}
        </span>
        <div className={styles.shelf__actions}>
          <Button
            variant="quiet"
            small
            onClick={() => void session.removeShelf(name)}
            title="Убрать полку. Книги останутся в библиотеке."
          >
            Убрать полку
          </Button>
        </div>
      </div>
      {books.length ? (
        <BookGrid books={books} wordsOf={wordsOf} />
      ) : (
        <p className={styles.shelf__hint}>Перетащите сюда книгу.</p>
      )}
    </section>
  )
}

function BookGrid({
  books,
  wordsOf,
}: {
  books: LibraryBook[]
  wordsOf: (id: string) => number
}) {
  return (
    <SortableContext items={books.map((book) => book.id)} strategy={rectSortingStrategy}>
      <div className={styles.grid}>
        {books.map((book, index) => (
          <BookTile key={book.id} book={book} words={wordsOf(book.id)} index={index} />
        ))}
      </div>
    </SortableContext>
  )
}

function BookTile({
  book,
  words,
  index,
}: {
  book: LibraryBook
  words: number
  index: number
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: book.id })

  const percent = Math.round(fraction(book) * 100)

  return (
    <Appear index={index}>
      <div
        ref={setNodeRef}
        className={styles.book}
        data-dragging={isDragging}
        style={{ transform: CSS.Transform.toString(transform), transition }}
      >
        <Link to="/reader/$bookId" params={{ bookId: book.id }} className={styles.book}>
          <BookCover book={book} />
          <span className={styles.book__title}>{book.title}</span>
        </Link>
        <div className={styles.book__meta}>
          <span>{percent}%</span>
          {words > 0 && (
            <Link
              to="/library/$bookId/words"
              params={{ bookId: book.id }}
              className={styles.book__words}
            >
              {words} слов
            </Link>
          )}
          <div className={styles.book__actions}>
            <button
              type="button"
              className={styles.book__drag}
              title="Переместить книгу"
              aria-label={`Переместить «${book.title}»`}
              {...attributes}
              {...listeners}
            >
              <span aria-hidden="true">⠿</span>
            </button>
            <Button
              variant="quiet"
              small
              title="Удалить книгу"
              aria-label={`Удалить «${book.title}»`}
              onClick={() => {
                void session.removeBook(book.id)
                toast(`«${book.title}» убрана из библиотеки`)
              }}
            >
              <TrashIcon size={15} />
            </Button>
          </div>
        </div>
      </div>
    </Appear>
  )
}

function NewShelf() {
  const [name, setName] = useState('')
  const [open, setOpen] = useState(false)

  if (!open) {
    return (
      <Button variant="quiet" onClick={() => setOpen(true)}>
        <PlusIcon size={16} /> Новая полка
      </Button>
    )
  }

  return (
    <form
      className={page.row}
      onSubmit={(event) => {
        event.preventDefault()
        const clean = name.trim()
        if (!clean) return
        void session.addShelf(clean)
        setName('')
        setOpen(false)
      }}
    >
      <input
        className={page.input}
        style={{ maxWidth: '16rem' }}
        value={name}
        onChange={(event) => setName(event.target.value)}
        placeholder="Например, «Классика»"
        aria-label="Название полки"
        autoFocus
      />
      <Button type="submit" variant="primary">
        Создать
      </Button>
      <Button variant="quiet" onClick={() => setOpen(false)}>
        Отмена
      </Button>
    </form>
  )
}

/**
 * Порядок книг: сначала заданный читателем, затем — по дате добавления.
 *
 * Новая книга всегда наверху и не требует перетаскивания: чаще всего её и
 * открывают следующей.
 */
function sortBooks(books: LibraryBook[], order: string[]): LibraryBook[] {
  const position = new Map(order.map((id, index) => [id, index]))
  return [...books].sort((a, b) => {
    const left = position.get(a.id)
    const right = position.get(b.id)
    if (left !== undefined && right !== undefined) return left - right
    if (left !== undefined) return -1
    if (right !== undefined) return 1
    return b.addedAt - a.addedAt
  })
}
