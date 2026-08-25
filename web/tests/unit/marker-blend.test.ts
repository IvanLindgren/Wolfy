import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * Маркер обязан смешиваться с текстом, а не закрывать его.
 *
 * Краски маркера непрозрачные (`--hl-1: #ffe9a3`), а слой подсветки рисуется
 * поверх строки. Стоит убрать наложение — и выделенные слова исчезают под
 * заливкой: маркер прячет ровно то, что им отметили. Проверить это в jsdom
 * нельзя, там нет ни раскладки, ни смешивания, поэтому проверяем сам договор
 * в стилях — он и есть то, что сломалось.
 */
const reader = readFileSync(
  resolve(__dirname, '../../src/reader/reader.module.css'),
  'utf8',
)
const theme = readFileSync(resolve(__dirname, '../../src/theme/theme.css'), 'utf8')

/** Тело правила по его селектору. */
function rule(css: string, selector: string): string {
  const at = css.indexOf(`${selector} {`)
  expect(at, `правило ${selector} пропало`).toBeGreaterThan(-1)
  return css.slice(at, css.indexOf('}', at))
}

describe('выделение маркером', () => {
  it('краска смешивается с буквами', () => {
    expect(rule(reader, '.mark--mark')).toContain('mix-blend-mode')
  })

  it('слой подсветки не заводит своего контекста наложения', () => {
    // z-index с числом изолирует слой, и смешивать краске становится не с чем:
    // текст остаётся снаружи контекста, а внутри пусто.
    const marks = rule(reader, '.marks')
    expect(marks).toMatch(/z-index:\s*auto/)
    expect(marks).not.toMatch(/z-index:\s*-?\d/)
  })

  it('режим наложения задан в каждой теме', () => {
    // Тёмным темам нужно осветление, а не умножение: краски у них тёмные, и
    // умножение затемнило бы и буквы. Новая тема без своего режима молча
    // получила бы запасное умножение — и сломалась бы ровно так.
    const тем = theme.match(/--hl-1:/g)?.length ?? 0
    const режимов = theme.match(/--mark-blend:/g)?.length ?? 0
    expect(тем).toBeGreaterThan(0)
    expect(режимов).toBe(тем)
  })

  it('тёмные темы осветляют, светлые умножают', () => {
    // Порядок в файле: светлая, Sepia, Dark, Oled, системная тёмная.
    const режимы = [...theme.matchAll(/--mark-blend:\s*(\w+)/g)].map((m) => m[1])
    expect(режимы).toEqual(['multiply', 'multiply', 'screen', 'screen', 'screen'])
  })
})
