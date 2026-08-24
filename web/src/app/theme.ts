/**
 * Тема и набор на корне документа.
 *
 * Тема живёт в настройках ядра и синхронизируется между устройствами — но
 * ядро поднимается через сотни миллисекунд после первой краски, а вспышка
 * кремовой бумаги в лицо тому, кто выбрал OLED-чёрную, случится за первые
 * двадцать. Поэтому выбор дублируется в `localStorage`: не как источник
 * истины, а как способ покрасить экран до того, как истина приедет.
 *
 * Токен в `localStorage` — недопустимо; имя темы — ровно наоборот: это не
 * секрет, а то, что должно быть известно раньше всего остального.
 */

import type { AppSettings, ThemeName } from '../core/types'
import { motion, noMotion, systemPrefersReducedMotion, type Motion } from '../theme/motion'

const THEME_KEY = 'wolfy.theme'
const SCALE_KEY = 'wolfy.scale'
const ACCENT_KEY = 'wolfy.accent'

export const THEMES: { name: ThemeName; title: string; hint: string }[] = [
  { name: 'Paper', title: 'Газета', hint: 'Белизна бумаги и густые чернила' },
  { name: 'Sepia', title: 'Сепия', hint: 'Мягкий вечерний свет' },
  { name: 'Dark', title: 'Угольная', hint: 'Глубокие чернила без тепла' },
  { name: 'Oled', title: 'OLED', hint: 'Чёрный без остатка' },
]

/**
 * Палитры акцента.
 *
 * Тема отвечает за бумагу и чернила, палитра — за единственную краску поверх
 * них. Разделены они потому, что это два разных вопроса: «светло или темно
 * глазам» и «какого цвета кнопка». Связать их значило бы отнять у читателя
 * тёмную тему с тёплым акцентом — сочетание, которое многие и выбирают.
 *
 * Выбор хранится **на устройстве**, а не в настройках ядра: поля для него в
 * `AppSettings` нет, и заводить его ради веба значило бы завести вторую
 * модель данных — то, что задание прямо запрещает. Тема при этом продолжает
 * ездить в синхронизацию, как и ездила.
 */
export const ACCENTS: { name: AccentName; title: string; light: string; dark: string }[] = [
  { name: 'teal', title: 'Морская волна', light: '#1f5f66', dark: '#63b3ac' },
  { name: 'indigo', title: 'Индиго', light: '#3d4f8c', dark: '#93a3e0' },
  { name: 'plum', title: 'Слива', light: '#6d3f6b', dark: '#c294c0' },
  { name: 'forest', title: 'Хвоя', light: '#3a6b4a', dark: '#7fb894' },
  { name: 'clay', title: 'Терракота', light: '#9a4f38', dark: '#dc9078' },
  { name: 'ink', title: 'Графит', light: '#3b3b38', dark: '#b9b3a6' },
]

/**
 * Тёмная ли сейчас бумага.
 *
 * Образец краски в настройках обязан показывать ту половину пары, которую
 * читатель увидит на кнопках: показать светлую половину поверх угольной темы
 * значит соврать про каждый из шести кружков.
 */
export function onDarkPaper(theme: string): boolean {
  if (theme === 'Dark' || theme === 'Oled') return true
  if (theme === 'Paper' || theme === 'Sepia') return false
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches
}

export type AccentName = 'teal' | 'indigo' | 'plum' | 'forest' | 'clay' | 'ink'

function isAccent(value: string | null): value is AccentName {
  return ACCENTS.some((accent) => accent.name === value)
}

/** Выбранная палитра. По умолчанию — та, что стоит в `:root`. */
export function accent(): AccentName {
  try {
    const stored = localStorage.getItem(ACCENT_KEY)
    return isAccent(stored) ? stored : 'teal'
  } catch {
    return 'teal'
  }
}

export function applyAccent(name: AccentName): void {
  document.documentElement.setAttribute('data-accent', name)
  try {
    localStorage.setItem(ACCENT_KEY, name)
  } catch {
    // Приватный режим: палитра вернётся к морской волне после перезагрузки.
  }
}

/** Цвет системных панелей: он же фон темы. */
const THEME_COLOR: Record<ThemeName, string> = {
  Paper: '#fbf9f5',
  Sepia: '#f4efe6',
  Dark: '#231f20',
  Oled: '#000000',
}

function isTheme(value: string | null): value is ThemeName {
  return value === 'Paper' || value === 'Sepia' || value === 'Dark' || value === 'Oled'
}

/** Красит документ до того, как поднимется ядро. */
export function applyStoredTheme(): void {
  try {
    const stored = localStorage.getItem(THEME_KEY)
    if (isTheme(stored)) applyTheme(stored)

    applyAccent(accent())

    const scale = localStorage.getItem(SCALE_KEY)
    if (scale) {
      const [font, line] = scale.split(':').map(Number)
      if (font) document.documentElement.style.setProperty('--font-scale', String(font))
      if (line) document.documentElement.style.setProperty('--line-scale', String(line))
    }
  } catch {
    // Приватный режим запрещает хранилище. Тема тогда следует системе — это
    // рабочее поведение, а не поломка.
  }
}

export function applyTheme(theme: ThemeName): void {
  document.documentElement.setAttribute('data-theme', theme)
  const meta = document.querySelector('meta[name="theme-color"]:not([media])')
  if (meta) meta.setAttribute('content', THEME_COLOR[theme])
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    // См. выше.
  }
}

/** Переносит настройки ядра на документ: тема, кегль, интерлиньяж, движение. */
export function applySettings(settings: AppSettings): void {
  if (isTheme(settings.theme)) applyTheme(settings.theme)

  const root = document.documentElement
  root.style.setProperty('--font-scale', String(settings.fontScale))
  root.style.setProperty('--line-scale', String(settings.lineScale))

  // Атрибут читают стили: часть движения живёт в CSS, и оно обязано
  // выключаться тем же переключателем, что и остальное.
  const quiet = settings.reduceMotion || systemPrefersReducedMotion()
  root.setAttribute('data-motion', quiet ? 'none' : 'full')

  try {
    localStorage.setItem(SCALE_KEY, `${settings.fontScale}:${settings.lineScale}`)
  } catch {
    // См. выше.
  }
}

/**
 * Темп движения с учётом обеих настроек — приложения и системы.
 *
 * Возвращается **весь объект** нулями, а не отдельные флаги: экран, который
 * решает про каждую анимацию сам, однажды забудет спросить.
 */
export function motionFor(settings: AppSettings): Motion {
  return settings.reduceMotion || systemPrefersReducedMotion() ? noMotion : motion
}
