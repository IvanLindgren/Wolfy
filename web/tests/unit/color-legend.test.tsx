import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { ColorLegend } from '../../src/card/ColorLegend'
import type { ContextPart, Marker } from '../../src/core/types'

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

function render(parts: ContextPart[], markers: Marker[]) {
  const node = document.createElement('div')
  document.body.append(node)
  const root = createRoot(node)
  act(() => {
    root.render(<ColorLegend parts={parts} markers={markers} />)
  })
  return {
    text: node.textContent ?? '',
    swatches: node.querySelectorAll('[data-shape]').length,
    empty: node.childElementCount === 0,
    close: () => {
      act(() => root.unmount())
      node.remove()
    },
  }
}

const ending: Marker = {
  token: 1,
  from: 2,
  to: 4,
  kind: 'ending',
  rule: 'past-simple',
  note: 'прошедшее время',
}

describe('ColorLegend', () => {
  it('называет только те краски, что встретились во фразе', () => {
    const parts: ContextPart[] = [
      { token: 0, pos: 'NOUN' },
      { token: 1, pos: 'VERB' },
    ]

    const { text, swatches, close } = render(parts, [])
    expect(text).toContain('существительное')
    expect(text).toContain('глагол')
    // Прилагательного во фразе нет — значит, и в легенде ему не место.
    expect(text).not.toContain('прилагательное')
    expect(swatches).toBe(2)
    close()
  })

  it('объясняет маркер грамматики, а не только части речи', () => {
    const { text, close } = render([{ token: 0, pos: 'VERB' }], [ending])
    expect(text).toContain('окончание')
    close()
  })

  it('не показывает один и тот же вид маркера дважды', () => {
    const { swatches, close } = render([], [ending, { ...ending, token: 3 }])
    expect(swatches).toBe(1)
    close()
  })

  it('молчит, когда объяснять нечего', () => {
    const { empty, close } = render([], [])
    expect(empty).toBe(true)
    close()
  })
})
