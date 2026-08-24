/**
 * Короткие сообщения внизу экрана.
 *
 * Их работа — сказать, что случилось то, чего читатель не видит: книга уже
 * есть в библиотеке, синхронизация не прошла, слово убрано из колоды. Всё,
 * что видно и так, сообщения не получает: слово, улетевшее в колоду по дуге,
 * не нуждается в подписи «слово добавлено».
 */

import { create } from 'zustand'

export interface Toast {
  id: number
  text: string
  /** Единственное действие: «Отменить», «Открыть». Двух не бывает. */
  action?: { label: string; run: () => void }
  /** Сколько держать. Ноль — до закрытия читателем. */
  lifetime: number
}

interface ToastState {
  toasts: Toast[]
  show: (text: string, action?: Toast['action'], lifetime?: number) => void
  dismiss: (id: number) => void
}

let counter = 0

export const useToasts = create<ToastState>((set) => ({
  toasts: [],

  show(text, action, lifetime = action ? 6000 : 3600) {
    const id = ++counter
    set((state) => ({ toasts: [...state.toasts, { id, text, action, lifetime }] }))
    if (lifetime > 0) {
      setTimeout(() => {
        set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }))
      }, lifetime)
    }
  },

  dismiss(id) {
    set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) }))
  },
}))

/** Короткий путь: `toast('Книга уже в библиотеке')`. */
export function toast(text: string, action?: Toast['action']): void {
  useToasts.getState().show(text, action)
}
