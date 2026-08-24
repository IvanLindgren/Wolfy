import { useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'

import * as api from '../api/client'
import { ACCOUNT_KEY, deviceInfo } from './useAccount'
import { consumeYandexState, parseSocialReturn } from './socialFlow'
import page from '../widgets/Page.module.css'

export function AuthReturn() {
  const navigate = useNavigate()
  const queries = useQueryClient()
  const [message, setMessage] = useState('Завершаем вход…')
  const started = useRef(false)
  useEffect(() => {
    // React StrictMode повторяет эффекты в development. Completion code Яндекса
    // одноразовый, поэтому второй запрос превратил бы успешный вход в ошибку.
    if (started.current) return
    started.current = true

    const flow = parseSocialReturn(location.search)
    if (flow.provider === 'yandex' && !consumeYandexState(flow.state)) {
      setMessage('Этот вход через Яндекс не был начат в текущей вкладке. Попробуйте ещё раз.')
      return
    }
    if (flow.error) {
      setMessage(flow.error)
      return
    }

    const finish = async () => {
      if (flow.provider === 'yandex') {
        if (!flow.code) {
          setMessage('Яндекс не вернул код входа. Попробуйте войти ещё раз.')
          return
        }
        const outcome = await api.yandexComplete(flow.code, deviceInfo())
        if (outcome.kind !== 'signedIn') {
          setMessage(outcome.kind === 'refused'
            ? outcome.message
            : 'Не удалось завершить вход через Яндекс.')
          return
        }
        queries.setQueryData(ACCOUNT_KEY, outcome.account)
      } else {
        // Google GIS уже обменял ID token через /v1/auth/google и до перехода
        // сюда положил общую сессию в httpOnly cookie.
        const account = await api.me()
        if (!account) {
          setMessage('Сессия не появилась. Попробуйте войти ещё раз.')
          return
        }
        queries.setQueryData(ACCOUNT_KEY, account)
      }
      void navigate({ to: flow.next, replace: true })
    }

    void finish().catch(() => setMessage('Сервер входа сейчас недоступен.'))
  }, [navigate, queries])
  return <div className={page.page}><p className={page.notice} role="status">{message}</p></div>
}
