import { act, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { Paginator, type PagerHandle } from '../../src/reader/Paginator'

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
})

describe('Paginator', () => {
  it('не зацикливает родителя с новым onPage на каждой перерисовке', async () => {
    let reports = 0

    function Host() {
      const [, renderAgain] = useState(0)
      const handle = useRef<PagerHandle | null>(null)

      return (
        <Paginator
          handle={handle}
          resetKey="book:chapter"
          duration={0}
          onPage={() => {
            reports += 1
            renderAgain((current) => current + 1)
          }}
        >
          <p>Chapter text</p>
        </Paginator>
      )
    }

    const node = document.createElement('div')
    document.body.append(node)
    const root = createRoot(node)
    mounted.push({ root, node })

    await act(async () => {
      root.render(<Host />)
    })

    expect(reports).toBe(1)
  })
})
