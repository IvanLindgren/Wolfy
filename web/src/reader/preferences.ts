/**
 * Настройки чтения, живущие на устройстве.
 *
 * Здесь ровно одна вещь — режим чтения: страницы или лента. Она не едет в
 * синхронизацию намеренно. Кегль, интерлиньяж и тема принадлежат читателю и
 * должны быть одинаковы везде; способ листать принадлежит **устройству**: на
 * телефоне удобны страницы, на большом мониторе с колесом мыши — лента, и
 * тащить одно на другое значит испортить оба.
 *
 * Заводить ради этого поле в `AppSettings` ядра нельзя: это была бы вторая
 * модель данных для веба — то, чего задание прямо запрещает.
 */

const MODE_KEY = 'wolfy.readingMode'
const MEASURE_KEY = 'wolfy.measure'
const FONT_KEY = 'wolfy.readerFont'

export type ReadingMode = 'pages' | 'scroll'
export type ReaderFont = 'serif' | 'sans'

export function readingMode(): ReadingMode {
  try {
    return localStorage.getItem(MODE_KEY) === 'scroll' ? 'scroll' : 'pages'
  } catch {
    return 'pages'
  }
}

export function setReadingMode(mode: ReadingMode): void {
  try {
    localStorage.setItem(MODE_KEY, mode)
  } catch {
    // Приватный режим: способ листать сбросится к страницам после
    // перезагрузки, и это не поломка.
  }
}

export function readerMeasure(): number {
  try { return Math.max(54, Math.min(80, Number(localStorage.getItem(MEASURE_KEY)) || 66)) } catch { return 66 }
}

export function setReaderMeasure(value: number): void {
  const safe = Math.max(54, Math.min(80, value))
  document.documentElement.style.setProperty('--measure', `${safe}ch`)
  try { localStorage.setItem(MEASURE_KEY, String(safe)) } catch { /* настройка останется до перезагрузки */ }
}

export function readerFont(): ReaderFont {
  try { return localStorage.getItem(FONT_KEY) === 'sans' ? 'sans' : 'serif' } catch { return 'serif' }
}

export function setReaderFont(value: ReaderFont): void {
  document.documentElement.style.setProperty('--reader-font', value === 'sans' ? 'var(--sans)' : 'var(--serif)')
  try { localStorage.setItem(FONT_KEY, value) } catch { /* настройка останется до перезагрузки */ }
}

export function applyReaderPreferences(): void {
  setReaderMeasure(readerMeasure())
  setReaderFont(readerFont())
}
