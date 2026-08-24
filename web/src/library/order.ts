/**
 * Порядок корешков на полке.
 *
 * Живёт на устройстве и в синхронизацию не едет. У книги в ядре нет поля
 * порядка, и заводить его ради веба нельзя — это была бы вторая модель данных
 * для одной библиотеки. А порядок, который читатель задал перетаскиванием,
 * терять при перезагрузке вкладки тоже нельзя.
 *
 * Компромисс честный: полка, прогресс и колода едут на другие устройства,
 * порядок корешков — нет. Притворяться, что едет, было бы хуже.
 */

const ORDER_KEY = 'wolfy.bookOrder'

export function bookOrder(): string[] {
  try {
    const stored = localStorage.getItem(ORDER_KEY)
    return stored ? (JSON.parse(stored) as string[]) : []
  } catch {
    return []
  }
}

export function saveBookOrder(order: string[]): void {
  try {
    localStorage.setItem(ORDER_KEY, JSON.stringify(order))
  } catch {
    // Приватный режим: порядок вернётся к «сначала новые», и это рабочее
    // поведение, а не поломка.
  }
}
