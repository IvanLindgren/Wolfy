import { describe, expect, it } from 'vitest'

import { dueDay, localDay, newId, plural } from '../../src/core/clock'
import { translationKey } from '../../src/storage/idb'

describe('время и локальные идентификаторы', () => {
  it('учитывает часовые пояса с долей часа', () => {
    const beforeMidnightUtc = Date.UTC(2026, 0, 1, 18, 45)
    const afterMidnightInNepal = Date.UTC(2026, 0, 1, 18, 15)

    expect(localDay(beforeMidnightUtc, 0)).not.toBe(localDay(beforeMidnightUtc, 345))
    expect(localDay(afterMidnightInNepal, 345)).toBe(localDay(beforeMidnightUtc, 345))
  })

  it('склоняет русские счётчики', () => {
    expect(plural(1, 'день', 'дня', 'дней')).toBe('день')
    expect(plural(3, 'день', 'дня', 'дней')).toBe('дня')
    expect(plural(12, 'день', 'дня', 'дней')).toBe('дней')
    expect(plural(21, 'день', 'дня', 'дней')).toBe('день')
  })

  it('создаёт UUID для офлайн-записей', () => {
    expect(newId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('нормализует контекстный ключ перевода', () => {
    expect(translationKey('  The  bank\nclosed. ', 'BANK')).toBe('The bank closed.\u0000bank')
  })

  it('различает новую, сегодняшнюю и будущую карточку', () => {
    const day = 86_400_000
    const reference = Date.UTC(2026, 7, 24, 12)
    expect(dueDay(0, reference, 0)).toBe('новая')
    expect(dueDay(reference, reference, 0)).toBe('сегодня')
    expect(dueDay(reference + day, reference, 0)).toBe('завтра')
    expect(dueDay(reference + 3 * day, reference, 0)).toBe('через 3 дня')
    expect(dueDay(reference - 2 * day, reference, 0)).toBe('2 дня назад')
  })
})
