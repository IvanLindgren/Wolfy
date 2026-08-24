import { describe, expect, it } from 'vitest'

import { droppedFiles, isFileDrag } from '../../src/library/drop'

describe('drag-and-drop import', () => {
  it('принимает несколько файлов из системного переноса', () => {
    const first = new File(['one'], 'one.epub')
    const second = new File(['two'], 'two.pdf')
    const transfer = {
      types: ['Files'],
      files: { 0: first, 1: second, length: 2, item: (index: number) => [first, second][index] ?? null },
    } as unknown as Pick<DataTransfer, 'types' | 'files'>

    expect(isFileDrag(transfer.types)).toBe(true)
    expect(droppedFiles(transfer)).toEqual([first, second])
  })

  it('не путает ссылку и внутреннюю сортировку с файлами', () => {
    expect(isFileDrag(['text/uri-list'])).toBe(false)
    expect(isFileDrag(['application/x-dnd-kit'])).toBe(false)
  })
})
