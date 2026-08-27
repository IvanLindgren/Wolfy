import { afterEach, describe, expect, it, vi } from 'vitest'

import { downloadBookChunk, uploadBookChunk } from '../../src/api/client'

describe('синхронизация файлов книг', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('отправляет бинарный чанк с точным смещением и контрольной суммой', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.method).toBe('PUT')
      expect(init?.headers).toMatchObject({
        'X-Wolfy-File-Name': 'book-id.epub',
        'X-Wolfy-SHA256': 'abc123',
        'X-Wolfy-Offset': '4',
        'X-Wolfy-Total': '7',
      })
      expect(Array.from(new Uint8Array(init?.body as ArrayBuffer))).toEqual([5, 6, 7])
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await uploadBookChunk('book-id', 'book-id.epub', 'abc123', 4, 7, new Uint8Array([5, 6, 7]))
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('запрашивает только нужный диапазон файла', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.headers).toEqual({ Range: 'bytes=1024-1535' })
      return new Response(new Uint8Array([8, 9]), { status: 206 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(downloadBookChunk('book-id', 1024, 512)).resolves.toEqual(new Uint8Array([8, 9]))
  })
})
