import { describe, expect, it } from 'vitest'

import { plural, readFraction, readingFacts } from '../../src/settings/facts'
import type { AppSettings, Card, DeckStatus, LibraryBook } from '../../src/core/types'

function книга(over: Partial<LibraryBook>): LibraryBook {
  return {
    id: 'b', path: 'books/b.epub', title: 'Книга', author: null, format: 'epub',
    sourceKey: '', addedAt: 0, chapters: 10,
    progress: { chapter: 0, withinChapter: 0, openedAt: 0 },
    shelf: null, rev: 0, dirty: false, deleted: false,
    ...over,
  }
}

function карточка(over: Partial<Card>): Card {
  return {
    id: 'c', bookId: 'b', kind: 'word', surface: 'word', lemma: 'word', translation: 'слово',
    context: '', pos: '', cefr: '', hp: 4, streak: 0, intervalDays: 1,
    dueAt: 0, reviewedAt: 0, addedAt: 0, rev: 0, dirty: false, deleted: false,
    ...over,
  }
}

const НАСТРОЙКИ = { streakDays: 3, bestStreak: 9, answers: 40, right: 30 } as AppSettings
const КОЛОДЫ: DeckStatus[] = [
  { kind: 'word', due: 4, total: 30, learned: 11 },
  { kind: 'phrase', due: 0, total: 5, learned: 1 },
  { kind: 'rule', due: 1, total: 9, learned: 0 },
]

describe('сводка чисел в настройках', () => {
  it('дочитанной считается книга по доле, а не по последней главе', () => {
    // Порог 0.999, а не 1.0: последняя строка последней главы редко попадает
    // в единицу ровно, и книга, дочитанная до точки, не должна висеть
    // непрочитанной из-за ошибки округления.
    const почти = книга({ chapters: 10, progress: { chapter: 9, withinChapter: 0.9995, openedAt: 5 } })
    const середина = книга({ id: 'm', chapters: 10, progress: { chapter: 5, withinChapter: 0, openedAt: 5 } })
    const нетронутая = книга({ id: 'n' })

    const facts = readingFacts([почти, середина, нетронутая], [], [], НАСТРОЙКИ, 1000)
    expect(facts.books).toBe(3)
    expect(facts.started).toBe(2)
    expect(facts.finished).toBe(1)
    expect(readFraction(книга({ chapters: 0 }))).toBe(0)
  })

  it('удалённые книги в числа не входят', () => {
    // Удалённое остаётся в состоянии ради синхронизации, и не вычесть его
    // здесь значило бы показать читателю книги, которых он у себя не видит.
    const facts = readingFacts(
      [книга({}), книга({ id: 'x', deleted: true })],
      [],
      [],
      НАСТРОЙКИ,
      1000,
    )
    expect(facts.books).toBe(1)
  })

  it('колоды берутся у ядра, а не пересчитываются по карточкам', () => {
    // Колода грамматики наполняется сама, и до первого ответа её карточек в
    // library.cards нет. Пересчёт дал бы «0 к повторению» рядом со значком
    // раздела, где горит пять.
    const facts = readingFacts([], [], КОЛОДЫ, НАСТРОЙКИ, 1000)
    expect(facts.cards).toBe(44)
    expect(facts.learned).toBe(12)
    expect(facts.due).toBe(5)
  })

  it('новыми за неделю считаются слова читателя, а не подмешанные правила', () => {
    const now = 10 * 24 * 60 * 60 * 1000
    const week = 7 * 24 * 60 * 60 * 1000
    const facts = readingFacts([], [
      карточка({ id: '1', addedAt: now - 1000 }),
      карточка({ id: '2', addedAt: now - week - 1000 }),
      // Правило добавляет приложение: в «новых словах за неделю» ему не место.
      карточка({ id: '3', kind: 'rule', addedAt: now - 1000 }),
      карточка({ id: '4', addedAt: now - 1000, deleted: true }),
    ], КОЛОДЫ, НАСТРОЙКИ, now)
    expect(facts.addedThisWeek).toBe(1)
  })

  it('доля верных не выдумывается, пока не было ответов', () => {
    expect(readingFacts([], [], [], НАСТРОЙКИ, 0).accuracy).toBe(75)
    const чистый = { ...НАСТРОЙКИ, answers: 0, right: 0 } as AppSettings
    expect(readingFacts([], [], [], чистый, 0).accuracy).toBeNull()
  })

  it('число согласовано со словом', () => {
    expect(plural(1, 'книга', 'книги', 'книг')).toBe('1 книга')
    expect(plural(3, 'книга', 'книги', 'книг')).toBe('3 книги')
    expect(plural(5, 'книга', 'книги', 'книг')).toBe('5 книг')
    // Одиннадцать — не «одна»: десятки от 11 до 19 идут особняком.
    expect(plural(11, 'книга', 'книги', 'книг')).toBe('11 книг')
    expect(plural(21, 'книга', 'книги', 'книг')).toBe('21 книга')
  })
})
