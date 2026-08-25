/**
 * Клавиатура — контракт, а не украшение.
 *
 * Веб-версия это в том числе десктопный клиент, и весь он обязан управляться
 * без мыши. Здесь описана одна тонкость, из-за которой такие вещи обычно и
 * ломаются: **порядок перехвата**.
 *
 * - Читалка забирает пробел и стрелки **до потомков** (capture): иначе их
 *   съест собственная прокрутка списка абзацев, и страница не перелистнётся.
 * - Всё остальное ловится **после потомков** (bubble) и пропускает события из
 *   текстовых полей: цифры и `Enter` должны достаться полю ввода, а не
 *   тренировке. Читатель, вводящий слово по памяти, набирает «1» как букву
 *   ответа, а не как выбор первого варианта.
 */

import { useEffect } from 'react'

export interface Shortcut {
  /** `event.key` как есть: `ArrowRight`, `Escape`, `s`, `?`. Регистр не важен. */
  key: string
  ctrl?: boolean
  shift?: boolean
  alt?: boolean
  run: (event: KeyboardEvent) => void
}

interface Options {
  /** Перехватывать до потомков. Нужно только читалке. */
  capture?: boolean
  /** Работают ли клавиши сейчас. Выключенный экран не должен ловить чужое. */
  enabled?: boolean
  /**
   * Ловить ли клавиши, набранные в поле ввода. По умолчанию — нет: поле
   * важнее любого сочетания без модификатора.
   */
  inFields?: boolean
}

/** Набирает ли читатель текст прямо сейчас. */
export function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

/** Должен ли Enter остаться нативному интерактивному элементу. */
export function isInteractive(target: EventTarget | null): boolean {
  if (isTyping(target)) return true
  if (!(target instanceof Element)) return false
  return target.closest([
    'button',
    'a[href]',
    'summary',
    '[contenteditable]:not([contenteditable="false"])',
    '[role="button"]',
    '[role="link"]',
    '[role="menuitem"]',
    '[role="option"]',
    '[role="tab"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="switch"]',
  ].join(',')) !== null
}

function matches(shortcut: Shortcut, event: KeyboardEvent): boolean {
  if (shortcut.key.toLowerCase() !== event.key.toLowerCase()) return false
  if (!!shortcut.ctrl !== (event.ctrlKey || event.metaKey)) return false
  if (shortcut.shift !== undefined && shortcut.shift !== event.shiftKey) return false
  if (shortcut.alt !== undefined && shortcut.alt !== event.altKey) return false
  return true
}

export function useShortcuts(shortcuts: Shortcut[], options: Options = {}): void {
  const { capture = false, enabled = true, inFields = false } = options

  useEffect(() => {
    if (!enabled) return

    function handle(event: KeyboardEvent) {
      // Повторы от зажатой клавиши не превращаем в десять перелистываний:
      // читатель, задержавший стрелку, хочет одну страницу, а не главу.
      if (event.repeat && event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
      // Enter на кнопке/ссылке принадлежит сфокусированному контролу. Иначе
      // глобальное «сохранить слово» закрывало карточку вместо раскрытия
      // подробностей или переключения графа.
      if (event.key === 'Enter' && isInteractive(event.target)) return
      if (!inFields && isTyping(event.target)) return

      for (const shortcut of shortcuts) {
        if (matches(shortcut, event)) {
          event.preventDefault()
          event.stopPropagation()
          shortcut.run(event)
          return
        }
      }
    }

    window.addEventListener('keydown', handle, { capture })
    return () => window.removeEventListener('keydown', handle, { capture })
  }, [shortcuts, capture, enabled, inFields])
}

/** Шпаргалка по клавишам — то, что показывает `?`. */
export const CHEAT_SHEET: { keys: string; action: string }[] = [
  { keys: 'Вправо · PageDown · Пробел', action: 'Страница вперёд' },
  { keys: 'Влево · PageUp · Shift+Пробел', action: 'Страница назад' },
  { keys: 'Home · End', action: 'Начало и конец главы' },
  { keys: 'Esc', action: 'Назад: оглавление, карточка, книга' },
  { keys: 'Enter', action: 'Сохранить слово · следующий вопрос' },
  { keys: 'S', action: 'Произнести слово' },
  { keys: '1–9', action: 'Вариант ответа в тренировке' },
  { keys: 'Ctrl+1…6', action: 'Разделы приложения' },
  { keys: '?', action: 'Эта шпаргалка' },
]
