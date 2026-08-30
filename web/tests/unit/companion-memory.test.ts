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
      'book', 'Книга', 1, 'Где герой?', 4000, 'persona',
      { answer: 'Он уехал.', evidence: [], remaining: 7 },
    )

    const cached = useCompanionMemory.getState().findQuestion('book', 1, 'Где герой?', 4000, 'persona')
    expect(cached).toMatchObject({ answer: 'Он уехал.', cached: true, remaining: -1 })

    // Текст книги на диск не попадает ни при каких условиях: страница уходит
    // в отпечаток и больше никуда. Вопрос читателя, наоборот, сохраняется
    // намеренно — из него собирается память разговора.
    useCompanionMemory.getState().rememberOpinion(
      'book', 1, 'секретный текст страницы', 'persona',
      { title: 'Оговорка', opinion: 'Он лукавит.', remaining: 6 },
    )
    expect(records.get('wolfy.companion.memory.v1')).not.toContain('секретный текст страницы')
  })

  it('сдвиг границы между вопросом и личностью не выдаёт чужой ответ', async () => {
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().rememberQuestion(
      'book', 'Книга', 1, 'Кто он', 0, 'персона',
      { answer: 'Ответ про первое.', evidence: [], remaining: 5 },
    )

    const memory = useCompanionMemory.getState()
    expect(memory.findQuestion('book', 1, 'Кто', 0, 'онперсона')).toBeNull()
    expect(memory.findQuestion('book', 1, 'Кто он', 0, 'персона')).not.toBeNull()
  })

  it('прочитанная страница не отменяет прошлый ответ, а прочитанная глава отменяет', async () => {
    // В ключ уходило всё прочитанное, а оно прирастает каждой строкой: кэш
    // промахивался всегда, кроме «нажал дважды подряд, не двинувшись».
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().clear()
    useCompanionMemory.getState().rememberQuestion(
      'book', 'Книга', 3, 'Почему он молчит?', 4000, 'persona',
      { answer: 'Боится.', evidence: [], remaining: 5 },
    )

    const memory = useCompanionMemory.getState()
    expect(memory.findQuestion('book', 3, 'Почему он молчит?', 4200, 'persona')).not.toBeNull()
    expect(memory.findQuestion('book', 3, 'почему он молчит', 4000, 'persona')).not.toBeNull()
    expect(memory.findQuestion('book', 3, 'Почему он молчит?', 9000, 'persona')).toBeNull()
    expect(memory.findQuestion('book', 4, 'Почему он молчит?', 4000, 'persona')).toBeNull()
  })

  it('сохраняет краткий пересказ как контекст книги и очищается отдельно', async () => {
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().rememberRecap(
      'book', 'Книга', 3, 5000,
      { summary: 'Герой получил письмо.', events: [{ title: 'Письмо', text: 'Пришла новость.', kind: 'turn' }], remaining: 6 },
    )
    expect(useCompanionMemory.getState().findRecap('book', 3, 5100)).not.toBeNull()
    expect(useCompanionMemory.getState().contextFor('book')).toContain('Герой получил письмо')

    useCompanionMemory.getState().setSize('deep')
    useCompanionMemory.getState().clear()
    expect(useCompanionMemory.getState().settings.size).toBe('deep')
    expect(useCompanionMemory.getState().cache).toHaveLength(0)
  })

  it('нажатие кнопки не выдаётся модели за вопрос читателя', async () => {
    // Мнение о странице и «вспомнить сюжет» — действия, а не вопросы. Раньше
    // их подписи уезжали в промпт строкой «недавние запросы читателя» и
    // вытесняли оттуда настоящие вопросы, ради которых память и заведена.
    const { useCompanionMemory } = await import('../../src/companion/memory')
    useCompanionMemory.getState().clear()
    useCompanionMemory.getState().rememberOpinion(
      'книга', 4, 'текст страницы', 'persona',
      { title: 'Оговорка', opinion: 'Здесь он лукавит.', remaining: 9 },
    )
    useCompanionMemory.getState().rememberRecap(
      'книга', 'Книга', 4, 2000,
      { summary: 'Герой уехал.', events: [], remaining: 8 },
    )

    const context = useCompanionMemory.getState().contextFor('книга')
    expect(context).toContain('Герой уехал')
    expect(context).not.toContain('Мнение о странице')
    expect(context).not.toContain('Вспомнить сюжет')
    expect(useCompanionMemory.getState().questions).toHaveLength(0)

    useCompanionMemory.getState().rememberQuestion(
      'книга', 'Книга', 4, 'Почему он молчит?', 2000, 'persona',
      { answer: 'Боится.', evidence: [], remaining: 7 },
    )
    expect(useCompanionMemory.getState().contextFor('книга')).toContain('Почему он молчит?')
  })
})
