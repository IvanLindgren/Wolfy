import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'

import type { Token } from '../../src/core/types'

const bridge = vi.hoisted(() => ({
  gloss: vi.fn(),
}))

vi.mock('../../src/core/bridge', () => bridge)

import { PhraseText } from '../../src/card/PhraseText'

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
  bridge.gloss.mockReset()
  for (const { root, node } of mounted.splice(0)) {
    act(() => root.unmount())
    node.remove()
  }
})

const tokens: Token[] = [
  { kind: 'word', text: 'Wolves', start: 400, end: 406 },
  { kind: 'space', text: ' ', start: 406, end: 407 },
  { kind: 'word', text: 'read', start: 407, end: 411 },
]

describe('PhraseText', () => {
  it('показывает подписи частей речи даже без подстрочного перевода', async () => {
    bridge.gloss.mockResolvedValue([
      { lemma: 'wolf', translation: 'волки', pos: 'NOUN' },
      // Вне предложения словарь считает `read` существительным; результат
      // контекстного теггера обязан иметь приоритет.
      { lemma: 'read', translation: 'читают', pos: 'NOUN' },
    ])

    const node = document.createElement('div')
    document.body.append(node)
    const root = createRoot(node)
    mounted.push({ root, node })

    await act(async () => {
      root.render(
        <PhraseText
          tokens={tokens}
          markers={[
            {
              token: 2,
              from: 0,
              to: 4,
              kind: 'auxiliary',
              rule: 'test',
              note: 'локальный индекс',
            },
          ]}
          parts={[
            { token: 0, pos: 'NOUN' },
            { token: 2, pos: 'VERB' },
          ]}
          interlinear={false}
          showParts
        />,
      )
    })

    expect(bridge.gloss).toHaveBeenCalledWith(['Wolves', 'read'])
    expect(node.querySelector('[title="существительное"][lang="ru"]')?.textContent).toBe('сущ.')
    expect(node.querySelector('[title="глагол"][lang="ru"]')?.textContent).toBe('гл.')
    expect(node.querySelector('mark')?.textContent).toBe('read')
    expect(node.textContent).not.toContain('волки')
    expect(node.textContent).not.toContain('читают')
  })
})
