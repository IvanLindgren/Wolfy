import { useEffect, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'

import * as api from '../api/client'
import { ACCOUNT_KEY } from './useAccount'
import page from '../widgets/Page.module.css'

export function AuthReturn() {
  const navigate = useNavigate()
  const queries = useQueryClient()
  const [message, setMessage] = useState('Завершаем вход…')
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const error = params.get('error')
    if (error) { setMessage(error); return }
    void api.me().then((account) => {
      if (!account) { setMessage('Сессия не появилась. Попробуйте войти ещё раз.'); return }
      queries.setQueryData(ACCOUNT_KEY, account)
      const next = params.get('next')
      void navigate({ to: next === '/discovery' ? '/discovery' : '/account', replace: true })
    }).catch(() => setMessage('Сервер входа сейчас недоступен.'))
  }, [navigate, queries])
  return <div className={page.page}><p className={page.notice} role="status">{message}</p></div>
}
