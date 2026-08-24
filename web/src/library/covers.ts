/**
 * Своя обложка книги.
 *
 * У EPUB обложка лежит внутри файла, у TXT и PDF её нет вовсе, а у книги,
 * собранной из снимков, — тем более. Набранная обложка (название антиквой,
 * автор ниже) честнее серого прямоугольника, но полка из двадцати одинаково
 * набранных корешков всё равно ищется хуже, чем полка с картинками.
 *
 * Картинка хранится **на устройстве**, рядом с самой книгой в OPFS, и никуда
 * не отправляется: книги пользователя не покидают устройство — это правило
 * задания, и обложка часть книги, а не отдельная сущность. Поля для неё в
 * `LibraryBook` ядра нет, и заводить его ради веба значило бы завести вторую
 * модель данных.
 *
 * Снимок с телефона весит мегабайты, а корешок на полке — сто пятьдесят
 * пикселей в ширину. Поэтому картинка ужимается до разумного размера перед
 * записью: хранить четыре мегабайта ради превью значит заполнить квоту
 * браузера двумя десятками книг и получить внезапную очистку хранилища.
 */

import { create } from 'zustand'

import { readFile, removeFile, writeFile } from '../storage/opfs'

/** Длинная сторона сохранённой обложки. Больше полке не нужно. */
const MAX_SIDE = 900

/** Форматы, которые принимает выбор файла и умеет разобрать браузер. */
export const COVER_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const

export const COVER_ACCEPT = '.png,.jpg,.jpeg,.webp,image/png,image/jpeg,image/webp'

function pathOf(bookId: string): string {
  return `covers/${bookId}.jpg`
}

export type CoverResult =
  | { kind: 'saved' }
  | { kind: 'refused'; message: string }

/**
 * Сохраняет выбранную картинку как обложку книги.
 *
 * Тип проверяется по самому файлу, а не по расширению: `.png`, внутри
 * которого лежит что угодно, `createImageBitmap` не разберёт, и упасть на
 * этом должно с понятным текстом, а не исключением в консоли.
 */
export async function saveCover(bookId: string, file: File | Blob): Promise<CoverResult> {
  const type = file.type.toLowerCase()
  if (!COVER_TYPES.includes(type as (typeof COVER_TYPES)[number])) {
    return {
      kind: 'refused',
      message: 'Обложкой может быть PNG, JPEG или WebP.',
    }
  }

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    return { kind: 'refused', message: 'Этот файл не разбирается как картинка.' }
  }

  try {
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * scale))
    canvas.height = Math.max(1, Math.round(bitmap.height * scale))

    const context = canvas.getContext('2d')
    if (!context) return { kind: 'refused', message: 'Браузер не дал холст для сжатия.' }
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)

    const compressed = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', 0.86),
    )
    if (!compressed) return { kind: 'refused', message: 'Картинку не удалось сжать.' }

    await writeFile(pathOf(bookId), new Uint8Array(await compressed.arrayBuffer()))
    return { kind: 'saved' }
  } finally {
    // Битмап держит распакованные пиксели: у снимка с камеры это полсотни
    // мегабайт, и ждать сборщик мусора здесь незачем.
    bitmap.close()
  }
}

/** Своя обложка книги, если читатель её ставил. */
export async function customCover(bookId: string): Promise<Uint8Array | null> {
  try {
    return await readFile(pathOf(bookId))
  } catch {
    return null
  }
}

export async function removeCover(bookId: string): Promise<void> {
  await removeFile(pathOf(bookId))
}

/**
 * Отметка о смене обложки.
 *
 * Превью читает файл один раз и держит `blob:`-ссылку; после замены файла
 * ссылка ведёт на прежние байты, и полка показывала бы старую картинку до
 * перезагрузки страницы. Счётчик версий — самый дешёвый способ сказать
 * превью «перечитай», не заводя наблюдения за файловой системой.
 */
export const useCoverStamp = create<{
  stamps: Record<string, number>
  bump: (bookId: string) => void
}>((set) => ({
  stamps: {},
  bump: (bookId) =>
    set((state) => ({
      stamps: { ...state.stamps, [bookId]: (state.stamps[bookId] ?? 0) + 1 },
    })),
}))
