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
import { deviceInfo } from '../core/device'

export { deviceInfo }

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
