import { describe, expect, it } from 'vitest'

import {
  contextualPos,
  otherSenses,
  primarySense,
} from '../../src/card/cardEssentials'
import type { Grammar, Sense } from '../../src/core/types'

describe('главное толкование карточки', () => {
  const senses: Sense[] = [
    { pos: 'NOUN', definition: 'a written work' },
    { pos: 'VERB', definition: 'to arrange or reserve' },
  ]

  it('совпадает с частью речи формы в текущем контексте', () => {
    expect(primarySense(senses, 'VERB')?.definition).toBe('to arrange or reserve')
  })

  it('сохраняет словарный fallback, если часть речи неизвестна', () => {
    expect(primarySense(senses, 'ADJ')?.definition).toBe('a written work')
  })

  it('не повторяет выбранное главное значение среди остальных', () => {
    const main = primarySense(senses, 'VERB')
    expect(otherSenses(senses, main)).toEqual([senses[0]])
  })
})

describe('часть речи выбранного слова в предложении', () => {
  const grammar: Grammar = {
    findings: [],
    markers: [],
    chunks: [
      {
        role: 'predicate',
        title: 'сказуемое',
        tint: 'VERB',
        start: 2,
        end: 5,
        head: 4,
      },
      {
        role: 'object',
        title: 'дополнение',
        tint: 'NOUN',
        start: 6,
        end: 9,
        head: 8,
      },
    ],
  }

  it('берёт VERB для выбранного book в «I will book a room»', () => {
    expect(contextualPos(grammar, 4)).toBe('VERB')
  })

  it('предпочитает точный результат теггера цвету всей группы', () => {
    expect(contextualPos({ ...grammar, parts: [{ token: 6, pos: 'DET' }] }, 6)).toBe('DET')
  })
})
