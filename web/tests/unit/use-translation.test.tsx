import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  translate: vi.fn(),
  cachedTranslation: vi.fn(async () => null as string | null),
  cacheTranslation: vi.fn(async () => undefined),
}))

vi.mock('../../src/api/client', () => ({
  translate: mocks.translate,
  OfflineError: class OfflineError extends Error {},
  ApiError: class ApiError extends Error {},
}))

vi.mock('../../src/storage/idb', () => ({
  cachedTranslation: mocks.cachedTranslation,
  cacheTranslation: mocks.cacheTranslation,
  translationKey: (sentence: string, word: string) => `${sentence}\u0000${word}`,
}))

import { useTranslation } from '../../src/card/useTranslation'

const mounted: Array<{ root: ReturnType<typeof createRoot>; node: HTMLDivElement }> = []
const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

afterEach(() => {
  for (const { root, node } of mounted.splice(0)) {
    act(() => root.unmount())
    node.remove()
  }
  mocks.translate.mockReset()
  mocks.cachedTranslation.mockClear()
  mocks.cacheTranslation.mockClear()
})

function Probe() {
  const translation = useTranslation('book', 'I will book a room.')
  return <output>{translation.state}</output>
}

function mountProbe() {
  const node = document.createElement('div')
  document.body.append(node)
  const root = createRoot(node)
  const entry = { root, node }
  mounted.push(entry)
  return entry
}

describe('useTranslation', () => {
  it('после закрытия сразу создаёт свежий запрос, а не ждёт отменённый', async () => {
    mocks.translate.mockImplementation((text: string, signal: AbortSignal) => {
      const call = mocks.translate.mock.calls.length
      if (call <= 2) {
        return new Promise<string>((_resolve, reject) => {
          signal.addEventListener(
            'abort',
            () => reject(new DOMException('aborted', 'AbortError')),
            { once: true },
          )
        })
      }
      return Promise.resolve(text === 'book' ? 'забронировать' : 'Я забронирую номер.')
    })

    const first = mountProbe()
    await act(async () => first.root.render(<Probe />))
    expect(mocks.translate).toHaveBeenCalledTimes(2)

    await act(async () => first.root.unmount())
    first.node.remove()
    mounted.splice(mounted.indexOf(first), 1)

    const second = mountProbe()
    await act(async () => second.root.render(<Probe />))

    expect(mocks.translate).toHaveBeenCalledTimes(4)
    expect(second.node.textContent).toBe('ready')
  })
})
