/**
 * Кто вошёл и что умеет этот сервер.
 *
 * Оба ответа — состояние сервера, а не ядра, поэтому живут в TanStack Query с
 * его кэшем и повторами. И оба обязаны уметь отвечать «никто» и «ничего»:
 * **без аккаунта работает всё, кроме синхронизации и ленты**, и приложение не
 * должно выглядеть сломанным оттого, что читатель не вошёл.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import * as api from '../api/client'
import { newId } from '../core/clock'

const DEVICE_KEY = 'wolfy.device'

/**
 * Описание устройства для Читавука.
 *
 * Номер устройства держится в `localStorage` — в отличие от токена, это не
 * секрет: он нужен, чтобы список сессий в аккаунте не превращался в десять
 * одинаковых «Браузер» после десяти входов.
 */
export function deviceInfo(): api.DeviceInfo {
  let id = ''
  try {
    id = localStorage.getItem(DEVICE_KEY) ?? ''
    if (!id) {
      id = newId()
      localStorage.setItem(DEVICE_KEY, id)
    }
  } catch {
    id = newId()
  }
  return { id, name: browserName(), platform: 'web' }
}

function browserName(): string {
  const agent = navigator.userAgent
  const engine = /Firefox\//.test(agent)
    ? 'Firefox'
    : /Edg\//.test(agent)
      ? 'Edge'
      : /Chrome\//.test(agent)
        ? 'Chrome'
        : /Safari\//.test(agent)
          ? 'Safari'
          : 'Браузер'
  const system = /Android/.test(agent)
    ? 'Android'
    : /iPhone|iPad/.test(agent)
      ? 'iOS'
      : /Mac OS X/.test(agent)
        ? 'macOS'
        : /Windows/.test(agent)
          ? 'Windows'
          : /Linux/.test(agent)
            ? 'Linux'
            : ''
  return system ? `${engine} на ${system}` : engine
}

export const ACCOUNT_KEY = ['account'] as const
export const CAPABILITIES_KEY = ['capabilities'] as const

export function useAccount() {
  return useQuery({
    queryKey: ACCOUNT_KEY,
    queryFn: () => api.me(),
    // Вход проверяется один раз за сеанс: спрашивать сервер на каждом экране
    // незачем, а после входа кэш обновляется явно.
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export function useCapabilities() {
  return useQuery({
    queryKey: CAPABILITIES_KEY,
    queryFn: () => api.capabilities(),
    staleTime: 10 * 60_000,
    retry: false,
  })
}

export function useSignOut() {
  const queries = useQueryClient()
  return useMutation({
    mutationFn: () => api.signOut(),
    onSuccess: () => {
      queries.setQueryData(ACCOUNT_KEY, null)
      // Лента и профиль принадлежат вошедшему: после выхода они не просто
      // устарели, их больше нет.
      void queries.invalidateQueries({ queryKey: ['discovery'] })
    },
  })
}
