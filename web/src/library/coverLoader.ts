/**
 * Ограничитель параллельных извлечений обложек из EPUB.
 *
 * Открыть 100 EPUB одновременно при заходе в библиотеку — значит открыть 100
 * zip-архивов, распаковать центральный каталог и держать их в памяти. Лимит
 * в 3 держит эту операцию в узде и не трогает custom covers (они локально в OPFS).
 */

let active = 0
const waiting: Array<() => void> = []
const LIMIT = 3

function acquire(): Promise<void> {
  if (active < LIMIT) {
    active += 1
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => {
    waiting.push(() => {
      active += 1
      resolve()
    })
  })
}

function release(): void {
  active = Math.max(0, active - 1)
  const next = waiting.shift()
  if (next) next()
}

export async function withCoverSlot<T>(fn: () => Promise<T>): Promise<T> {
  await acquire()
  try {
    return await fn()
  } finally {
    release()
  }
}

export function coverSlotInfoForTests() {
  return { active, waiting: waiting.length }
}
export function resetCoverLoaderForTests() {
  active = 0
  waiting.length = 0
}
