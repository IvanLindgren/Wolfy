import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { SentenceGraph } from '../../src/card/SentenceGraph'
import type { Chunk, Token } from '../../src/core/types'

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

describe('SentenceGraph', () => {
  it('читает индексы chunk локально, даже если фраза находится в середине главы', () => {
    const tokens: Token[] = [
      { kind: 'word', text: 'Wolves', start: 900, end: 906 },
      { kind: 'space', text: ' ', start: 906, end: 907 },
      { kind: 'word', text: 'read', start: 907, end: 911 },
    ]
    const chunks: Chunk[] = [
      {
        role: 'subject',
        title: 'подлежащее',
        tint: 'NOUN',
        start: 0,
        end: 1,
        head: 0,
      },
      {
        role: 'predicate',
        title: 'сказуемое',
        tint: 'VERB',
        start: 2,
        end: 3,
        head: 2,
      },
    ]
    const node = document.createElement('div')
    document.body.append(node)
    const root = createRoot(node)

    act(() => {
      root.render(
        <SentenceGraph
          tokens={tokens}
          chunks={chunks}
          mode="graph"
          stagger={0}
          duration={0}
        />,
      )
    })

    const labels = [...node.querySelectorAll('text')].map((label) => label.textContent)
    expect(labels).toContain('Wolves')
    expect(labels).toContain('read')

    act(() => root.unmount())
    node.remove()
  })
})
