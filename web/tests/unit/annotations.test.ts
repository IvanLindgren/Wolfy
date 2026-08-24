/**
 * Заметки: слияние двух устройств и поведение хранилища.
 *
 * Слияние — единственное место, где заметки принимают решение, чья правка
 * верна. Ошибиться здесь значит потерять чужую заметку или воскресить
 * удалённую; оба случая читатель замечает и обоих прощает один раз.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PUSH_DEBOUNCE,
  applyServerSnapshot,
  mergeAnnotations,
  useAnnotations,
} from '../../src/reader/annotations'
import type { Annotation } from '../../src/reader/annotations'

const files = vi.hoisted(() => new Map<string, string>())
const server = vi.hoisted(() => ({
  books: new Map<string, { items: Annotation[]; generation: number }>(),
  pulls: [] as { device: string; seen: number }[],
  pushes: [] as { device: string; seen: number }[],
}))

vi.mock('../../src/storage/opfs', () => ({
  readText: async (path: string) => files.get(path) ?? '',
  writeFile: async (path: string, text: string) => {
    files.set(path, text)
  },
}))

// Мини-сервер повторяет настоящий протокол: слияние по (rev, writer),
// поколение растёт вместе с состоянием, все записи штампуются им.
vi.mock('../../src/api/client', () => ({
  fetchBookAnnotations: async (bookId: string, device: string, seen: number) => {
    server.pulls.push({ device, seen })
    const stored = server.books.get(bookId)
    return stored
      ? { items: structuredClone(stored.items), generation: stored.generation }
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
    const stored = server.books.get(bookId)
    const base = stored?.items ?? []
    const { items: merged } = mergeAnnotations(base, list)
    const changed = JSON.stringify(merged) !== JSON.stringify(base)
    const generation = changed ? (stored?.generation ?? 0) + 1 : (stored?.generation ?? 0)
    const stamped = merged.map((item) => ({ ...item, generation }))
    server.books.set(bookId, { items: structuredClone(stamped), generation })
    return { items: structuredClone(stamped), generation }
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
  generation: 0,
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

  it('порядок покрывает все поля, а не только текст', () => {
    // Раньше равные (rev, writer) сравнивались слепком текстов, и записи,
    // отличающиеся только местом, решались по-разному в зависимости от
    // порядка сторон.
    const base = item({ start: 10 })
    const moved = item({ start: 20 })

    const forward = mergeAnnotations([base], [moved]).items[0]
    const backward = mergeAnnotations([moved], [base]).items[0]
    expect(forward).toEqual(backward)
    expect(forward?.start).toBe(20)
  })

  it('заметки с разных устройств объединяются', () => {
    const local = [item({ id: 'phone', chapter: 0, start: 1, end: 2, rev: 1 })]
    const remote = [item({ id: 'laptop', chapter: 1, start: 5, end: 6, rev: 4 })]

    const merged = mergeAnnotations(local, remote)
    expect(merged.items).toHaveLength(2)
    expect(merged.differsFromLocal).toBe(true)
    expect(merged.differsFromRemote).toBe(true)
  })

  it('удаление доезжает до другого устройства пометкой', () => {
    const local = [item({})]
    const remote = [item({ deleted: true, tone: null, quote: '', note: '', rev: 3 })]

    const merged = mergeAnnotations(local, remote)
    expect(merged.differsFromLocal).toBe(true)
    expect(merged.items.filter((entry) => !entry.deleted)).toHaveLength(0)
  })

  it('устаревшая копия не воскрешает удалённое', () => {
    const tombstone = item({ deleted: true, rev: 5 })
    const stale = [item({ rev: 4 })]

    expect(mergeAnnotations(stale, [tombstone]).items[0]?.deleted).toBe(true)
    expect(mergeAnnotations([tombstone], stale).items[0]?.deleted).toBe(true)
  })

  it('коммутативна, ассоциативна и идемпотентна на случайных списках', () => {
    const rng = mulberry32(7)
    // Результат слияния не отсортирован — порядок записей в массиве зависит
    // от порядка сторон, и сравнивать его надо безразлично к нему.
    const byId = (items: Annotation[]) =>
      [...items].sort((x, y) => (x.id < y.id ? -1 : x.id > y.id ? 1 : 0))

    for (let round = 0; round < 200; round += 1) {
      const a = randomItems(rng)
      const b = randomItems(rng)
      const c = randomItems(rng)

      expect(byId(mergeAnnotations(a, b).items)).toEqual(byId(mergeAnnotations(b, a).items))
      expect(byId(mergeAnnotations(mergeAnnotations(a, b).items, c).items)).toEqual(
        byId(mergeAnnotations(a, mergeAnnotations(b, c).items).items),
      )
      expect(byId(mergeAnnotations(a, a).items)).toEqual(byId(mergeAnnotations(a, []).items))
    }
  })
})

describe('applyServerSnapshot', () => {
  it('собранная сервером пометка исчезает из местного списка', () => {
    const local = [item({ deleted: true, tone: null, quote: '', note: '', generation: 1 })]
    // Сервер уже собрал пометку: снимок поколения 1 приходит без неё.
    const applied = applyServerSnapshot(local, [], 1)

    expect(applied.items).toHaveLength(0)
    expect(applied.differsFromLocal).toBe(true)
  })

  it('непроштампованная пометка не трогается: сервер о ней ещё не знает', () => {
    const local = [item({ deleted: true, tone: null, quote: '', note: '', generation: 0 })]
    const applied = applyServerSnapshot(local, [], 3)

    expect(applied.items).toHaveLength(1)
  })

  it('пометка из более свежего снимка, чем местное поколение, остаётся', () => {
    const local = [item({ deleted: true, tone: null, quote: '', note: '', generation: 5 })]
    // Снимок поколения 3 о пометке поколения 5 знать не мог.
    const applied = applyServerSnapshot(local, [], 3)

    expect(applied.items).toHaveLength(1)
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

  afterEach(async () => {
    // Отложенный пуш, не дождавшийся своего часа в тесте, спускается здесь:
    // иначе его таймер остался бы взведённым на следующий тест, и тот молча
    // потерял бы собственную отправку.
    await vi.advanceTimersByTimeAsync(PUSH_DEBOUNCE + 1)
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
        { ...item({ id: 'mine', note: 'чужое новее', rev: 5, writer: 'laptop', generation: 7 }) },
        { ...item({ id: 'cloud', chapter: 3, start: 0, end: 4, rev: 7, writer: 'laptop', generation: 7 }) },
      ],
      generation: 7,
    })

    await useAnnotations.getState().open('b1')

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

  it('правка получает версию clock + 1, уезжает наверх и приезжает проштампованной', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({ version: 2, clock: 4, seen: 0, annotations: [] }),
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
    expect(stored.seen).toBe(0)
    expect(server.books.has('b1')).toBe(false)

    await pushed()
    expect(server.pushes[0]?.seen).toBe(0)
    const sent = server.books.get('b1')!
    expect(sent.items).toHaveLength(1)
    expect(sent.items[0]?.id).toBe(added.id)
    expect(sent.generation).toBe(1)

    // Ответ сервера возвращает штамп поколения в местный файл.
    const state = useAnnotations.getState()
    expect(state.annotations[0]?.generation).toBe(1)
    expect(state.seen).toBe(1)
  })

  it('собранная сервером пометка уходит из местного файла', async () => {
    files.set(
      pathOf('b1'),
      JSON.stringify({
        version: 2,
        clock: 2,
        seen: 1,
        annotations: [item({ deleted: true, tone: null, quote: '', note: '', rev: 2, generation: 1 })],
      }),
    )
    // Сервер пометку уже собрал: снимок поколения 1 без неё.
    server.books.set('b1', { items: [], generation: 1 })

    await useAnnotations.getState().open('b1')

    expect(useAnnotations.getState().annotations).toHaveLength(0)
    const stored = JSON.parse(files.get(pathOf('b1'))!) as { annotations: Annotation[] }
    expect(stored.annotations).toHaveLength(0)
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
        annotations: [{ ...item({ rev: 0, writer: '', generation: 0 }), updatedAt: 42 }],
      }),
    )

    await useAnnotations.getState().open('b1')

    const migrated = useAnnotations.getState().annotations[0]
    expect(migrated?.rev).toBe(42)
    expect(migrated?.writer).toBeTruthy()
    expect(useAnnotations.getState().clock).toBe(42)
  })
})

// --- Случайные списки для property-тестов -------------------------------------

function mulberry32(seed: number): () => number {
  let state = seed
  return () => {
    state += 0x6d2b79f5
    let value = state
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

function randomItems(rng: () => number): Annotation[] {
  const pool = 20
  const ids = shuffle([...Array(pool).keys()], rng).slice(0, Math.floor(rng() * pool) + 1)
  const writers = ['aaa', 'bbb', 'ccc']
  const texts = ['', 'мысль', 'z']
  return ids.map((number) =>
    item({
      id: `w${number}`,
      chapter: Math.floor(rng() * 4),
      start: Math.floor(rng() * 30),
      end: Math.floor(rng() * 30) + 30,
      tone: rng() < 0.3 ? null : ((Math.floor(rng() * 10) + 1) as 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10),
      quote: texts[Math.floor(rng() * 3)]!,
      note: texts[Math.floor(rng() * 3)]!,
      rev: Math.floor(rng() * 10) + 1,
      writer: writers[Math.floor(rng() * 3)]!,
      generation: Math.floor(rng() * 4),
      createdAt: Math.floor(rng() * 5),
      updatedAt: Math.floor(rng() * 5),
      deleted: rng() < 0.5,
    }),
  )
}

function shuffle<T>(values: T[], rng: () => number): T[] {
  for (let index = values.length - 1; index > 0; index -= 1) {
    const pick = Math.floor(rng() * (index + 1))
    ;[values[index], values[pick]] = [values[pick]!, values[index]!]
  }
  return values
}
