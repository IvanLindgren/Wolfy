import { act, useMemo } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

import { useShortcuts } from '../../src/app/shortcuts'

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

function Probe({ save }: { save: () => void }) {
  useShortcuts(useMemo(() => [{ key: 'Enter', run: save }], [save]))
  return (
    <div>
      <button type="button"><span>Подробнее</span></button>
      <div contentEditable suppressContentEditableWarning><span>Редактор</span></div>
      <span>Фон</span>
    </div>
  )
}

describe('глобальные клавиши', () => {
  it('оставляет Enter кнопке и contentEditable, но ловит его вне контролов', () => {
    const save = vi.fn()
    const node = document.createElement('div')
    document.body.append(node)
    const root = createRoot(node)
    act(() => root.render(<Probe save={save} />))

    const press = (target: Element) => {
      const event = new KeyboardEvent('keydown', {
        key: 'Enter',
        bubbles: true,
        cancelable: true,
      })
      target.dispatchEvent(event)
      return event
    }

    const buttonText = node.querySelector('button span')!
    const editorText = node.querySelector('[contenteditable] span')!
    const background = [...node.querySelectorAll('span')]
      .find((element) => element.textContent === 'Фон')!

    expect(press(buttonText).defaultPrevented).toBe(false)
    expect(press(editorText).defaultPrevented).toBe(false)
    expect(save).not.toHaveBeenCalled()

    expect(press(background).defaultPrevented).toBe(true)
    expect(save).toHaveBeenCalledOnce()

    act(() => root.unmount())
    node.remove()
  })
})
