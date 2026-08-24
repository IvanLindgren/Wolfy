import { useEffect } from 'react'
import { useNavigate } from '@tanstack/react-router'

import { session, useSession } from '../core/session'
import { useToasts } from '../app/toasts'

const NEXT_KEY = 'wolfy.nextReminder'
let timer = 0

export function notificationPermission(): NotificationPermission | 'unavailable' {
  return typeof Notification === 'undefined' ? 'unavailable' : Notification.permission
}

export async function enableReviewNotifications(): Promise<NotificationPermission | 'unavailable'> {
  if (typeof Notification === 'undefined') return 'unavailable'
  return Notification.requestPermission()
}

export function ReminderController() {
  const ready = useSession((state) => state.ready)
  const revision = useSession((state) => state.library.revision)
  const intensity = useSession((state) => state.settings.intensity)
  const navigate = useNavigate()

  useEffect(() => {
    if (!ready) return
    let alive = true
    void session.reminderAt().then((at) => {
      if (!alive) return
      if (at) localStorage.setItem(NEXT_KEY, String(at)); else localStorage.removeItem(NEXT_KEY)
      arm(at)
    })
    return () => { alive = false }
  }, [intensity, ready, revision])

  useEffect(() => {
    const at = Number(localStorage.getItem(NEXT_KEY) ?? 0)
    if (!at || at > Date.now()) return
    void session.due().then((cards) => {
      if (!cards.length) return
      if (notificationPermission() === 'granted') void showSystem(cards.length)
      else useToasts.getState().show(`Пора повторить: ${cards.length} ${cards.length === 1 ? 'карточка' : 'карточек'}`, { label: 'Открыть', run: () => void navigate({ to: '/decks' }) })
    })
  }, [navigate, ready])
  return null
}

function arm(at: number | null) {
  window.clearTimeout(timer)
  if (!at) return
  const delay = at - Date.now()
  if (delay <= 0) { void notifyDue(); return }
  timer = window.setTimeout(() => arm(at), Math.min(delay, 2_000_000_000))
}

async function notifyDue() {
  const cards = await session.due()
  if (!cards.length) return
  if (notificationPermission() === 'granted') await showSystem(cards.length)
  else localStorage.setItem(NEXT_KEY, String(Date.now()))
}

async function showSystem(count: number) {
  const registration = await navigator.serviceWorker?.ready.catch(() => null)
  const options: NotificationOptions = { body: `${count} ${count === 1 ? 'карточка ждёт' : 'карточек ждут'} короткого повторения.`, icon: '/icons/icon-192.png', badge: '/icons/icon-192.png', tag: 'wolfy-review' }
  if (registration) await registration.showNotification('Wolfy помнит про слова', options)
  else new Notification('Wolfy помнит про слова', options)
}
