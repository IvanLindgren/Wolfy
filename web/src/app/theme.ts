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

export const THEMES: { name: ThemeName; title: string; hint: string }[] = [
  { name: 'Paper', title: 'Газета', hint: 'Белизна бумаги и густые чернила' },
  { name: 'Sepia', title: 'Сепия', hint: 'Мягкий вечерний свет' },
  { name: 'Dark', title: 'Угольная', hint: 'Глубокие чернила без тепла' },
  { name: 'Oled', title: 'OLED', hint: 'Чёрный без остатка' },
]

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
