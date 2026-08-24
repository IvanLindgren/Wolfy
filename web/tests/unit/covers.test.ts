import { describe, expect, it, vi } from 'vitest'

import { COVER_TYPES, saveCover } from '../../src/library/covers'

describe('обложка книги', () => {
  it('принимает ровно те форматы, что обещает выбор файла', () => {
    expect([...COVER_TYPES]).toEqual(['image/png', 'image/jpeg', 'image/webp'])
  })

  it('отказывает по типу файла до того, как трогает картинку', async () => {
    // Разбор картинки в этой среде вообще недоступен: если проверка типа его
    // не опередит, отказ придёт исключением, а не понятным текстом.
    const bitmap = vi.fn()
    vi.stubGlobal('createImageBitmap', bitmap)

    const result = await saveCover('book-1', new Blob(['not a picture'], { type: 'application/pdf' }))

    expect(result).toEqual({
      kind: 'refused',
      message: 'Обложкой может быть PNG, JPEG или WebP.',
    })
    expect(bitmap).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('сообщает понятным текстом, а не падает, если внутри .png лежит не картинка', async () => {
    vi.stubGlobal(
      'createImageBitmap',
      vi.fn().mockRejectedValue(new Error('decode failed')),
    )

    const result = await saveCover('book-1', new Blob(['broken'], { type: 'image/png' }))

    expect(result).toEqual({
      kind: 'refused',
      message: 'Этот файл не разбирается как картинка.',
    })
    vi.unstubAllGlobals()
  })
})
