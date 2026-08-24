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

function render(tokens: Token[], chunks: Chunk[]) {
  const node = document.createElement('div')
  document.body.append(node)
  const root = createRoot(node)
  act(() => {
    root.render(
      <SentenceGraph tokens={tokens} chunks={chunks} stagger={0} duration={0} />,
    )
  })
  return {
    node,
    close: () => {
      act(() => root.unmount())
      node.remove()
    },
  }
}

describe('SentenceGraph', () => {
  it('читает индексы chunk локально, даже если фраза находится в середине главы', () => {
    const tokens: Token[] = [
      { kind: 'word', text: 'Wolves', start: 900, end: 906 },
      { kind: 'space', text: ' ', start: 906, end: 907 },
      { kind: 'word', text: 'read', start: 907, end: 911 },
    ]
    const chunks: Chunk[] = [
      { role: 'subject', title: 'подлежащее', tint: 'NOUN', start: 0, end: 1, head: 0 },
      { role: 'predicate', title: 'сказуемое', tint: 'VERB', start: 2, end: 3, head: 2 },
    ]

    const { node, close } = render(tokens, chunks)
    const words = [...node.querySelectorAll('[data-head]')].map((word) => word.textContent)
    expect(words).toEqual(['Wolves', 'read'])
    close()
  })

  it('показывает группу целиком, а не одно главное слово', () => {
    const words = ['The', 'quick', 'brown', 'fox', 'jumps']
    const tokens: Token[] = words.map((text, index) => ({
      kind: 'word',
      text,
      start: index * 6,
      end: index * 6 + text.length,
    }))
    const chunks: Chunk[] = [
      { role: 'subject', title: 'подлежащее', tint: 'NOUN', start: 0, end: 4, head: 3 },
      { role: 'predicate', title: 'сказуемое', tint: 'VERB', start: 4, end: 5, head: 4 },
    ]

    const { node, close } = render(tokens, chunks)
    const rows = [...node.querySelectorAll('li')]
    expect(rows).toHaveLength(2)
    expect(rows[0]?.textContent).toContain('The')
    expect(rows[0]?.textContent).toContain('fox')

    // Сказуемое — корень разбора: на нём и держится всё остальное.
    expect(rows[1]?.getAttribute('data-root')).toBe('true')
    expect(rows[0]?.getAttribute('data-root')).toBe('false')
    close()
  })

  it('строки идут в порядке фразы, а не в порядке, в котором ядро нашло группы', () => {
    const words = ['Dogs', 'chase', 'cats']
    const tokens: Token[] = words.map((text, index) => ({
      kind: 'word',
      text,
      start: index * 6,
      end: index * 6 + text.length,
    }))
    const chunks: Chunk[] = [
      { role: 'predicate', title: 'сказуемое', tint: 'VERB', start: 1, end: 2, head: 1 },
      { role: 'object', title: 'дополнение', tint: 'NOUN', start: 2, end: 3, head: 2 },
      { role: 'subject', title: 'подлежащее', tint: 'NOUN', start: 0, end: 1, head: 0 },
    ]

    const { node, close } = render(tokens, chunks)
    const order = [...node.querySelectorAll('li')].map(
      (row) => row.querySelector('[data-head]')?.textContent,
    )
    expect(order).toEqual(['Dogs', 'chase', 'cats'])
    close()
  })
})
