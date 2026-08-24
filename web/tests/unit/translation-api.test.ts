import { afterEach, describe, expect, it, vi } from 'vitest'

import { translate } from '../../src/api/client'

describe('контекстный перевод', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('передаёт предложение как context, а переводит только выбранное слово', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as Record<string, string>
      expect(body).toEqual({
        text: 'book',
        context: 'I will book a room.',
        source: 'EN',
        target: 'RU',
      })
      return new Response(JSON.stringify({ text: 'забронировать' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      translate('book', undefined, { context: 'I will book a room.' }),
    ).resolves.toBe('забронировать')
    expect(fetchMock).toHaveBeenCalledOnce()
  })
})
