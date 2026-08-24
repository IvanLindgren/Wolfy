import { beforeEach, describe, expect, it, vi } from 'vitest'

const pdf = vi.hoisted(() => {
  const cleanup = vi.fn()
  const destroy = vi.fn(async () => undefined)
  const getPage = vi.fn(async (number: number) => ({
    getTextContent: async () => ({
      items: number === 1
        ? [
            { str: 'Hello', transform: [1, 0, 0, 1, 0, 20] },
            { str: ' world', transform: [1, 0, 0, 1, 50, 20] },
          ]
        : [{ str: 'Second page', transform: [1, 0, 0, 1, 0, 10] }],
    }),
    cleanup,
  }))
  const document = { numPages: 2, getPage, destroy }
  return {
    cleanup,
    destroy,
    getPage,
    document,
    GlobalWorkerOptions: { workerPort: null as unknown },
    getDocument: vi.fn(() => ({ promise: Promise.resolve(document) })),
  }
})

class TestPDFWorker {
  readonly name: string

  constructor(options?: WorkerOptions) {
    this.name = options?.name ?? ''
  }
}

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: pdf.GlobalWorkerOptions,
  getDocument: pdf.getDocument,
}))

vi.mock('pdfjs-dist/build/pdf.worker.min.mjs?worker', () => ({
  default: TestPDFWorker,
}))

import { extractPdfPages } from '../../src/library/pdf'

describe('PDF import', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    pdf.document.numPages = 2
  })

  it('использует настоящий Vite worker и сохраняет физические страницы', async () => {
    const pages = await extractPdfPages(new Uint8Array([1, 2, 3]).buffer)

    expect(pages).toEqual(['Hello world', 'Second page'])
    expect(pdf.GlobalWorkerOptions.workerPort).toBeInstanceOf(TestPDFWorker)
    expect((pdf.GlobalWorkerOptions.workerPort as TestPDFWorker).name).toBe('wolfy-pdf')
    expect(pdf.getPage).toHaveBeenCalledTimes(2)
    expect(pdf.cleanup).toHaveBeenCalledTimes(2)
    expect(pdf.destroy).toHaveBeenCalledOnce()
  })

  it('явно отказывает вместо незаметного обрезания слишком длинной книги', async () => {
    pdf.document.numPages = 3001

    await expect(
      extractPdfPages(new Uint8Array([1, 2, 3]).buffer),
    ).rejects.toThrow('В PDF 3001 страниц')
    expect(pdf.getPage).not.toHaveBeenCalled()
    expect(pdf.destroy).toHaveBeenCalledOnce()
  })
})
