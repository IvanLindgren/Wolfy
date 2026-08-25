import { describe, expect, it } from 'vitest'

import { parseFormula, symbolsOf } from '../../src/grammar/Formula'

describe('разбор формулы правила', () => {
  it('делит формулу на кирпичики по плюсу', () => {
    expect(parseFormula('have/has + V3')).toEqual([
      [
        { text: 'have/has', kind: 'word' },
        { text: 'V3', kind: 'symbol', title: expect.stringContaining('третья форма') },
      ],
    ])
  })

  it('разводит части сложного предложения по запятой', () => {
    const clauses = parseFormula('if + Present, will + V')
    expect(clauses).toHaveLength(2)
    expect(clauses[0]!.map((piece) => piece.text)).toEqual(['if', 'Present'])
    expect(clauses[1]!.map((piece) => piece.text)).toEqual(['will', 'V'])
  })

  it('узнаёт `V-ing` целиком, а не как `V` с хвостом', () => {
    const [clause] = parseFormula('am/is/are + V-ing')
    expect(clause!.at(-1)).toMatchObject({ text: 'V-ing', kind: 'symbol' })
  })

  it('считает русское слово пропуском, который читатель заполняет сам', () => {
    const [clause] = parseFormula('make / let / see / hear + объект + V')
    expect(clause!.map((piece) => piece.kind)).toEqual(['word', 'slot', 'symbol'])
  })

  it('не оставляет пустых кирпичиков от лишних пробелов', () => {
    expect(parseFormula('  will  +  be  +  V-ing ')[0]!.map((piece) => piece.text)).toEqual([
      'will',
      'be',
      'V-ing',
    ])
  })
})

describe('расшифровка знаков формулы', () => {
  it('называет только те знаки, что встретились', () => {
    expect(symbolsOf('will have + V3').map((symbol) => symbol.code)).toEqual(['V3'])
  })

  it('не выдумывает знаков там, где их нет', () => {
    expect(symbolsOf('used to + V').map((symbol) => symbol.code)).toEqual(['V'])
    expect(symbolsOf('more / most + прилагательное')).toEqual([])
  })

  it('перечисляет знаки в порядке разбора, а не в порядке формулы', () => {
    expect(symbolsOf('have/has + been + V-ing').map((symbol) => symbol.code)).toEqual(['V-ing'])
  })
})
