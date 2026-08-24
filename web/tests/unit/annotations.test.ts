/**
 * Заметки: слияние двух устройств и поведение хранилища.
 *
 * Слияние — единственное место, где заметки принимают решение, чья правка
 * верна. Ошибиться здесь значит потерять чужую заметку или воскресить
 * удалённую; оба случая читатель замечает и обоих прощает один раз.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PUSH_DEBOUNCE, mergeAnnotations, useAnnotations } from '../../src/reader/annotations'
import type { Annotation } from '../../src/reader/annotations'

const files = vi.hoisted(() => new Map<string, string>())
const server = vi.hoisted(() => ({
  books: new Map<string, { items: Annotation[]; topRev: number }>(),
  pulls: [] as { device: string; seen: number }[],
  pushes: [] as { device: string; seen: number }[],
}))

vi.mock('../../src/storage/opfs', () => ({
  readText: async (path: string) => files.get(path) ?? '',
  writeFile: async (path: string, text: string) => {
    files.set(path, text)
  },
}))

vi.mock('../../src/api/client', () => ({
  fetchBookAnnotations: async (bookId: string, device: string, seen: number) => {
    server.pulls.push({ device, seen })
    const stored = server.books.get(bookId)
    return stored
      ? { items: structuredClone(stored.items), topRev: stored.topRev }
      : null
  },
  pushBookAnnotations: async (
    bookId: string,
    device: string,
    seen: number,
    items: unknown,
  ) => {
    server.pushes.push({ device, seen })
    const list = items as Annotation[]
    server.books.set(bookId, {
      items: structuredClone(list),
      topRev: list.reduce((top, item) => Math.max(top, item.rev), 0),
    })
    return { items: structuredClone(list), topRev: server.books.get(bookId)!.topRev }
  },
}))

const NOW = Date.now()

const item = (over: Partial<Annotation>): Annotation => ({
  id: 'a',
  chapter: 0,
  start: 3,
  end: 7,
  tone: 2,
  quote: 'to be',
  note: '',
  rev: 1,
  writer: 'phone',
  createdAt: NOW - 1_000,
  updatedAt: NOW - 1_000,
  ...over,
})

describe('mergeAnnotations', () => {
  it('большая версия побеждает в обе стороны', () => {
    const local = [item({ note: 'новое', rev: 2 })]
    const remote = [item({ note: 'старое', rev: 1 })]

    expect(mergeAnnotations(local, remote).items[0]?.note).toBe('новое')
    expect(mergeAnnotations(remote, local).items[0]?.note).toBe('новое')
  })

  it('равные версии решаются писателем, тоже в обе стороны', () => {
    const left = [item({ note: 'первое', writer: 'aaa' })]
    const right = [item({ note: 'второе', writer: 'bbb' })]

    expect(mergeAnnotations(left, right).items[0]?.note).toBe('второе')
    expect(mergeAnnotations(right, left).items[0]?.note).toBe('второе')
  })

  it('заметки с разных устройств объединяются', () => {
    const local = [item({ id: 'phone', chapter: 0, start: 1, end: 2, rev: 1 })]
    const remote = [item({ id: 'laptop', chapter: 1, start: 5, end: 6, rev: 4 })]

    const merged = mergeAnnotations(local, remote)
    expect(merged.items).toHaveLength(2)
    expect(merged.differsFromLocal).toBe(true)
    expect(merged.differsFromRemote).toBe(true)
  })

  it('одинаковые списки не требуют ни записи, ни отправки', () => {
    const same = [item({}), item({ id: 'b', chapter: 0, start: 8, end: 9 })]
    const merged = mergeAnnotations(same, [...same].reverse())
    expect(merged.differsFromLocal).toBe(false)
    expect(merged.differsFromRemote).toBe(false)
  })

  it('удаление доезжает до другого устройства пометкой', () => {
    const local = [item({})]
    const remote = [item({ deleted: true, tone: null, quote: '', note: '', rev: 3 })]

    const merged = mergeAnnotations(local, remote)
    expect(merged.differsFromLocal).toBe(true)
    expect(merged.items.filter((entry) => !entry.deleted)).toHaveLength(0)
  })

  it('пометки удалений в слиянии не трогаются: сборка — дело сервера', () => {
    const ancient = item({ deleted: true, rev: 1, updatedAt: 1 })
    const merged = mergeAnnotations([], [ancient])
    expect(merged.items).toHaveLength(1)
  })

  it('устаревшая копия не воскрешает удалённое', () => {
    const tombstone = item({ deleted: true, rev: 5 })
    const stale = [item({ rev: 4 })]

    expect(mergeAnnotations(stale, [tombstone]).items[0]?.deleted).toBe(true)
    expect(mergeAnnotations([tombstone], stale).items[0]?.deleted).toBe(true)
  })
})

describe('хранилище заметок', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    files.clear()
    server.books.clear()
    server.pulls.length = 0
    server.pushes.length = 0
    useAnnotations.setState({ bookId: null, annotations: [], clock: 0, seen: 0 })
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  const pathOf = (bookId: string) => `notes/${bookId}.json`
  const pushed = async () => {
    await vi.advanceTimersByTimeAsync(PUSH_DEBOUNCE + 1)
  }

  it('открытие без сети работает от местного файла и не шлёт ничего', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({ version: 2, clock: 3, seen: 0, annotations: [item({})] }),
    )

    await useAnnotations.getState().open('b1')

    const state = useAnnotations.getState()
    expect(state.annotations).toHaveLength(1)
    expect(state.clock).toBe(3)
    expect(server.books.size).toBe(0)
  })

  it('при открытии подтверждение передаётся, а чужое приезжает в файл', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({
        version: 2,
        clock: 1,
        seen: 1,
        annotations: [item({ id: 'mine', rev: 1 })],
      }),
    )
    server.books.set('b1', {
      items: [
        { ...item({ id: 'mine', note: 'чужое новее', rev: 5, writer: 'laptop' }) },
        { ...item({ id: 'cloud', chapter: 3, start: 0, end: 4, rev: 7, writer: 'laptop' }) },
      ],
      topRev: 7,
    })

    await useAnnotations.getState().open('b1')
    await pushed()

    expect(server.pulls[0]?.seen).toBe(1)

    const state = useAnnotations.getState()
    expect(state.annotations.find((entry) => entry.id === 'mine')?.note).toBe('чужое новее')
    expect(state.annotations.find((entry) => entry.id === 'cloud')).toBeTruthy()
    // Увидев версию 7, счётчик обязан подняться не ниже неё: следующая
    // правка не может проиграть тому, что устройство уже видело.
    expect(state.clock).toBe(7)
    expect(state.seen).toBe(7)

    const stored = JSON.parse(files.get(pathOf('b1'))!) as {
      clock: number
      seen: number
    }
    expect(stored.clock).toBe(7)
    expect(stored.seen).toBe(7)
  })

  it('правка получает версию clock + 1 и уезжает наверх с подтверждением', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({ version: 2, clock: 4, seen: 3, annotations: [] }),
    )
    await useAnnotations.getState().open('b1')

    const added = await useAnnotations.getState().add({
      chapter: 2,
      start: 4,
      end: 6,
      tone: null,
      quote: 'home',
      note: 'первая',
    })

    expect(added.rev).toBe(5)
    expect(added.writer).toBeTruthy()

    const stored = JSON.parse(files.get(pathOf('b1'))!) as {
      annotations: Annotation[]
      seen: number
    }
    expect(stored.annotations[0]?.note).toBe('первая')
    // Собственная правка подтверждением серверу не считается.
    expect(stored.seen).toBe(3)
    expect(server.books.has('b1')).toBe(false)

    await pushed()
    expect(server.pushes[0]?.seen).toBe(3)
    const sent = server.books.get('b1')!
    expect(sent.items).toHaveLength(1)
    expect(sent.items[0]?.id).toBe(added.id)
  })

  it('удаление ставит пометку и доезжает до сервера ею же', async () => {
    await useAnnotations.getState().open('b1')
    const added = await useAnnotations.getState().add({
      chapter: 0,
      start: 1,
      end: 2,
      tone: 3,
      quote: 'q',
      note: 'n',
    })
    await useAnnotations.getState().remove(added.id)

    const state = useAnnotations.getState().annotations
    expect(state).toHaveLength(1)
    expect(state[0]).toMatchObject({ deleted: true, note: '', quote: '' })

    await pushed()
    const sent = server.books.get('b1')!
    expect(sent.items[0]?.deleted).toBe(true)
    expect(sent.items[0]?.rev).toBeGreaterThan(added.rev)
  })

  it('битый местный файл не роняет открытие', async () => {
    files.set(pathOf('b1'), '{ сломанный json')
    await useAnnotations.getState().open('b1')
    expect(useAnnotations.getState().annotations).toHaveLength(0)
  })

  it('файл первой версии переезжает на (rev, writer)', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({
        version: 1,
        annotations: [{ ...item({ rev: 0, writer: '' }), updatedAt: 42 }],
      }),
    )

    await useAnnotations.getState().open('b1')

    const migrated = useAnnotations.getState().annotations[0]
    expect(migrated?.rev).toBe(42)
    expect(migrated?.writer).toBeTruthy()
    expect(useAnnotations.getState().clock).toBe(42)
  })
})
