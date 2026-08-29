import { beforeAll, describe, expect, it, vi } from 'vitest'

const records = new Map<string, string>()

beforeAll(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => records.get(key) ?? null,
      setItem: (key: string, value: string) => records.set(key, value),
      removeItem: (key: string) => records.delete(key),
    },
  })
  vi.resetModules()
})

describe('локальная память компаньона', () => {
  it('кэширует ответ, но не сохраняет исходный текст книги', async () => {
    const { useCompanionMemory } = await import('../../src/companion/memory')
    const memory = useCompanionMemory.getState()
    memory.rememberQuestion(
      'book', 'Книга', 1, 'Где герой?', 'секретный текст страницы', 'persona',
      { answer: 'Он уехал.', evidence: [], remaining: 7 },
    )

    const cached = useCompanionMemory.getState().findQuestion(
      'book', 1, 'Где герой?', 'секретный текст страницы', 'persona',
    )
    expect(cached).toMatchObject({ answer: 'Он уехал.', cached: true, remaining: -1 })
    expect(records.get('wolfy.companion.memory.v1')).not.toContain('секретный текст страницы')
  })

  it('сдвиг границы между вопросом и прочитанным не выдаёт чужой ответ', async () => {
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().rememberQuestion(
      'book', 'Книга', 1, 'Кто он', 'стал королём', 'persona',
      { answer: 'Ответ про первое.', evidence: [], remaining: 5 },
    )

    const memory = useCompanionMemory.getState()
    expect(memory.findQuestion('book', 1, 'Кто', 'он стал королём', 'persona')).toBeNull()
    expect(memory.findQuestion('book', 1, 'Кто он', 'стал королём', 'persona')).not.toBeNull()
  })

  it('сохраняет краткий пересказ как контекст книги и очищается отдельно', async () => {
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().rememberRecap(
      'book', 'Книга', 3, 'another excerpt',
      { summary: 'Герой получил письмо.', events: [{ title: 'Письмо', text: 'Пришла новость.', kind: 'turn' }], remaining: 6 },
    )
    expect(useCompanionMemory.getState().contextFor('book')).toContain('Герой получил письмо')

    useCompanionMemory.getState().setSize('deep')
    useCompanionMemory.getState().clear()
    expect(useCompanionMemory.getState().settings.size).toBe('deep')
    expect(useCompanionMemory.getState().cache).toHaveLength(0)
  })
})
