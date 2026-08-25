/**
 * Прилипание выделения фразы к границам слов.
 *
 * Регрессия: край выделения, попавший на пробел между словами, отдавался
 * родительскому элементу — то есть всему абзацу, — и «ближайшим» словом
 * оказывалось первое или последнее слово главы. Выделение двух слов красило
 * текст от начала абзаца до его конца.
 */

import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'

import { ChapterView } from '../../src/reader/ChapterView'
import type { Token } from '../../src/core/types'
import type { TokenizedBlock } from '../../src/reader/useChapter'

/** «The quick brown fox jumps» — слова на чётных местах, пробелы на нечётных. */
function tokenize(text: string): Token[] {
  const tokens: Token[] = []
  let at = 0
  text.split(' ').forEach((word, index) => {
    if (index > 0) {
      tokens.push({ kind: 'space', start: at, end: at + 1, text: ' ' })
      at += 1
    }
    tokens.push({ kind: 'word', start: at, end: at + word.length, text: word })
    at += word.length
  })
  return tokens
}

const SENTENCE = 'The quick brown fox jumps over the lazy dog'

function blocks(): TokenizedBlock[] {
  const tokens = tokenize(SENTENCE)
  return [{ block: { kind: 'paragraph', text: SENTENCE }, index: 0, tokens, offset: 0 }]
}

const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

let host: HTMLDivElement
let root: ReturnType<typeof createRoot>

beforeEach(() => {
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => root.unmount())
  host.remove()
  window.getSelection()?.removeAllRanges()
})

function render(onPhrase: (start: number, end: number) => void) {
  act(() => {
    root.render(
      <ChapterView
        blocks={blocks()}
        marks={[]}
        onWord={vi.fn()}
        onPhrase={onPhrase}
        dropCap={false}
        images={new Map()}
      />,
    )
  })
}

/** Пробел между словами: отдельный текстовый узел прямо в абзаце. */
function spaceAfter(token: number): Text {
  const word = document.querySelector(`[data-t="${token}"]`)!
  const next = word.nextSibling
  if (!next || next.nodeType !== Node.TEXT_NODE) {
    throw new Error(`после токена ${token} нет текстового узла с пробелом`)
  }
  return next as Text
}

function selectAndRelease(range: Range) {
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  act(() => {
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
  })
}

describe('выделение фразы прилипает к словам', () => {
  it('не растягивает выделение до конца главы, когда край попал на пробел', () => {
    const onPhrase = vi.fn()
    render(onPhrase)

    // «quick brown» плюс пробел после последнего слова.
    const range = document.createRange()
    range.setStart(document.querySelector('[data-t="2"]')!.firstChild!, 0)
    range.setEnd(spaceAfter(4), 1)
    selectAndRelease(range)

    expect(onPhrase).toHaveBeenCalledTimes(1)
    expect(onPhrase).toHaveBeenCalledWith(2, 5)
  })

  it('не растягивает выделение до начала главы, когда на пробел попало начало', () => {
    const onPhrase = vi.fn()
    render(onPhrase)

    // Начало — на пробеле перед «brown», конец — внутри «fox».
    const range = document.createRange()
    range.setStart(spaceAfter(2), 0)
    range.setEnd(document.querySelector('[data-t="6"]')!.firstChild!, 3)
    selectAndRelease(range)

    expect(onPhrase).toHaveBeenCalledWith(4, 7)
  })

  it('оставляет выделение внутри слов как есть', () => {
    const onPhrase = vi.fn()
    render(onPhrase)

    const range = document.createRange()
    range.setStart(document.querySelector('[data-t="8"]')!.firstChild!, 1)
    range.setEnd(document.querySelector('[data-t="12"]')!.firstChild!, 2)
    selectAndRelease(range)

    expect(onPhrase).toHaveBeenCalledWith(8, 13)
  })

  it('молчит на одном слове: у слова своя карточка, а не разбор фразы', () => {
    const onPhrase = vi.fn()
    render(onPhrase)

    const range = document.createRange()
    range.selectNodeContents(document.querySelector('[data-t="2"]')!)
    selectAndRelease(range)

    expect(onPhrase).not.toHaveBeenCalled()
  })
})
