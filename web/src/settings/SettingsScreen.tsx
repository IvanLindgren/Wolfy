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
import { deviceInfo, useAccount, useCapabilities, useSignOut } from '../account/useAccount'
import { syncNow, useSyncState } from '../sync/sync'
import { enableReviewNotifications, notificationPermission } from '../decks/notifications'
import { useDeckStatuses } from '../decks/useDeckStatus'
import { useCompanionMemory, type MemorySize } from '../companion/memory'
import { plural, readingFacts } from './facts'

/**
 * Версия веба приходит из package.json на сборке.
 *
 * Здесь стояла строка «Веб 0.1.0», набранная руками. С package.json она
 * совпадала по случайности и разошлась бы при первом же выпуске — молча, а
 * читают её именно тогда, когда что-то не работает и версия должна быть
 * правдой.
 */
declare const __WOLFY_WEB_VERSION__: string
const WEB_VERSION = typeof __WOLFY_WEB_VERSION__ === 'string' ? __WOLFY_WEB_VERSION__ : 'dev'

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
  const lexicon = useSession((state) => state.lexicon)
  const dictionary = useSession((state) => state.dictionary)
  const library = useSession((state) => state.library)
  const account = useAccount()
  const capabilities = useCapabilities()
  const signOut = useSignOut()
  const sync = useSyncState()
  const [mode, setMode] = useState<ReadingMode>(readingMode)
  const [measure, setMeasure] = useState(readerMeasure)
  const [font, setFont] = useState<ReaderFont>(readerFont)
  const [usage, setUsage] = useState<StorageUsage | null>(null)
  const [download, setDownload] = useState<{ loaded: number; total: number } | null>(null)
  const [message, setMessage] = useState('')
  const [notify, setNotify] = useState(notificationPermission)
  const decks = useDeckStatuses()
  const memory = useCompanionMemory()
  const [confirmMemoryClear, setConfirmMemoryClear] = useState(false)
  const [tone, setTone] = useState<AccentName>(accent)
  const [online, setOnline] = useState(navigator.onLine)
  // Установлено ли приложение. Читателю это объясняет, почему у него нет
  // адресной строки и почему книги открываются двойным щелчком из проводника.
  const standalone = typeof matchMedia === 'function' && matchMedia('(display-mode: standalone)').matches
  const darkPaper = onDarkPaper(settings.theme)
  // Считается на каждый показ настроек, а не по таймеру: экран открывают
  // редко, а «к повторению сегодня» стареет каждую минуту, и число, устаревшее
  // на полдня, хуже отсутствующего.
  const facts = readingFacts(library.books, library.cards, decks, settings, Date.now())
  useEffect(() => { void storageUsage().then(setUsage) }, [dictionary])
  useEffect(() => {
    const update = () => setOnline(navigator.onLine)
    addEventListener('online', update)
    addEventListener('offline', update)
    return () => { removeEventListener('online', update); removeEventListener('offline', update) }
  }, [])

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
    <Setting title="Память компаньона" hint={`${memory.cache.length} ответов · ${memory.books.length} книг · хранится только в этом браузере`}><div className={styles.choices}><label className={styles.switch}><input type="checkbox" checked={memory.settings.enabled} onChange={(event) => memory.setEnabled(event.target.checked)} /> Включена</label>{memory.settings.enabled && <><label className={styles.switch}><input type="checkbox" checked={memory.settings.shareWithAi} onChange={(event) => memory.setShareWithAi(event.target.checked)} /> Учитывать в новых ответах</label>{(['compact', 'balanced', 'deep'] as MemorySize[]).map((size) => <button key={size} className={styles.choice} data-active={memory.settings.size === size} onClick={() => memory.setSize(size)}>{size === 'compact' ? 'Короткая' : size === 'deep' ? 'Большая' : 'Обычная'}</button>)}{memory.cache.length + memory.books.length + memory.questions.length > 0 && (confirmMemoryClear ? <><Button variant="danger" onClick={() => { memory.clear(); setConfirmMemoryClear(false) }}>Стереть ответы и пересказы</Button><Button variant="quiet" onClick={() => setConfirmMemoryClear(false)}>Отмена</Button></> : <Button variant="quiet" onClick={() => setConfirmMemoryClear(true)}>Очистить память</Button>)}</>}</div></Setting>
    <Setting title="Компаньон" hint="Персонаж, которого вы создаёте и наряжаете. Необязательный"><a href="/companion">Открыть раздел →</a></Setting>
    <Setting title="Офлайн‑словарь" hint={dictionary ? 'Толкования и МФА доступны без сети' : 'Скачивается один раз и хранится в браузере'}><div>{dictionary ? <Button onClick={async () => { await forget(DICTIONARY_URL); useSession.getState().setDictionaryReady(false); setMessage('Офлайн‑словарь удалён.') }}>Удалить словарь</Button> : <Button variant="primary" disabled={!!download} onClick={() => void installDictionary()}>{download ? 'Загружаем…' : 'Установить'}</Button>}{download && <div className={styles.progress}><span style={{ width: download.total ? `${download.loaded / download.total * 100}%` : '30%' }} /></div>}</div></Setting>
    <Setting title="Хранилище" hint={usage ? `${formatBytes(usage.total)} занято · книги ${formatBytes(usage.books)}${usage.persisted ? ' · защищено от автоочистки' : ''}` : 'Считаем…'}><Button variant="danger" onClick={async () => { if (!confirm('Удалить все книги, колоды, настройки и офлайн‑ресурсы из этого браузера?')) return; await clearEverything(); await clearAssets(); location.reload() }}>Очистить данные</Button></Setting>
    <h2 className={styles.groupTitle}>Аккаунт</h2>
    {/*
      * Раньше здесь была одна строка с почтой. Из неё не следовало ни что даёт
      * аккаунт, ни что перестанет работать без него, — а решение «входить или
      * нет» читатель принимает именно по этому.
      */}
    <Setting title="Аккаунт" hint={account.data ? `${account.data.email}${account.data.displayName ? ` · ${account.data.displayName}` : ''}` : 'Вход не выполнен. Чтение, разбор слов и колоды работают и так — они целиком на этом устройстве'}>{account.data ? <Button variant="quiet" disabled={signOut.isPending} onClick={() => signOut.mutate()}>Выйти</Button> : <a href="/account">Войти для синхронизации →</a>}</Setting>
    <Setting title="Что даёт вход" hint="Библиотека и колоды на всех устройствах · перевод предложений из сети · Beta-подсказки и компаньон · файл книги восстанавливается из хранилища сервера"><span className={styles.settingHint}>{account.data ? 'Всё включено' : 'Сейчас недоступно'}</span></Setting>
    <Setting title="Это устройство" hint={`${deviceInfo().name} · ${deviceInfo().platform}${account.data ? ' · сессия названа так же в списке устройств аккаунта' : ''}`}><span className={styles.settingHint}>{standalone ? 'Установлено как приложение' : 'Вкладка браузера'}</span></Setting>
    {account.data && <Setting title="Синхронизация" hint={sync.error ?? (sync.lastSuccess ? `Последний обмен ${new Date(sync.lastSuccess).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })} · ${sync.pending > 0 ? `${plural(sync.pending, 'запись ждёт', 'записи ждут', 'записей ждут')} отправки` : 'всё отправлено'}` : 'Ещё не запускалась')}><Button disabled={sync.running} onClick={() => void syncNow()}>{sync.running ? 'Синхронизируем…' : 'Синхронизировать'}</Button></Setting>}

    <h2 className={styles.groupTitle}>Чтение в числах</h2>
    <section className={styles.setting} style={{ gridTemplateColumns: '1fr' }}>
      <div>
        <div className={styles.settingTitle}>Сколько уже прочитано</div>
        <div className={styles.settingHint}>Колоды считает ядро — тем же счётом, что горит отметкой на разделе «Карточки». Занятия считаются по этому браузеру: обмен тренировкой между устройствами в вебе пока не включён</div>
        <div className={styles.facts}>
          <Fact value={facts.books} label={`начато ${facts.started} · дочитано ${facts.finished}`} caption="книг в библиотеке" />
          <Fact value={facts.cards} label={`${facts.learned} выучено`} caption="карточек в колодах" />
          <Fact value={facts.due} label={facts.due > 0 ? 'ждут прямо сейчас' : 'на сегодня всё'} caption="к повторению" />
          <Fact value={facts.addedThisWeek} label="за последние семь дней" caption="новых слов и фраз" />
          <Fact value={facts.streakDays} label={`лучшая — ${facts.bestStreak}`} caption="дней подряд" />
          <Fact value={facts.accuracy === null ? '—' : `${facts.accuracy}%`} label={facts.answers > 0 ? `${plural(facts.answers, 'ответ', 'ответа', 'ответов')} всего` : 'ещё не отвечали'} caption="верных ответов" />
        </div>
        {facts.withoutFile > 0 && <p className={styles.factsNote}>{plural(facts.withoutFile, 'книга известна', 'книги известны', 'книг известны')} по синхронизации, но файла в этом браузере нет. Откройте — и Wolfy предложит восстановить.</p>}
      </div>
    </section>

    <h2 className={styles.groupTitle}>Сервис</h2>
    <Setting title="Версия" hint={`Веб ${WEB_VERSION} · ядро ${version || 'запускается'}`}><span className={styles.settingHint}>{online ? 'Сеть есть' : 'Сети нет'}</span></Setting>
    {/*
      * Что умеет сервер — его собственный ответ на /healthz, а не наше
      * обещание. Тем же ответом экран входа прячет «Создать аккаунт», когда
      * регистрация не настроена: кнопка, которая всегда отвечает ошибкой, хуже
      * её отсутствия. В настройках это же знание объясняет, почему у соседа
      * работает то, чего нет здесь.
      */}
    <Setting title="Что умеет сервер" hint={capabilities.data ? `${service(capabilities.data.translate, 'перевод из сети')} · ${service(capabilities.data.ocr, 'страница по фото')} · ${service(capabilities.data.dictionary, 'офлайн-словарь')} · ${service(capabilities.data.register, 'регистрация')}` : 'Спрашиваем сервер…'}><span className={styles.settingHint}>{capabilities.data?.status === 'ok' ? 'Отвечает' : 'Недоступен'}</span></Setting>
    <Setting title="Как это устроено" hint="Библиотека, разбор слов и расписание повторений считает на этом устройстве то же Rust-ядро, что в приложениях для Windows, Linux и Android. Сеть нужна только переводу из сети, Beta-подсказкам и обмену между устройствами"><a href="https://wolfy.citavuk.ru" target="_blank" rel="noreferrer">Приложения для компьютера →</a></Setting>
    <Setting title="Диагностика" hint={`Разбор слов ${lexicon ? 'готов' : 'не поднялся'} · словарь ${dictionary ? 'установлен' : 'не установлен'} · хранилище ${usage ? formatBytes(usage.total) : 'считается'}`}><Button variant="quiet" onClick={() => void storageUsage().then(setUsage)}>Обновить сведения</Button></Setting>
  </div>{message && <p className={page.notice} role="status" style={{ marginTop: '1rem' }}>{message}</p>}</div>
}

/**
 * Одно число сводки.
 *
 * Значение крупно, подпись под ним мелко: читатель ищет глазами число, а
 * читает подпись только у того числа, на котором остановился.
 */
/** «Есть» или «нет» рядом с названием возможности — без «функция недоступна». */
function service(available: boolean, title: string): string {
  return `${title}: ${available ? 'есть' : 'нет'}`
}

function Fact({ value, caption, label }: { value: number | string; caption: string; label: string }) {
  return <div className={styles.fact}><div className={styles.fact__value}>{value}</div><div className={styles.fact__label}>{caption}<br />{label}</div></div>
}

function Setting({ title, hint, children }: { title: string; hint: string; children: React.ReactNode }) {
  return <section className={styles.setting}><div><div className={styles.settingTitle}>{title}</div><div className={styles.settingHint}>{hint}</div></div><div>{children}</div></section>
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} КБ`
  return `${(bytes / 1024 ** 2).toFixed(1)} МБ`
}
