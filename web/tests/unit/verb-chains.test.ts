import { describe, expect, it } from 'vitest'

import { chainsOf } from '../../src/reader/useChapter'
import type { Token } from '../../src/core/types'

/**
 * Цепочки сказуемого: символы ядра против номеров токенов читалки.
 *
 * Ядро отдаёт цепочку в смещениях UTF-16, а вся читалка живёт в номерах
 * токенов. Ошибка на единицу здесь означает, что тап по «is» возьмёт «is
 * walking home» вместе с лишним словом или наоборот потеряет глагол, - и
 * увидеть это можно только на живой книге.
 */
describe('цепочки сказуемого', () => {
  // "She is walking." — токены с их смещениями в тексте главы.
  const tokens: Token[] = [
    { kind: 'word', start: 0, end: 3, text: 'She' },
    { kind: 'space', start: 3, end: 4, text: ' ' },
    { kind: 'word', start: 4, end: 6, text: 'is' },
    { kind: 'space', start: 6, end: 7, text: ' ' },
    { kind: 'word', start: 7, end: 14, text: 'walking' },
    { kind: 'punctuation', start: 14, end: 15, text: '.' },
  ]

  it('переводит символы в номера токенов', () => {
    const chains = chainsOf(tokens, [{ start: 4, end: 14, mainStart: 7 }])
    expect(chains).toHaveLength(1)
    // Токены 2..4: «is», пробел и «walking».
    expect(chains[0]).toMatchObject({ start: 2, end: 5, main: 4 })
  })

  it('не захватывает слова за границей цепочки', () => {
    const [chain] = chainsOf(tokens, [{ start: 4, end: 14, mainStart: 7 }])
    expect(chain).toBeDefined()
    // «She» слева и точка справа в цепочку не входят.
    expect(chain!.start).toBeGreaterThan(0)
    expect(chain!.end).toBeLessThan(tokens.length)
  })

  it('пустой список ядра не даёт цепочек', () => {
    expect(chainsOf(tokens, undefined)).toEqual([])
    expect(chainsOf(tokens, [])).toEqual([])
  })

  it('цепочка, не совпавшая ни с одним токеном, отбрасывается', () => {
    // Рассинхрон разметки не должен превращаться в отметку на пустоте.
    expect(chainsOf(tokens, [{ start: 200, end: 210, mainStart: 205 }])).toEqual([])
  })
})
