import { useEffect, useState } from 'react'

import { ACCENTS, accent, applyAccent, onDarkPaper, THEMES, type AccentName } from '../app/theme'
import * as bridge from '../core/bridge'
import { session, useSession } from '../core/session'
import type { FocusMode, IntensityName, ThemeName } from '../core/types'
import { readerFont, readerMeasure, readingMode, setReaderFont, setReaderMeasure, setReadingMode, type ReaderFont, type ReadingMode } from '../reader/preferences'
import { clearAssets, DICTIONARY_URL, forget } from '../storage/assets'
import { clearEverything, storageUsage, type StorageUsage } from '../storage/opfs'
import { Button } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import styles from '../screens.module.css'
import { useAccount, useSignOut } from '../account/useAccount'
import { syncNow, useSyncState } from '../sync/sync'
import { enableReviewNotifications, notificationPermission } from '../decks/notifications'

const INTENSITIES: { name: IntensityName; title: string }[] = [
  { name: 'Gentle', title: 'Легко' }, { name: 'Normal', title: 'Обычно' },
  { name: 'Strong', title: 'Плотно' }, { name: 'Extreme', title: 'Экстремально' },
]

/*
 * Режимы окна чтения.
 *
 * Предложение — единица смысла, абзац — единица мысли. Что из них подходит,
 * зависит от человека и от книги, поэтому выбор оставлен читателю, а не
 * назначен нами.
 */
const FOCUS_MODES: { mode: FocusMode; title: string }[] = [
  { mode: 'off', title: 'Без окна' },
  { mode: 'sentence', title: 'Предложение' },
  { mode: 'paragraph', title: 'Абзац' },
]

/*
 * Темп ведущей строки.
 *
 * Числа не круглые и не произвольные: 160 — спокойное чтение вслух, 220 —
 * обычное чтение про себя на неродном языке, 300 — быстро, но ещё с
 * пониманием. Ползунок здесь был бы хуже: он предлагает подбирать число,
 * которого читатель про себя не знает.
 */
const PACES: { wpm: number; title: string }[] = [
  { wpm: 0, title: 'Выключена' },
  { wpm: 160, title: 'Спокойно' },
  { wpm: 220, title: 'Обычно' },
  { wpm: 300, title: 'Быстро' },
]

/*
 * Размер отрезка. В словах, а не в минутах: минуты пришлось бы переводить в
 * слова по скорости, которой мы не знаем, и обещание «пять минут» оказалось
 * бы неверным ровно для того, кто читает медленнее.
 */
const SEGMENTS: { words: number; title: string }[] = [
  { words: 0, title: 'Без отрезков' },
  { words: 150, title: 'Короткий' },
  { words: 400, title: 'Средний' },
  { words: 900, title: 'Длинный' },
]

export function SettingsScreen() {
  const settings = useSession((state) => state.settings)
  const version = useSession((state) => state.version)
  const dictionary = useSession((state) => state.dictionary)
  const account = useAccount()
  const signOut = useSignOut()
  const sync = useSyncState()
  const [mode, setMode] = useState<ReadingMode>(readingMode)
  const [measure, setMeasure] = useState(readerMeasure)
  const [font, setFont] = useState<ReaderFont>(readerFont)
  const [usage, setUsage] = useState<StorageUsage | null>(null)
  const [download, setDownload] = useState<{ loaded: number; total: number } | null>(null)
  const [message, setMessage] = useState('')
  const [notify, setNotify] = useState(notificationPermission)
  const [tone, setTone] = useState<AccentName>(accent)
  const darkPaper = onDarkPaper(settings.theme)
  useEffect(() => { void storageUsage().then(setUsage) }, [dictionary])

  const installDictionary = async () => {
    setMessage('')
    try {
      const ok = await bridge.loadDictionary((loaded, total) => setDownload({ loaded, total }))
      useSession.getState().setDictionaryReady(ok)
      setMessage(ok ? 'Офлайн‑словарь установлен.' : 'Словарь не удалось установить.')
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Словарь не загрузился.') }
    finally { setDownload(null) }
  }

  return <div className={page.page}><header className={page.head}><div><div className={page.kicker}>Личная типографика</div><h1 className={page.title}>Настройки</h1></div></header><div className={styles.settings}>
    <Setting title="Тема" hint="Синхронизируется между устройствами"><div className={styles.choices}>{THEMES.map((theme) => <button key={theme.name} className={styles.choice} data-active={settings.theme === theme.name} onClick={() => void session.setTheme(theme.name as ThemeName)}>{theme.title}</button>)}</div></Setting>
    <Setting title="Цвет акцента" hint="Настройка только этого устройства: в настройках ядра поля для неё нет"><div className={styles.swatches}>{ACCENTS.map((item) => <button key={item.name} type="button" className={styles.swatch} data-active={tone === item.name} style={{ ['--swatch-tone' as string]: darkPaper ? item.dark : item.light }} title={item.title} aria-label={`Акцент «${item.title}»`} aria-pressed={tone === item.name} onClick={() => { setTone(item.name); applyAccent(item.name) }}><span /></button>)}</div></Setting>
    <Setting title="Кегль" hint={`${Math.round(settings.fontScale * 100)}%`}><input type="range" min="0.8" max="1.45" step="0.05" value={settings.fontScale} onChange={(event) => void session.setFontScale(Number(event.target.value))} aria-label="Кегль" /></Setting>
    <Setting title="Межстрочный интервал" hint={`${Math.round(settings.lineScale * 100)}%`}><input type="range" min="0.85" max="1.35" step="0.05" value={settings.lineScale} onChange={(event) => void session.setLineScale(Number(event.target.value))} aria-label="Межстрочный интервал" /></Setting>
    <Setting title="Режим чтения" hint="Настройка только этого устройства"><div className={styles.choices}>{(['pages', 'scroll'] as ReadingMode[]).map((value) => <button key={value} className={styles.choice} data-active={mode === value} onClick={() => { setMode(value); setReadingMode(value) }}>{value === 'pages' ? 'Страницы' : 'Лента'}</button>)}</div></Setting>
    <Setting title="Шрифт книги" hint="Можно менять и прямо над страницей"><div className={styles.choices}>{(['serif', 'sans'] as ReaderFont[]).map((value) => <button key={value} className={styles.choice} data-active={font === value} onClick={() => { setFont(value); setReaderFont(value) }}>{value === 'serif' ? 'Книжный' : 'Простой'}</button>)}</div></Setting>
    <Setting title="Ширина колонки" hint={`${measure} знаков`}><input type="range" min="54" max="80" step="2" value={measure} onChange={(event) => { const value = Number(event.target.value); setMeasure(value); setReaderMeasure(value) }} aria-label="Ширина колонки" /></Setting>
    <Setting title="Полужирная основа" hint="Взгляд цепляется за начало слова, а окончание достраивает сам"><label className={styles.switch}><input type="checkbox" checked={settings.emphasizeStems} onChange={(event) => void session.setEmphasizeStems(event.target.checked)} /> Выделять основу</label></Setting>
    <Setting title="Окно чтения" hint="Притушить всё, кроме того места, где вы сейчас. Окно ведёт указатель — как бумажную линейку водят пальцем"><div className={styles.choices}>{FOCUS_MODES.map((item) => <button key={item.mode} className={styles.choice} data-active={settings.focusMode === item.mode} onClick={() => void session.setFocusMode(item.mode)}>{item.title}</button>)}</div></Setting>
    <Setting title="Ведущая строка" hint={settings.pacerWpm > 0 ? `${settings.pacerWpm} слов в минуту · включается кнопкой в читалке` : 'Выключена: окно ведёте вы сами'}><div className={styles.choices}>{PACES.map((item) => <button key={item.wpm} className={styles.choice} data-active={settings.pacerWpm === item.wpm} onClick={() => void session.setPacer(item.wpm)}>{item.title}</button>)}</div></Setting>
    <Setting title="Отрезок чтения" hint={settings.segmentWords > 0 ? `Подход примерно в ${settings.segmentWords} слов, конец подтягивается к точке` : 'Выключен: у главы остаётся только её собственный конец'}><div className={styles.choices}>{SEGMENTS.map((item) => <button key={item.words} className={styles.choice} data-active={settings.segmentWords === item.words} onClick={() => void session.setSegmentWords(item.words)}>{item.title}</button>)}</div></Setting>
    <Setting title="Интенсивность повторений" hint="Новые сроки рассчитывает ядро"><div className={styles.choices}>{INTENSITIES.map((item) => <button key={item.name} className={styles.choice} data-active={settings.intensity === item.name} onClick={() => void session.setIntensity(item.name)}>{item.title}</button>)}</div></Setting>
    <Setting title="Напоминания" hint={notify === 'granted' ? 'Браузер покажет уведомление по личному графику забывания' : notify === 'denied' ? 'Уведомления запрещены в настройках браузера; Wolfy напомнит внутри приложения' : 'Разрешение спрашивается только по этой кнопке'}><Button disabled={notify === 'granted' || notify === 'unavailable'} onClick={async () => setNotify(await enableReviewNotifications())}>{notify === 'granted' ? 'Разрешены' : notify === 'unavailable' ? 'Не поддерживаются' : 'Разрешить'}</Button></Setting>
    <Setting title="Меньше движения" hint="Отключает перелёты, пружины и плавную прокрутку"><label className={styles.switch}><input type="checkbox" checked={settings.reduceMotion} onChange={(event) => void session.setReduceMotion(event.target.checked)} /> Без анимаций</label></Setting>
    <Setting title="Звуки компаньона" hint="Тихие сигналы при появлении и готовом ответе"><label className={styles.switch}><input type="checkbox" checked={settings.companionSounds} onChange={(event) => void session.setCompanionSounds(event.target.checked)} /> Включены</label></Setting>
    <Setting title="Компаньон" hint="Персонаж, которого вы создаёте и наряжаете. Необязательный"><a href="/companion">Открыть раздел →</a></Setting>
    <Setting title="Офлайн‑словарь" hint={dictionary ? 'Толкования и МФА доступны без сети' : 'Скачивается один раз и хранится в браузере'}><div>{dictionary ? <Button onClick={async () => { await forget(DICTIONARY_URL); useSession.getState().setDictionaryReady(false); setMessage('Офлайн‑словарь удалён.') }}>Удалить словарь</Button> : <Button variant="primary" disabled={!!download} onClick={() => void installDictionary()}>{download ? 'Загружаем…' : 'Установить'}</Button>}{download && <div className={styles.progress}><span style={{ width: download.total ? `${download.loaded / download.total * 100}%` : '30%' }} /></div>}</div></Setting>
    <Setting title="Хранилище" hint={usage ? `${formatBytes(usage.total)} занято · книги ${formatBytes(usage.books)}${usage.persisted ? ' · защищено от автоочистки' : ''}` : 'Считаем…'}><Button variant="danger" onClick={async () => { if (!confirm('Удалить все книги, колоды, настройки и офлайн‑ресурсы из этого браузера?')) return; await clearEverything(); await clearAssets(); location.reload() }}>Очистить данные</Button></Setting>
    <Setting title="Аккаунт" hint={account.data?.email ?? 'Без аккаунта — чтение продолжает работать'}>{account.data ? <Button variant="quiet" disabled={signOut.isPending} onClick={() => signOut.mutate()}>Выйти</Button> : <a href="/account">Войти для синхронизации →</a>}</Setting>
    {account.data && <Setting title="Синхронизация" hint={sync.error ?? (sync.lastSuccess ? `Последний обмен ${new Date(sync.lastSuccess).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })} · ожидает ${sync.pending}` : 'Ещё не запускалась')}><Button disabled={sync.running} onClick={() => void syncNow()}>{sync.running ? 'Синхронизируем…' : 'Синхронизировать'}</Button></Setting>}
    <Setting title="Диагностика" hint={`Веб 0.1.0 · ядро ${version || 'запускается'} · словарь ${dictionary ? 'готов' : 'не установлен'}`}><Button variant="quiet" onClick={() => void storageUsage().then(setUsage)}>Обновить сведения</Button></Setting>
  </div>{message && <p className={page.notice} role="status" style={{ marginTop: '1rem' }}>{message}</p>}</div>
}

function Setting({ title, hint, children }: { title: string; hint: string; children: React.ReactNode }) {
  return <section className={styles.setting}><div><div className={styles.settingTitle}>{title}</div><div className={styles.settingHint}>{hint}</div></div><div>{children}</div></section>
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} КБ`
  return `${(bytes / 1024 ** 2).toFixed(1)} МБ`
}
