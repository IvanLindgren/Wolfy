import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadBookURL, OfflineError } from '../../src/api/client'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('downloadBookURL', () => {
  it('получает бинарную книгу и имя из Content-Disposition', async () => {
    const fetch = vi.fn(async () => new Response('%PDF-1.7', {
      status: 200,
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': "attachment; filename*=UTF-8''Alice%20in%20Wonderland.pdf",
      },
    }))
    vi.stubGlobal('fetch', fetch)

    const result = await downloadBookURL(' https://books.example/alice ')

    expect(result.fileName).toBe('Alice in Wonderland.pdf')
    expect(result.contentType).toBe('application/pdf')
    expect(new TextDecoder().decode(result.bytes)).toBe('%PDF-1.7')
    expect(fetch).toHaveBeenCalledWith('/v1/library/fetch', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ url: 'https://books.example/alice' }),
    }))
  })

  it('показывает понятную ошибку защищённого загрузчика', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ error: 'разрешены только публичные HTTPS-адреса' }),
      { status: 400, headers: { 'Content-Type': 'application/json' } },
    )))

    await expect(downloadBookURL('https://127.0.0.1/book.pdf')).rejects.toMatchObject({
      status: 400,
      message: 'разрешены только публичные HTTPS-адреса',
    })
  })

  it('отличает отсутствие сети от ответа сервера', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline') }))
    await expect(downloadBookURL('https://books.example/book.epub')).rejects.toBeInstanceOf(
      OfflineError,
    )
  })
})
