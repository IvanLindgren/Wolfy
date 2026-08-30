/**
 * Раздел «Компаньон»: посадочная, мастер создания и созданный компаньон.
 *
 * Компаньон всегда необязателен: с посадочной можно уйти одной ссылкой.
 * Черновик мастера переживает перезагрузку страницы, но в синхронизацию не
 * едет, пока не нажато «Сохранить».
 */
import { useEffect, useState } from 'react'

import * as api from '../api/client'
import { Button } from '../widgets/Button'
import page from '../widgets/Page.module.css'
import {
  DEFAULT_APPEARANCE, DEFAULT_PERSONALITY, MBTI_CODES, PALETTES, PACK_ID,
  POLAR_LABELS, SLOT_TITLES, appearanceAsset,
  appearanceWithAsset, assetLabel, characterLine, validatePack, validateProfile,
  type CompanionAppearance, type CompanionPhrasePack, type CompanionProfile,
} from './model'
import { CompanionFigure } from './figure'
import { blankDraft, useCompanion } from './store'

const STEP_TITLES = ['', 'Имя и обращение', 'Внешность', 'Одежда и аксессуары', 'Характер', 'Портрет словами', 'Проверка']

export function CompanionScreen() {
  const store = useCompanion()
  const profile = store.draft ?? store.profile
  const [step, setStep] = useState(store.profile ? 7 : 0)

  if (step === 0 && !profile) {
    return (
      <Landing
        onCreate={() => { store.saveDraft(blankDraft()); setStep(1) }}
        onContinue={() => { history.back() }}
      />
    )
  }
  if (!profile) return <Landing onCreate={() => { store.saveDraft(blankDraft()); setStep(1) }} onContinue={() => history.back()} />

  const issues = validateProfile(profile)
  const saved = store.profile && store.profile.id === profile.id

  const update = (transform: (draft: CompanionProfile) => CompanionProfile) => {
    store.saveDraft(transform(profile))
  }

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Компаньон</div>
          <h1 className={page.title}>{step >= 1 && step <= 6 ? STEP_TITLES[step] : profile.name || 'Компаньон'}</h1>
        </div>
      </header>

      {step >= 1 && step <= 6 && (
        <div style={{ display: 'grid', gap: '1.5rem', justifyItems: 'center' }}>
          <div style={{ background: 'var(--paper, #F7F2E9)', padding: '1rem', borderRadius: '1rem', border: '1px solid rgba(0,0,0,.15)' }}>
            <CompanionFigure appearance={profile.appearance} size={160} />
          </div>
          {step === 1 && (
            <div style={{ display: 'grid', gap: '.75rem', width: 'min(28rem, 100%)' }}>
              <input value={profile.name} placeholder="Как его зовут?" onChange={(event) => update((draft) => ({ ...draft, name: [...event.target.value].slice(0, 40).join('') }))} aria-label="Имя" />
              <input value={profile.pronouns ?? ''} placeholder="Обращение, необязательно" onChange={(event) => update((draft) => { const value = [...event.target.value].slice(0, 80).join(''); return { ...draft, pronouns: value || null } })} aria-label="Обращение" />
              <strong>Какой образ собрать?</strong>
              <div style={{ display: 'flex', gap: '.5rem', flexWrap: 'wrap' }}>
                {PRESENTATION_OPTIONS.map(([value, label]) => (
                  <button
                    key={value}
                    type="button"
                    aria-pressed={profile.presentation === value}
                    data-active={profile.presentation === value}
                    onClick={() => update((draft) => ({ ...draft, presentation: value, appearance: presentationAppearance(draft.appearance, value) }))}
                  >{label}</button>
                ))}
              </div>
              <small>Это только стартовый вид. Дальше любую причёску, одежду и обращение можно выбрать независимо.</small>
              {issues.includes('name') && profile.name.length > 0 && <small role="status">Имя обязательно, до 40 знаков.</small>}
            </div>
          )}
          {(step === 2 || step === 3) && (
            <div style={{ display: 'grid', gap: '1rem', width: 'min(44rem, 100%)' }}>
              {(step === 2 ? ['hair', 'brows', 'eyes', 'nose', 'mouth', 'beard'] : ['body', 'accessoryFront']).map((slot) => (
                <AssetPicker key={slot} appearance={profile.appearance} slot={slot} onPick={(assetId) => update((draft) => ({ ...draft, appearance: appearanceWithAsset(draft.appearance, slot, assetId) }))} />
              ))}
              {step === 2 && (
                <>
                  <PaletteRow title="Кожа" options={PALETTES.skin} value={profile.appearance.skin} onPick={(name) => update((draft) => ({ ...draft, appearance: { ...draft.appearance, skin: name } }))} />
                  <PaletteRow title="Волосы" options={PALETTES.hairColor} value={profile.appearance.hairColor} onPick={(name) => update((draft) => ({ ...draft, appearance: { ...draft.appearance, hairColor: name } }))} />
                </>
              )}
              {step === 3 && (
                <>
                  <PaletteRow title="Одежда" options={PALETTES.outfitColor} value={profile.appearance.outfitColor} onPick={(name) => update((draft) => ({ ...draft, appearance: { ...draft.appearance, outfitColor: name } }))} />
                  <PaletteRow title="Акцент" options={PALETTES.accentColor} value={profile.appearance.accentColor} onPick={(name) => update((draft) => ({ ...draft, appearance: { ...draft.appearance, accentColor: name } }))} />
                </>
              )}
            </div>
          )}
          {step === 4 && (
            <div style={{ display: 'grid', gap: '1rem', width: 'min(34rem, 100%)' }}>
              {POLAR_LABELS.map(([low, high, key]) => (
                <label key={key} style={{ display: 'grid', gap: '.25rem' }}>
                  <span style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <small>{low}</small>
                    <small>{high}</small>
                  </span>
                  <input
                    type="range" min={0} max={100} step={1}
                    value={profile.personality[key] ?? 50}
                    aria-valuemin={0} aria-valuemax={100} aria-valuenow={profile.personality[key] ?? 50}
                    aria-valuetext={`${low} или ${high}`}
                    onChange={(event) => update((draft) => ({ ...draft, personality: { ...draft.personality, [key]: Number(event.target.value) } }))}
                  />
                </label>
              ))}
            </div>
          )}
          {step === 5 && (
            <div style={{ display: 'grid', gap: '1rem', width: 'min(34rem, 100%)' }}>
              <small>MBTI, необязательно. Подсказка стиля, а не диагноз.</small>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.4rem' }}>
                {MBTI_CODES.map((code) => (
                  <button key={code} type="button" data-active={profile.mbti === code} onClick={() => update((draft) => ({ ...draft, mbti: draft.mbti === code ? null : code }))}>{code}</button>
                ))}
              </div>
              <textarea
                value={profile.description}
                placeholder="Описание, необязательно: как он говорит?"
                rows={4}
                onChange={(event) => update((draft) => ({ ...draft, description: [...event.target.value].slice(0, 1200).join('') }))}
                aria-label="Описание"
              />
              <small>{[...profile.description].length} из 1200</small>
            </div>
          )}
          {step === 6 && (
            <div style={{ display: 'grid', gap: '.75rem', justifyItems: 'center' }}>
              <strong>{profile.name}</strong>
              <small>{characterLine(profile)}</small>
              <Button variant="primary" disabled={issues.length > 0} onClick={() => { store.save(profile); setStep(7) }}>Сохранить</Button>
              <small>После сохранения один запрос создаст набор из ста коротких реплик. Обычное чтение запросов не тратит.</small>
            </div>
          )}
          <div style={{ display: 'flex', gap: '1rem', width: 'min(34rem, 100%)', justifyContent: 'space-between' }}>
            <Button variant="quiet" onClick={() => (step > 1 ? setStep(step - 1) : setStep(store.profile ? 7 : 0))}>Назад</Button>
            {step < 6 && (
              <Button variant="primary" disabled={step === 1 && (issues.includes('name') || issues.includes('presentation'))} onClick={() => setStep(step + 1)}>Дальше</Button>
            )}
          </div>
        </div>
      )}

      {step === 7 && profile && (
        <ProfileView profile={profile} onEdit={(target) => { setStep(target) }} />
      )}
      {void saved}
    </div>
  )
}

function Landing({ onCreate, onContinue }: { onCreate: () => void; onContinue: () => void }) {
  return (
    <div style={{ display: 'grid', gap: '1.25rem', justifyItems: 'center', textAlign: 'center' }}>
      <h2>Читатель, рядом с которым кто-то есть</h2>
      <div style={{ background: 'var(--paper, #F7F2E9)', padding: '1rem', borderRadius: '1rem', border: '1px solid rgba(0,0,0,.15)' }}>
        <CompanionFigure appearance={{ ...DEFAULT_APPEARANCE, hair: 'hair.01', brows: 'brows.02', eyes: 'eyes.17', mouth: 'mouth.01', body: 'body.17' }} size={160} />
      </div>
      <p style={{ maxWidth: '34rem' }}>
        Компаньон это персонаж, которого вы создаёте и наряжаете. Он сидит рядом
        со страницей, редко говорит заготовленными фразами и поддерживает, а не
        отвлекает. Чтение работает и без него: это необязательный раздел.
      </p>
      <Button variant="primary" onClick={onCreate}>Создать компаньона</Button>
      <button type="button" onClick={onContinue} style={{ background: 'none', border: 'none', cursor: 'pointer' }}>Продолжить без компаньона</button>
    </div>
  )
}

function ProfileView({ profile, onEdit }: { profile: CompanionProfile; onEdit: (step: number) => void }) {
  const store = useCompanion()
  const [message, setMessage] = useState('')
  const [confirming, setConfirming] = useState(false)

  const generate = async () => {
    setMessage('')
    store.markPackLoading()
    try {
      const data = await api.companionPack({
        id: profile.id,
        name: profile.name,
        locale: profile.locale,
        personality: profile.personality,
        mbti: profile.mbti ?? null,
        description: profile.description,
      })
      const pack: CompanionPhrasePack = {
        schemaVersion: data.pack.schemaVersion,
        profileHash: data.pack.profileHash,
        locale: data.pack.locale,
        generatedAt: Date.now(),
        source: data.cached ? 'cache' : 'generated',
        phrases: data.pack.phrases,
      }
      if (validatePack(pack).length > 0) throw new Error('invalid companion phrase pack')
      store.attachPack(pack)
      setMessage(data.cached ? 'Готовый набор для этого характера уже был сохранён.' : 'Набор реплик создан.')
      store.markPackReady()
    } catch {
      setMessage('Не удалось собрать набор реплик. Приложение продолжит работать с базовым набором.')
      store.markPackFailed('Не удалось собрать набор реплик. Приложение продолжит работать с базовым набором.', true)
    }
  }

  return (
    <div style={{ display: 'grid', gap: '1.25rem', justifyItems: 'center' }}>
      <div style={{ background: 'var(--paper, #F7F2E9)', padding: '1rem', borderRadius: '1rem', border: '1px solid rgba(0,0,0,.15)' }}>
        <CompanionFigure appearance={profile.appearance} size={180} />
      </div>
      <div style={{ textAlign: 'center' }}>
        <strong>{profile.name}</strong>
        <div><small>{characterLine(profile)}</small></div>
      </div>
      <div style={{ display: 'flex', gap: '1.5rem' }}>
        <Button variant="quiet" onClick={() => onEdit(2)}>Изменить внешность</Button>
        <Button variant="quiet" onClick={() => onEdit(4)}>Изменить характер</Button>
      </div>
      <label style={{ display: 'flex', gap: '.5rem', alignItems: 'center' }}>
        <input
          type="checkbox"
          checked={profile.reactionsEnabled}
          onChange={(event) => store.save({ ...profile, reactionsEnabled: event.target.checked })}
        />
        Реплики при чтении
      </label>
      <Button variant="primary" disabled={store.packRequest.kind === 'loading'} onClick={() => void generate()}>
        {profile.phrasePack ? 'Обновить набор реплик' : 'Создать набор реплик'}
      </Button>
      {store.packRequest.kind === 'loading' && <small role="status">Создаём набор реплик. Это может занять минуту.</small>}
      <small>Это один запрос к ИИ. Обычное чтение запросов не тратит. ИИ может ошибаться.</small>
      <a href="/privacy" target="_blank" rel="noreferrer">Политика приватности</a>
      {(profile.aiConsentAt ?? 0) > 0 && (
        <Button variant="quiet" onClick={() => store.save({ ...profile, aiConsentAt: 0 })}>Отозвать согласие на ИИ</Button>
      )}
      {message && <small role="status">{message}</small>}
      {confirming ? (
        <div style={{ display: 'grid', gap: '.5rem', justifyItems: 'center' }}>
          <small>Удалить компаньона? Профиль и набор реплик исчезнут со всех устройств.</small>
          <div style={{ display: 'flex', gap: '1rem' }}>
            <Button variant="danger" onClick={() => { store.remove(); setConfirming(false); location.href = '/settings' }}>Удалить</Button>
            <Button variant="quiet" onClick={() => setConfirming(false)}>Оставить</Button>
          </div>
        </div>
      ) : (
        <Button variant="danger" onClick={() => setConfirming(true)}>Удалить компаньона</Button>
      )}
    </div>
  )
}

function AssetPicker({ appearance, slot, onPick }: { appearance: CompanionAppearance; slot: string; onPick: (assetId: string) => void }) {
  const [ids, setIds] = useState<string[]>([])
  const selected = appearanceAsset(appearance, slot)

  useEffect(() => {
    let alive = true
    void (async () => {
      const response = await fetch('/companions/manifest.json', { cache: 'force-cache' })
      const data = (await response.json()) as { assets: { id: string; slot: string }[] }
      if (alive) setIds(data.assets.filter((item) => item.slot === slot).map((item) => item.id))
    })()
    return () => { alive = false }
  }, [slot])

  return (
    <div style={{ display: 'grid', gap: '.35rem' }}>
      <span>{SLOT_TITLES[slot] ?? slot}</span>
      <div style={{ display: 'flex', gap: '.55rem', overflowX: 'auto', paddingBottom: '.25rem' }}>
        {ids.map((id) => (
          <button
            key={id}
            type="button"
            aria-label={assetLabel(id)}
            aria-pressed={selected === id}
            onClick={() => onPick(id)}
            style={{
              flex: '0 0 82px', width: 82, height: 92, padding: 2, borderRadius: 12,
              border: selected === id ? '2px solid #8C3B2E' : '1px solid rgba(0,0,0,.2)',
              background: 'var(--surface, #fff)', cursor: 'pointer', position: 'relative',
            }}
          >
            <CompanionFigure appearance={appearanceWithAsset(appearance, slot, id)} size={76} />
            {id.endsWith('.none') && <small style={{ position: 'absolute', inset: 'auto 0 4px' }}>Нет</small>}
          </button>
        ))}
      </div>
    </div>
  )
}

const PRESENTATION_OPTIONS = [
  ['masculine', 'Мужской'],
  ['feminine', 'Женский'],
  ['neutral', 'Нейтральный'],
] as const

function presentationAppearance(current: CompanionAppearance, presentation: CompanionProfile['presentation']): CompanionAppearance {
  if (presentation === 'masculine') return { ...current, hair: 'hair.11', brows: 'brows.04', eyes: 'eyes.17', mouth: 'mouth.01', beard: 'beard.none', body: 'body.20' }
  if (presentation === 'feminine') return { ...current, hair: 'hair.01', brows: 'brows.02', eyes: 'eyes.17', mouth: 'mouth.01', beard: 'beard.none', body: 'body.17' }
  return { ...current, hair: 'hair.23', brows: 'brows.01', eyes: 'eyes.16', mouth: 'mouth.02', beard: 'beard.none', body: 'body.22' }
}

function PaletteRow({ title, options, value, onPick }: { title: string; options: ReadonlyArray<readonly [string, string]>; value: string; onPick: (name: string) => void }) {
  return (
    <div style={{ display: 'flex', gap: '.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
      <span>{title}</span>
      {options.map(([name, color]) => (
        <button
          key={name}
          type="button"
          aria-label={`${title}: ${name}`}
          aria-pressed={value === name}
          onClick={() => onPick(name)}
          style={{
            width: 36, height: 36, borderRadius: 10, background: color, cursor: 'pointer',
            border: value === name ? '2px solid #8C3B2E' : '1px solid rgba(0,0,0,.2)',
          }}
        />
      ))}
    </div>
  )
}

export const _packIdForTest = PACK_ID
export const _defaultPersonality = DEFAULT_PERSONALITY
