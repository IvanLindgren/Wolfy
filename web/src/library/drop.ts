/** Отличает внешний перенос файлов от внутренней сортировки обложек. */
export function isFileDrag(types: Iterable<string> | ArrayLike<string>): boolean {
  return Array.from(types).includes('Files')
}

/** Берёт только файлы: текст/URL, случайно брошенные на полку, игнорируются. */
export function droppedFiles(
  transfer: Pick<DataTransfer, 'types' | 'files'>,
): File[] {
  return isFileDrag(transfer.types) ? Array.from(transfer.files) : []
}
