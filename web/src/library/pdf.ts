/**
 * Извлечение текста из PDF — единственное, что веб делает не ядром.
 *
 * Причина техническая и названа прямо: `pdf-extract` не собирается под
 * `wasm32-unknown-unknown`. Замена равноценная — `pdf.js` достаёт тот же
 * текстовый слой, а дальше он идёт в ядро как обычный текст и разбирается
 * тем же кодом, что и всё остальное.
 *
 * Условие замены одно и оно жёсткое: **постраничное деление сохраняется**.
 * Одна физическая страница — одна единица навигации, включая пустые. Склей
 * их — и номера, оглавление и прогресс уедут после первой же иллюстрации без
 * текстового слоя.
 *
 * Библиотека грузится динамически: она весит около полумегабайта, а нужна
 * только тому, кто принёс PDF. Оболочке с бюджетом в 200 КБ она не по
 * карману.
 */

/** Сколько страниц PDF считаем разумным пределом. */
const MAX_PAGES = 3000

export async function extractPdfPages(bytes: ArrayBuffer): Promise<string[]> {
  const pdfjs = await import('pdfjs-dist')
  // Воркер `pdf.js` собирается Vite как отдельный модуль: без него разбор
  // идёт в главном потоке и подвешивает интерфейс на весь документ.
  const worker = await import('pdfjs-dist/build/pdf.worker.min.mjs?url')
  pdfjs.GlobalWorkerOptions.workerSrc = worker.default

  const document = await pdfjs.getDocument({ data: new Uint8Array(bytes) }).promise
  const total = Math.min(document.numPages, MAX_PAGES)
  const pages: string[] = []

  try {
    for (let number = 1; number <= total; number += 1) {
      const page = await document.getPage(number)
      const content = await page.getTextContent()
      pages.push(linesOf(content.items as TextItem[]))
      // Страница держит свои растровые ресурсы до явной очистки; на книге в
      // триста страниц это сотни мегабайт.
      page.cleanup()
    }
  } finally {
    await document.destroy()
  }

  return pages
}

interface TextItem {
  str?: string
  hasEOL?: boolean
  transform?: number[]
}

/**
 * Собирает строки страницы.
 *
 * `pdf.js` отдаёт куски текста с координатами, а не строки: в PDF нет
 * абзацев, есть символы на листе. Строки восстанавливаются по вертикальной
 * координате — куски на одной высоте принадлежат одной строке.
 *
 * Дальше работу доделывает ядро: `parser/pdf.rs` склеивает строки в абзацы и
 * снимает переносы через дефис. Дублировать это здесь значило бы завести
 * вторую реализацию того же правила.
 */
function linesOf(items: TextItem[]): string {
  const lines: { y: number; parts: string[] }[] = []

  for (const item of items) {
    const text = item.str ?? ''
    if (!text) {
      if (item.hasEOL && lines.length) lines.push({ y: Number.NaN, parts: [] })
      continue
    }
    const y = item.transform?.[5] ?? 0
    const last = lines[lines.length - 1]

    // Полтора пункта разницы — это всё ещё одна строка: у надстрочных знаков
    // и мелкого кегля базовая линия слегка гуляет.
    if (last && Number.isFinite(last.y) && Math.abs(last.y - y) < 1.5) {
      last.parts.push(text)
    } else {
      lines.push({ y, parts: [text] })
    }
  }

  return lines
    .map((line) => line.parts.join('').replace(/\s+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
