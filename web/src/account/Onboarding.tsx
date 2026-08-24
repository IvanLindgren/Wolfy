import { useEffect, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'

import { session, useSession } from '../core/session'
import type { ThemeName } from '../core/types'
import { addFile } from '../library/import'
import { THEMES } from '../app/theme'
import { Button } from '../widgets/Button'
import { Wolfy } from '../widgets/Wolfy'
import styles from '../screens.module.css'

const DEMO = `The Quiet Library\n\nAt dusk, the old library breathed like a sleeping animal. Clara opened a narrow book and found a pencilled note: Every unfamiliar word is a door. She smiled and began to read.`

export function Home() {
  const navigate = useNavigate()
  const ready = useSession((state) => state.ready)
  const seen = useSession((state) => state.settings.onboardingSeen)
  const [step, setStep] = useState(0)
  const [adding, setAdding] = useState(false)
  useEffect(() => { if (ready && seen) void navigate({ to: '/library', replace: true }) }, [navigate, ready, seen])
  if (!ready || seen) return null

  const finish = async (demo: boolean) => {
    setAdding(true)
    let bookId = ''
    if (demo) {
      const result = await addFile(new File([DEMO], 'The Quiet Library.txt', { type: 'text/plain' }))
      if (result.kind !== 'refused') bookId = result.book.id
      await session.markDemoAdded()
    }
    await session.seenOnboarding()
    void navigate(bookId ? { to: '/reader/$bookId', params: { bookId } } : { to: '/library' })
  }

  return <div className={styles.onboarding}>
    <section>
      <div className={styles.eyebrow}>Wolfy · первый запуск</div>
      <div className={styles.steps}>{[0, 1, 2].map((index) => <span key={index} className={styles.step} data-on={index <= step} />)}</div>
      {step === 0 && <><h1 className={styles.onboardingTitle}>Книга становится тихим уроком.</h1><p className={styles.lead}>Нажмите на любое английское слово — Wolfy сразу разберёт форму и грамматику. Перевод спокойно доедет следом.</p></>}
      {step === 1 && <><h1 className={styles.onboardingTitle}>Настройте бумагу под себя.</h1><p className={styles.lead}>Тема и кегль сохранятся в ядре и позже синхронизируются с другими устройствами.</p><div className={styles.choices}>{THEMES.map((theme) => <button className={styles.choice} data-active={useSession.getState().settings.theme === theme.name} key={theme.name} onClick={() => void session.setTheme(theme.name as ThemeName)}>{theme.title}</button>)}</div><input type="range" min="0.85" max="1.35" step="0.05" defaultValue={useSession.getState().settings.fontScale} onChange={(event) => void session.setFontScale(Number(event.target.value))} aria-label="Размер текста" /></>}
      {step === 2 && <><h1 className={styles.onboardingTitle}>Попробуйте на одной странице.</h1><p className={styles.lead}>Демонстрационная книга останется только в этом браузере. Аккаунт для чтения не нужен.</p></>}
      <div style={{ display: 'flex', gap: '.6rem', flexWrap: 'wrap' }}>{step < 2 ? <Button variant="primary" onClick={() => setStep((value) => value + 1)}>Продолжить</Button> : <Button variant="primary" disabled={adding} onClick={() => void finish(true)}>{adding ? 'Добавляем…' : 'Открыть демо‑книгу'}</Button>}<Button variant="quiet" disabled={adding} onClick={() => void finish(false)}>Пропустить</Button></div>
    </section>
    <aside>{step === 0 ? <div className={styles.demo}>She had been <span className={styles.token}>reading</span> since dawn, and the margins were filled with notes.</div> : step === 1 ? <div className={styles.demo}><strong>Газета, сепия, уголь и настоящий OLED.</strong><br />Одна и та же книга, без вспышек и скачков строки.</div> : <div style={{ display: 'grid', placeItems: 'center' }}><Wolfy mood="glad" size={180} /></div>}</aside>
  </div>
}
