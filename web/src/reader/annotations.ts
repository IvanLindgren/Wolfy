/**
 * Выделения маркером и заметки к книге.
 *
 * Одна сущность на две вещи, потому что это и есть одна вещь: кусок книги,
 * который читатель чем-то отметил. Выделение — отметка цветом, заметка —
 * отметка словами, и очень часто это одно и то же место. Разводить их по двум
 * хранилищам значило бы потом склеивать обратно на экране заметок, где они
 * должны стоять вперемешку в порядке чтения.
 *
 * **Место хранится номером токена, а не пикселем и не номером страницы.**
 * Страница в книге с перетекающим текстом — величина непостоянная: сменил
 * кегль, и её больше нет. Номер токена внутри главы переживает и кегль, и
 * ширину колонки, и смену устройства — тем же способом ядро хранит место, на
 * котором книга закрыта.
 *
 * **Хранение — двухслойное.** Первый слой — файл на устройстве: заметки
 * работают без входа и без сети, как и сама книга. Второй — сервер для
 * вошедших: список целиком уезжает наверх после каждой правки и приезжает
 * при открытии книги, где сливается с местным.
 *
 * **Версия записи — пара (rev, writer), а не время правки.** `rev` — счётчик
 * Лампорта этой книги на этом устройстве: каждая правка получает `clock + 1`,
 * а слияние поднимает `clock` до наибольшей увиденной версии. Часы устройств
 * врут, счётчик — нет, и порядок (rev, writer) полный и детерминированный.
 * Следствие Лампорта здесь работает на читателя: устройство, видевшее
 * удаление, физически не может породить правку, которая ему проиграет.
 *
 * **Удаление — пометка, живущая до подтверждения всех.** Стирать пометки по
 * возрасту нельзя: устройство, пролежавшее в ящике месяц, воскресило бы
 * запись своей устаревшей копией. Поэтому пометки держатся в файле и на
 * сервере, пока каждое устройство не отчитается через поле `seen`, что
 * долговечно сохранило состояние с версией не ниже пометки. Сборку мусора
 * делает только сервер — он один видит всех.
 */

import { create } from 'zustand'

import { fetchBookAnnotations, pushBookAnnotations, type AnnotationItem } from '../api/client'
import { newId, now } from '../core/clock'
import { deviceInfo } from '../core/device'
import { readText, writeFile } from '../storage/opfs'

/** Номер краски маркера: `1`…`10`, дальше это `var(--hl-N)` в теме. */
export type Tone = 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10

export const TONES: { tone: Tone; title: string }[] = [
  { tone: 1, title: 'Жёлтый' },
  { tone: 2, title: 'Оранжевый' },
  { tone: 3, title: 'Розовый' },
  { tone: 4, title: 'Красный' },
  { tone: 5, title: 'Фиолетовый' },
  { tone: 6, title: 'Синий' },
  { tone: 7, title: 'Голубой' },
  { tone: 8, title: 'Зелёный' },
  { tone: 9, title: 'Оливковый' },
  { tone: 10, title: 'Серый' },
]

export function toneColor(tone: Tone): string {
  return `var(--hl-${tone})`
}

export interface Annotation {
  id: string
  chapter: number
  /** Полуинтервал номеров токенов главы. Для заметки к месту `end === start`. */
  start: number
  end: number
  /** Краска маркера. `null` — заметка без выделения. */
  tone: Tone | null
  /** Цитата из книги. У заметки к странице её может не быть. */
  quote: string
  /** Что читатель об этом думает. */
  note: string
  /** Номер правки в счётчике Лампорта писателя. */
  rev: number
  /** Стабильный номер устройства, подписавшего правку. */
  writer: string
  createdAt: number
  updatedAt: number
  /**
   * Пометка удаления.
   *
   * Удаление — это запись, а не отсутствие записи: иначе оно не доехало бы до
   * второго устройства, и заметка там воскресла бы из его локального файла.
   * Содержимое при удалении стирается — хранить текст того, что читатель
   * вычеркнул, значит шпионить за ним самим за собой.
   */
  deleted?: boolean
}

function pathOf(bookId: string): string {
  return `notes/${bookId}.json`
}

/** Стабильный номер этого устройства; в браузере — localStorage. */
let cachedWriter: string | null = null
function writerId(): string {
  cachedWriter ??= deviceInfo().id
  return cachedWriter
}

/**
 * Что лежит в файле.
 *
 * `clock` — счётчик Лампорта книги на этом устройстве: источник номеров
 * правок и память о том, что устройство уже видело. `seen` — наибольшая
 * версия **серверного** состояния, которую устройство долговечно сохранило;
 * это подтверждение для сборщика мусора, и оно сознательно не включает
 * собственные ещё не подтверждённые сервером правки.
 */
interface Stored {
  version: 2
  clock: number
  seen: number
  annotations: Annotation[]
}

const EMPTY: Stored = { version: 2, clock: 0, seen: 0, annotations: [] }

export async function loadAnnotations(bookId: string): Promise<Stored> {
  try {
    const text = await readText(pathOf(bookId))
    if (!text) return EMPTY
    const raw = JSON.parse(text) as { version?: unknown; annotations?: unknown }
    if (raw.version === 2 && Array.isArray(raw.annotations)) {
      return { ...EMPTY, ...(raw as unknown as Stored) }
    }
    if (raw.version === 1 && Array.isArray(raw.annotations)) {
      return migrateV1(raw as { annotations: Annotation[] })
    }
    return EMPTY
  } catch {
    // Битый файл — это потеря заметок, но не повод не открыть книгу.
    return EMPTY
  }
}

/**
 * Переезд с первой версии файла, жившей до (rev, writer).
 *
 * Старые записи версии не имели; роль версии играет время правки — на один
 * раз это честно: конфликты со старым форматом возможны только в день
 * переезда, и любой детерминированный порядок там лучше потери.
 */
function migrateV1(parsed: { annotations: Annotation[] }): Stored {
  const annotations = parsed.annotations.map((item) => ({
    ...item,
    rev: item.updatedAt > 0 ? item.updatedAt : 1,
    writer: writerId(),
  }))
  return {
    version: 2,
    clock: annotations.reduce((top, item) => Math.max(top, item.rev), 0),
    seen: 0,
    annotations,
  }
}

async function persist(bookId: string, stored: Stored): Promise<void> {
  await writeFile(pathOf(bookId), JSON.stringify(stored))
}

/**
 * Слияние двух списков одной книги.
 *
 * Победитель конфликта — большая пара (rev, writer); при полном равенстве —
 * больший слепок содержимого. Формат слепка совпадает с серверным: у обоих
 * сторон равные (rev, writer) обязаны выбрать одного и того же победителя,
 * иначе сервер и устройство разойдутся в выводе.
 *
 * Пометки удалений здесь не трогаются: их сборка — дело сервера, у которого
 * одного есть реестр подтверждений всех устройств.
 */
export function mergeAnnotations(
  local: Annotation[],
  remote: AnnotationItem[],
): { items: Annotation[]; differsFromLocal: boolean; differsFromRemote: boolean } {
  const byKey = (list: AnnotationItem[]) => new Map(list.map((item) => [item.id, item]))
  const fromLocal = byKey(local)
  const fromRemote = byKey(remote)

  const merged = new Map<string, Annotation>()
  for (const [id, item] of fromLocal) merged.set(id, item as Annotation)
  for (const [id, item] of fromRemote) {
    const current = merged.get(id)
    if (!current || later(item, current)) {
      // Сервер уже проверил, что краска лежит в 1..10.
      merged.set(id, { ...item, tone: item.tone as Tone | null })
    }
  }

  const items = [...merged.values()]
  return {
    items,
    differsFromLocal: differs(items, fromLocal),
    differsFromRemote: differs(items, fromRemote),
  }
}

/** Минимум полей, по которым сравниваются версии записи. */
type Versioned = {
  rev: number
  writer: string
  tone: number | null
  quote: string
  note: string
  createdAt: number
  deleted?: boolean
}

function later(candidate: Versioned, current: Versioned): boolean {
  if (candidate.rev !== current.rev) return candidate.rev > current.rev
  if (candidate.writer !== current.writer) return candidate.writer > current.writer
  return key(candidate) > key(current)
}

/** Слепок содержимого — тот же формат, что и на сервере. */
function key(item: Versioned): string {
  return `${item.tone ?? -1}|${item.quote}|${item.note}|${item.createdAt}|${item.deleted === true}`
}

function differs(merged: Annotation[], side: Map<string, AnnotationItem>): boolean {
  if (merged.length !== side.size) return true
  for (const item of merged) {
    const other = side.get(item.id)
    if (!other || later(item, other) || later(other, item)) return true
  }
  return false
}

/** Наибольшая версия списка — то, что увидевшее его устройство отчитает. */
function topRev(items: Annotation[]): number {
  return items.reduce((top, item) => Math.max(top, item.rev), 0)
}

interface AnnotationState {
  /** Какая книга сейчас загружена: заметки читаются по одной книге за раз. */
  bookId: string | null
  annotations: Annotation[]
  clock: number
  seen: number
  open: (bookId: string) => Promise<void>
  add: (input: NewAnnotation) => Promise<Annotation>
  update: (id: string, patch: Partial<Pick<Annotation, 'note' | 'tone'>>) => Promise<void>
  remove: (id: string) => Promise<void>
}

export interface NewAnnotation {
  chapter: number
  start: number
  end: number
  tone: Tone | null
  quote: string
  note: string
}

export const useAnnotations = create<AnnotationState>((set, get) => ({
  bookId: null,
  annotations: [],
  clock: 0,
  seen: 0,

  open: async (bookId) => {
    // Список меняем сразу, чтобы на экране не остались заметки прошлой книги
    // на те несколько кадров, пока читается файл новой.
    set({ bookId, annotations: [], clock: 0, seen: 0 })
    const local = await loadAnnotations(bookId)
    if (get().bookId !== bookId) return
    set({ annotations: local.annotations, clock: local.clock, seen: local.seen })

    // Дальше — сеть, и она ничего не должна блокировать: книга уже открыта,
    // местные заметки уже на экране. Сервер отвечает списком целиком; если
    // он расходится с местным, побеждает тот, кто новее — по (rev, writer).
    const remote = await fetchBookAnnotations(bookId, writerId(), local.seen)
    if (!remote || get().bookId !== bookId) return

    const { items, differsFromLocal, differsFromRemote } = mergeAnnotations(
      local.annotations,
      remote.items,
    )
    const next: Stored = {
      version: 2,
      clock: Math.max(local.clock, topRev(items)),
      seen: Math.max(local.seen, remote.topRev),
      annotations: items,
    }
    if (differsFromLocal) {
      set({ annotations: items, clock: next.clock, seen: next.seen })
      await persist(bookId, next)
    }
    if (differsFromLocal || differsFromRemote) schedulePush(bookId)
  },

  add: async (input) => {
    const { bookId, annotations, clock, seen } = get()
    const rev = clock + 1
    const moment = now()
    const annotation: Annotation = {
      id: newId(),
      ...input,
      rev,
      writer: writerId(),
      createdAt: moment,
      updatedAt: moment,
    }
    const next = [...annotations, annotation]
    set({ annotations: next, clock: rev })
    if (bookId) {
      await persist(bookId, { version: 2, clock: rev, seen, annotations: next })
      schedulePush(bookId)
    }
    return annotation
  },

  update: async (id, patch) => {
    const { bookId, annotations, clock, seen } = get()
    const rev = clock + 1
    const next = annotations.map((item) =>
      item.id === id ? { ...item, ...patch, rev, updatedAt: now() } : item,
    )
    set({ annotations: next, clock: rev })
    if (bookId) {
      await persist(bookId, { version: 2, clock: rev, seen, annotations: next })
      schedulePush(bookId)
    }
  },

  remove: async (id) => {
    const { bookId, annotations, clock, seen } = get()
    const rev = clock + 1
    // Пометка вместо стирания: второе устройство узнает про удаление только
    // так. Содержимое стирается вместе с записью.
    const next = annotations.map((item) =>
      item.id === id
        ? { ...item, deleted: true, tone: null, quote: '', note: '', rev, updatedAt: now() }
        : item,
    )
    set({ annotations: next, clock: rev })
    if (bookId) {
      await persist(bookId, { version: 2, clock: rev, seen, annotations: next })
      schedulePush(bookId)
    }
  },
}))

// --- Отправка наверх ----------------------------------------------------------

/**
 * Сколько ждать перед отправкой после последней правки.
 *
 * Перекрасить пять выделений подряд — это пять правок за десять секунд;
 * отправлять каждую означало бы пять одинаковых списков, отличающихся
 * последней строкой. Экспортируется ради тестов.
 */
export const PUSH_DEBOUNCE = 2_000

let pushTimer: ReturnType<typeof setTimeout> | null = null

/**
 * Ставит книгу в очередь на отправку.
 *
 * Ждёт одна отложенная задача, а не копятся задачи: пришедшая позже правка
 * просто заменяет собой ещё не отправленную. Ответ сервера — слитый список —
 * возвращается в местный файл: так пометки, собранные серверным сборщиком
 * мусора, не остаются на устройстве навсегда, а правки, сделанные, пока
 * ответ летел, в слиянии побеждают по (rev, writer).
 */
function schedulePush(bookId: string): void {
  if (pushTimer !== null) return
  pushTimer = setTimeout(() => {
    pushTimer = null
    void runPush(bookId)
  }, PUSH_DEBOUNCE)
}

async function runPush(bookId: string): Promise<void> {
  const store = useAnnotations.getState()
  if (store.bookId !== bookId) return

  const response = await pushBookAnnotations(bookId, writerId(), store.seen, store.annotations)
  if (!response) return

  // Пока ответ летел, читатель мог править дальше; сливаем с самым свежим
  // местным состоянием, а не с тем, что ушло наверх.
  const current = useAnnotations.getState()
  if (current.bookId !== bookId) return
  const { items, differsFromLocal } = mergeAnnotations(current.annotations, response.items)
  const next: Stored = {
    version: 2,
    clock: Math.max(current.clock, topRev(items)),
    seen: Math.max(current.seen, response.topRev),
    annotations: items,
  }
  if (differsFromLocal) {
    useAnnotations.setState({ annotations: items, clock: next.clock, seen: next.seen })
    await persist(bookId, next)
  }
}

/**
 * Порядок чтения.
 *
 * Заметки на экране книги стоят так, как встречаются в тексте, а не так, как
 * их создавали: читатель ищет «то место про волков», и помнит он его по книге,
 * а не по дате, когда до него добрался.
 */
export function inReadingOrder(annotations: Annotation[]): Annotation[] {
  return [...annotations].sort(
    (a, b) => a.chapter - b.chapter || a.start - b.start || a.createdAt - b.createdAt,
  )
}
