import { useEffect, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'

import * as api from '../api/client'
import { addDownloaded } from '../library/import'
import { Button } from '../widgets/Button'
import { WolfyCompanion } from '../widgets/Wolfy'
import page from '../widgets/Page.module.css'
import styles from '../screens.module.css'
import { useAccount } from '../account/useAccount'

const GENRES = ['Приключения', 'Фантастика', 'Детектив', 'Роман', 'История', 'Юмор', 'Готика', 'Поэзия']

export function DiscoveryScreen() {
  const account = useAccount()
  const [profile, setProfile] = useState<api.DiscoveryProfile | null>(null)
  const [items, setItems] = useState<api.DiscoveryItem[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = async () => {
    setBusy(true); setMessage('')
    try {
      const nextProfile = await api.discoveryProfile()
      setProfile(nextProfile)
      if (nextProfile.onboardingComplete) setItems((await api.discoveryFeed()).items)
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Лента сейчас недоступна.') }
    setBusy(false)
  }
  useEffect(() => { if (account.data) void load() }, [account.data])

  if (!account.data) return <div className={page.page}><WolfyCompanion mood="kind" title="Лента открывается после входа"><p className={page.muted}>Войдите, чтобы сохранить <strong>уровень и любимые жанры</strong>.</p><Link to="/account"><Button variant="primary">Войти</Button></Link></WolfyCompanion></div>
  if (profile && !profile.onboardingComplete) return <ProfileForm initial={profile} onSaved={(saved) => { setProfile(saved); void load() }} />

  return <div className={page.page}><header className={page.head}><div><div className={page.kicker}>Project Gutenberg</div><h1 className={page.title}>Открытые издания</h1><p className={page.subtitle}>Книги в общественном достоянии, подобранные по уровню и жанрам.</p></div><div className={page.headActions}><Button small disabled={busy} onClick={() => void load()}>Обновить</Button></div></header>
    {message && <p className={page.notice}>{message}</p>}
    <div className={styles.feed}>{items.map((item) => <DiscoveryCard key={item.id} item={item} onChanged={(changed) => setItems((all) => all.map((entry) => entry.id === changed.id ? changed : entry))} onMessage={setMessage} />)}</div>
    {!busy && !items.length && <WolfyCompanion mood="calm" title="В ленте пока тихо"><p className={page.muted}>Попробуйте обновить её немного позже.</p></WolfyCompanion>}
  </div>
}

function ProfileForm({ initial, onSaved }: { initial: api.DiscoveryProfile; onSaved: (profile: api.DiscoveryProfile) => void }) {
  const [level, setLevel] = useState(initial.englishLevel || 'B2')
  const [genres, setGenres] = useState(new Set(initial.genres))
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const save = async () => {
    if (!genres.size) { setMessage('Выберите хотя бы один жанр.'); return }
    setBusy(true)
    try { onSaved(await api.saveDiscoveryProfile({ englishLevel: level, genres: [...genres], onboardingComplete: true })) } catch (error) { setMessage(error instanceof Error ? error.message : 'Профиль не сохранился.') }
    setBusy(false)
  }
  return <div className={page.page}><header className={page.head}><div><div className={page.kicker}>Настройка ленты</div><h1 className={page.title}>Что вам подходит</h1><p className={page.subtitle}>Уровень и жанры сохранятся в аккаунте и появятся на других устройствах.</p></div></header><section className={page.section}><div className={page.sectionHead}><h2 className={page.sectionTitle}>Уровень английского</h2><span className={page.sectionRule} /></div><div className={styles.choices} style={{ justifyContent: 'flex-start' }}>{['A1','A2','B1','B2','C1','C2'].map((value) => <button key={value} className={styles.choice} data-active={level === value} onClick={() => setLevel(value)}>{value}</button>)}</div></section><section className={page.section}><div className={page.sectionHead}><h2 className={page.sectionTitle}>Жанры</h2><span className={page.sectionRule} /></div><div className={styles.choices} style={{ justifyContent: 'flex-start' }}>{GENRES.map((value) => <button key={value} className={styles.choice} data-active={genres.has(value)} onClick={() => setGenres((current) => { const next = new Set(current); next.has(value) ? next.delete(value) : next.add(value); return next })}>{value}</button>)}</div></section>{message && <p className={page.notice}>{message}</p>}<Button variant="primary" disabled={busy} onClick={() => void save()}>{busy ? 'Сохраняем…' : 'Собрать ленту'}</Button></div>
}

function DiscoveryCard({ item, onChanged, onMessage }: { item: api.DiscoveryItem; onChanged: (item: api.DiscoveryItem) => void; onMessage: (message: string) => void }) {
  const navigate = useNavigate()
  const [adding, setAdding] = useState(false)
  const like = async () => { onChanged({ ...item, liked: true }); try { await api.likeDiscoveryItem(item.id) } catch (error) { onChanged(item); onMessage(error instanceof Error ? error.message : 'Отметка не сохранилась.') } }
  const add = async () => {
    setAdding(true)
    try {
      const downloaded = await api.downloadDiscoveryItem(item.id)
      const result = await addDownloaded(downloaded.bytes, downloaded.fileName, downloaded.title || item.title, downloaded.author || item.author, downloaded.sourceKey)
      if (result.kind === 'refused') onMessage(result.message)
      else { onChanged({ ...item, liked: true, added: true }); void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } }) }
    } catch (error) { onMessage(error instanceof Error ? error.message : 'Книга сейчас не загружается.') }
    setAdding(false)
  }
  return <article className={styles.feedCard}>{item.coverUrl ? <img className={styles.cover} src={item.coverUrl} alt="" loading="lazy" decoding="async" /> : <div className={styles.coverFallback}>{item.title}</div>}<div style={{ display: 'flex', flexDirection: 'column' }}><div className={styles.meta}>{item.contentType || 'Книга'} · {item.level}</div><h2 className={styles.feedTitle}>{item.title}</h2><div className={styles.meta}>{item.author}</div><div className={styles.chips}>{item.genres.slice(0, 5).map((genre) => <span className={styles.chip} key={genre}>{genre}</span>)}</div><p className={styles.summary}>{item.summary}</p><div className={styles.feedActions}><Button small disabled={item.liked} onClick={() => void like()}>{item.liked ? 'Понравилось' : 'Нравится'}</Button><Button small variant="primary" disabled={item.added || adding} onClick={() => void add()}>{item.added ? 'В библиотеке' : adding ? 'Загружаем…' : 'Добавить книгу'}</Button></div></div></article>
}
