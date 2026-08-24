import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'

import * as api from '../api/client'
import { Button } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import styles from '../screens.module.css'
import { ACCOUNT_KEY, deviceInfo, useAccount, useCapabilities, useSignOut } from './useAccount'

export function AccountScreen() {
  const account = useAccount()
  const capabilities = useCapabilities()
  const signOut = useSignOut()
  if (account.data) return <div className={page.page}><header className={page.head}><div><div className={page.kicker}>Общий аккаунт Читавука</div><h1 className={page.title}>Аккаунт</h1></div></header><div className={styles.accountCard}><span className={styles.avatar}>{(account.data.displayName || account.data.email)[0]?.toUpperCase()}</span><div><strong>{account.data.displayName || 'Читатель'}</strong><div className={page.muted}>{account.data.email}</div></div><Button variant="quiet" disabled={signOut.isPending} onClick={() => signOut.mutate()}>Выйти</Button></div><p className={styles.lead}>Книги, прогресс, колоды и настройки синхронизируются. Сами файлы книг остаются на каждом устройстве.</p></div>
  return <AuthForm capabilities={capabilities.data} />
}

function AuthForm({ capabilities }: { capabilities?: api.Capabilities }) {
  const queries = useQueryClient()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [message, setMessage] = useState('')
  const [waiting, setWaiting] = useState('')
  const [busy, setBusy] = useState(false)

  const accept = (outcome: api.AuthOutcome) => {
    if (outcome.kind === 'signedIn') { queries.setQueryData(ACCOUNT_KEY, outcome.account); setMessage('Готово. Аккаунт подключён.') }
    else if (outcome.kind === 'awaitingEmail' || outcome.kind === 'emailNotConfirmed') { setWaiting(outcome.email); setMessage(`Письмо отправлено на ${outcome.email}. Подтвердите почту и затем войдите.`) }
    else if (outcome.kind === 'offline') setMessage('Сети нет. Читать и тренироваться можно без аккаунта.')
    else setMessage(outcome.message)
  }
  const submit = async (event: React.FormEvent) => {
    event.preventDefault(); setBusy(true); setMessage('')
    accept(mode === 'login' ? await api.signIn(email, password, deviceInfo()) : await api.register(email, password, name, deviceInfo()))
    setBusy(false)
  }
  const social = async (provider: 'google' | 'yandex') => {
    setBusy(true)
    try { const returnUrl = `${location.origin}/auth/return?next=${encodeURIComponent('/account')}`; location.assign(await api.socialStart(provider, returnUrl, deviceInfo())) } catch (error) { setMessage(error instanceof Error ? error.message : 'Вход не начался.'); setBusy(false) }
  }
  return <div className={`${page.page} ${styles.hero}`}><section className={styles.panel}><div className={page.kicker}>Общий аккаунт Читавука</div><h1 className={page.title}>{mode === 'login' ? 'Войти и продолжить' : 'Создать аккаунт'}</h1><p className={styles.lead}>Без аккаунта работают чтение, разбор, перевод и тренировки. Вход нужен только для ленты и синхронизации.</p>{capabilities?.register && <div className={styles.tabs}><button className={styles.tab} data-active={mode === 'login'} onClick={() => setMode('login')}>Вход</button><button className={styles.tab} data-active={mode === 'register'} onClick={() => setMode('register')}>Регистрация</button></div>}
    <form className={styles.form} onSubmit={(event) => void submit(event)}>{mode === 'register' && <label className={page.field}><span className={page.label}>Имя</span><input className={page.input} value={name} onChange={(event) => setName(event.target.value)} autoComplete="name" required /></label>}<label className={page.field}><span className={page.label}>Почта</span><input className={page.input} type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="email" required /></label><label className={page.field}><span className={page.label}>Пароль</span><input className={page.input} type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={8} required /></label><Button wide variant="primary" type="submit" disabled={busy || capabilities?.signIn === false}>{busy ? 'Подождите…' : mode === 'login' ? 'Войти' : 'Создать аккаунт'}</Button></form>
    {waiting && capabilities?.resend && <Button wide variant="quiet" onClick={async () => setMessage(await api.resendVerification(waiting) ? `Новое письмо отправлено на ${waiting}.` : 'Письмо сейчас не отправляется.')}>Отправить письмо ещё раз</Button>}
    {(capabilities?.google || capabilities?.yandex) && <><div className={styles.divider}>или</div><div className={styles.social}>{capabilities.google && <Button disabled={busy} onClick={() => void social('google')}>Google</Button>}{capabilities.yandex && <Button disabled={busy} onClick={() => void social('yandex')}>Яндекс</Button>}</div></>}{message && <p className={styles.message} role="status">{message}</p>}</section></div>
}
