/**
 * Время и свежие номера — то, чего у ядра нет.
 *
 * Часов и генератора случайностей внутри ядра нет намеренно: с ними логику
 * сдвига дат нельзя проверить тестом, а серию дней — доказать. Поэтому всё
 * приходит параметрами команды, и собирается это здесь, в одном месте.
 */

/** Сейчас, в UTC-миллисекундах. */
export function now(): number {
  return Date.now()
}

/**
 * Смещение часового пояса **в минутах**.
 *
 * В минутах, а не в часах: Индия живёт на +5:30, Непал на +5:45, и деление на
 * часы сдвигает им границу дня — вместе с ней уезжает серия занятий.
 *
 * Знак развёрнут относительно `getTimezoneOffset`: тот считает, сколько
 * прибавить к местному времени, чтобы получить UTC, а ядру нужно обратное —
 * насколько местное время впереди UTC.
 */
export function offsetMinutes(): number {
  return -new Date().getTimezoneOffset()
}

/**
 * Свежий номер.
 *
 * UUID, а не счётчик: книга получает номер до того, как впервые дойдёт до
 * сети, — иначе её нельзя добавить в самолёте, — а на сервере под этот номер
 * отведена колонка uuid.
 */
export function newId(): string {
  const source: Crypto | undefined = globalThis.crypto
  if (source?.randomUUID) return source.randomUUID()

  // Небезопасный контекст (обычный http без localhost): `randomUUID` там
  // недоступен. Номер всё равно должен получиться — книга важнее.
  const bytes = new Uint8Array(16)
  source.getRandomValues(bytes)
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Местный день числом — тем же способом, что считает ядро. */
export function localDay(at: number = now(), offset: number = offsetMinutes()): number {
  return Math.floor((at + offset * 60_000) / 86_400_000)
}

/** «сегодня», «вчера», «3 дня назад» — для списков и диагностики. */
export function relativeDay(at: number): string {
  if (!at) return 'ни разу'
  const days = localDay() - localDay(at)
  if (days <= 0) return 'сегодня'
  if (days === 1) return 'вчера'
  if (days < 7) return `${days} ${plural(days, 'день', 'дня', 'дней')} назад`
  return new Date(at).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
}

/** Срок карточки: прошедшие даты и будущие интервалы не смешиваются. */
export function dueDay(
  at: number,
  reference: number = now(),
  offset: number = offsetMinutes(),
): string {
  if (!at) return 'новая'
  const days = localDay(at, offset) - localDay(reference, offset)
  if (days < 0) {
    const ago = -days
    if (ago === 1) return 'вчера'
    if (ago < 7) return `${ago} ${plural(ago, 'день', 'дня', 'дней')} назад`
    return new Date(at).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
  }
  if (days === 0) return 'сегодня'
  if (days === 1) return 'завтра'
  if (days < 7) return `через ${days} ${plural(days, 'день', 'дня', 'дней')}`
  return new Date(at).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })
}

/** Час и минута местного времени. */
export function clockTime(at: number): string {
  return new Date(at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

export function plural(count: number, one: string, few: string, many: string): string {
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) return many
  if (mod10 === 1) return one
  if (mod10 >= 2 && mod10 <= 4) return few
  return many
}
