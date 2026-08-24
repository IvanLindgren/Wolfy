import { act, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { SearchField } from '../../src/widgets/SearchField'

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

function Harness({ initial }: { initial: string }) {
  const [query, setQuery] = useState(initial)
  return <SearchField value={query} onChange={setQuery} label="Поиск книги" />
}

function mount(initial: string) {
  const node = document.createElement('div')
  document.body.append(node)
  const root = createRoot(node)
  act(() => root.render(<Harness initial={initial} />))
  return {
    input: () => node.querySelector('input') as HTMLInputElement,
    clear: () => node.querySelector('button'),
    close: () => {
      act(() => root.unmount())
      node.remove()
    },
  }
}

describe('SearchField', () => {
  it('не показывает крестик, пока очищать нечего', () => {
    const { clear, close } = mount('')
    expect(clear()).toBeNull()
    close()
  })

  it('очищает поле своим крестиком, а не браузерным', () => {
    const { input, clear, close } = mount('fox')
    const button = clear()
    expect(button).not.toBeNull()
    // Нативный крестик `type="search"` спрятан в стилях; этот — настоящая
    // кнопка, и добраться до неё можно с клавиатуры.
    expect(button?.getAttribute('aria-label')).toBe('Очистить поиск')

    act(() => button?.click())
    expect(input().value).toBe('')
    expect(clear()).toBeNull()
    close()
  })

  it('подписан для программ чтения с экрана: видимого заголовка у поля нет', () => {
    const { input, close } = mount('')
    expect(input().getAttribute('aria-label')).toBe('Поиск книги')
    expect(input().type).toBe('search')
    close()
  })
})
