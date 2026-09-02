/**
 * Что можно скачать и как об этом спросить сервер.
 *
 * ## Почему список не записан в страницу руками
 *
 * Пакет называется по версии — `Wolfy-0.1.5.msi`, — и версия меняется каждый
 * выпуск. Ссылка, набранная в вёрстке, живёт до первого обновления и потом
 * ведёт в 404: страница загрузок ломается ровно тогда, когда выходит новая
 * сборка, то есть когда за ней и приходят.
 *
 * Поэтому страница спрашивает сервер тем же запросом, каким спрашивают сами
 * приложения (`/v1/update/latest`), и показывает то, что на сервере правда
 * лежит: версию, размер и адрес. Платформа, для которой ещё ничего не
 * выложено, честно так и говорит — вместо ссылки в пустоту.
 */

/** Ключ платформы у сервера обновлений. Совпадает с `updates.patternFor`. */
export type Platform = 'android' | 'windows' | 'windows-arm64' | 'linux'

export interface Release {
  version: string
  /** Адрес файла на сервере, относительный: раздача с того же origin. */
  url: string
  sha256: string
  size: number
}

export interface Edition {
  platform: Platform
  /** Крупная подпись колонки. */
  title: string
  /** Расширение файла — им подписана кнопка. */
  kind: string
  /** Кому это, одной строкой. */
  who: string
  /** Что сделать с файлом после скачивания. */
  install: string
}

/**
 * Порядок колонок.
 *
 * Не алфавитный: сперва то, чем пользуются с телефона, потом настольные
 * машины от самой частой к самой редкой. Windows на ARM стоит рядом с
 * обычной, а не в конце: их путают, и разводить их по разным углам страницы
 * значит помогать скачать не тот файл.
 */
export const EDITIONS: Edition[] = [
  {
    platform: 'android',
    title: 'Android',
    kind: 'APK',
    who: 'Телефон и планшет, Android 8+',
    install: 'Откройте файл и разрешите установку.',
  },
  {
    platform: 'windows',
    title: 'Windows · x64',
    kind: 'MSI',
    who: 'ПК и ноутбуки на Intel или AMD',
    install: 'Запустите установщик.',
  },
  {
    platform: 'windows-arm64',
    title: 'Windows · ARM64',
    kind: 'MSI',
    who: 'Ноутбуки на Snapdragon',
    install: 'Собран под ARM: x64-сборка здесь не встанет.',
  },
  {
    platform: 'linux',
    title: 'Linux · x64',
    kind: 'DEB',
    who: 'Debian, Ubuntu и родственники',
    install: 'Откройте менеджером пакетов.',
  },
]

/** Ссылка на приложение в Google Play. */
export const PLAY_URL = 'https://play.google.com/store/apps/details?id=com.wolfy.reader'

/** Телеграм-канал проекта. */
export const TELEGRAM_URL = 'https://t.me/citavuk'

/** Исходный код. */
export const SOURCE_URL = 'https://github.com/IvanLindgren/Wolfy'

/**
 * Спрашивает у сервера самую свежую сборку платформы.
 *
 * `current=0.0.0` означает «у меня не стоит ничего»: сервер отвечает
 * последним пакетом, какой у него есть. Ответ 204 — «нечего предложить», и
 * это не ошибка: так выглядит платформа, для которой выпуск ещё не собран.
 */
export async function latestRelease(platform: Platform, signal?: AbortSignal): Promise<Release | null> {
  const response = await fetch(
    `/v1/update/latest?platform=${encodeURIComponent(platform)}&current=0.0.0`,
    { signal, headers: { Accept: 'application/json' } },
  )
  if (response.status === 204) return null
  if (!response.ok) throw new Error(`сервер ответил ${response.status}`)
  const body = (await response.json()) as Release
  if (!body?.url || !body?.version) throw new Error('сервер ответил не пакетом')
  return body
}

/**
 * Размер файла человеческими словами.
 *
 * До гигабайта хватает мегабайт: установщик читалки меряется десятками, и
 * «0.07 ГБ» сообщает меньше, чем «68 МБ». Округление до целого там, где число
 * трёхзначное: «103.4 МБ» — точность, которую никто не проверяет.
 */
export function fileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return ''
  const mb = bytes / (1024 * 1024)
  if (mb < 1) return `${Math.max(1, Math.round(bytes / 1024))} КБ`
  if (mb < 100) return `${mb.toFixed(1).replace('.', ',')} МБ`
  return `${Math.round(mb)} МБ`
}

/** Короткий отпечаток для глаза: полные 64 знака никто не сверяет целиком. */
export function shortSum(sha256: string): string {
  const clean = sha256.trim().toLowerCase()
  return /^[0-9a-f]{64}$/.test(clean) ? `${clean.slice(0, 8)}…${clean.slice(-8)}` : ''
}
