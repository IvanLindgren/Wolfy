/**
 * Ограничитель параллельных выгрузок картинок из WASM.
 *
 * В главе может быть десятки иллюстраций; одновременный Promise.all на
 * bridge.resource приводит к вскрытию EPUB десятки раз параллельно и к 30
 * одновременным blob URLs. Лимит в 2 запроса держит память и CPU в узде.
 */

import * as bridge from '../core/bridge'

const LIMIT = 2
let active = 0
const queue: Array<() => void> = []

function acquire(): Promise<void> {
  if (active < LIMIT) {
    active += 1
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => {
    queue.push(() => {
      active += 1
      resolve()
    })
  })
}

function release(): void {
  active = Math.max(0, active - 1)
  const next = queue.shift()
  if (next) next()
}

export async function limitedResource(bookId: string, path: string): Promise<Uint8Array | null> {
  await acquire()
  try {
    return await bridge.resource(bookId, path)
  } finally {
    release()
  }
}

export function resetImageLoaderForTests(): void {
  active = 0
  queue.length = 0
}
