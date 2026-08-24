/**
 * Страница по фото.
 *
 * Снимок делается камерой устройства или выбирается файлом, **сжимается в
 * браузере** и уходит на распознавание. Сжатие обязательно: снимок с
 * двенадцатимегапиксельной камеры весит четыре мегабайта, а для распознавания
 * текста хватает полутора тысяч пикселей по длинной стороне. Отправлять
 * четыре мегабайта по мобильному интернету ради того же результата — значит
 * заставить читателя ждать вчетверо дольше и заплатить за трафик.
 *
 * Результат становится обычной книгой внутри приложения: страницы дописываются
 * одна за другой, склейку делает ядро.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'

import * as api from '../api/client'
import { useAccount, useCapabilities } from '../account/useAccount'
import { toast } from '../app/toasts'
import { Button } from '../widgets/Button'
import { CameraIcon, CheckIcon } from '../widgets/icons'
import page from '../widgets/Page.module.css'
import { WolfyCompanion } from '../widgets/Wolfy'
import { appendSnapshot } from './import'
import styles from './library.module.css'

/** Длинная сторона сжатого снимка. Больше распознаванию не нужно. */
const MAX_SIDE = 1600

export function PhotoScreen() {
  const account = useAccount()
  const capabilities = useCapabilities()
  const navigate = useNavigate()

  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const chooser = useRef<HTMLInputElement>(null)

  const [shot, setShot] = useState<string | null>(null)
  const [blob, setBlob] = useState<Blob | null>(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [camera, setCamera] = useState(false)

  const stopCamera = useCallback(() => {
    stream.current?.getTracks().forEach((track) => track.stop())
    stream.current = null
    setCamera(false)
  }, [])

  useEffect(() => stopCamera, [stopCamera])

  const startCamera = useCallback(async () => {
    try {
      const media = await navigator.mediaDevices.getUserMedia({
        // Задняя камера: страницу книги снимают ей, а не фронтальной.
        video: { facingMode: { ideal: 'environment' } },
      })
      stream.current = media
      setCamera(true)
      if (video.current) {
        video.current.srcObject = media
        await video.current.play()
      }
    } catch {
      toast('Камера недоступна. Выберите снимок файлом.')
    }
  }, [])

  const capture = useCallback(async () => {
    const source = video.current
    if (!source) return
    const canvas = document.createElement('canvas')
    const scale = Math.min(1, MAX_SIDE / Math.max(source.videoWidth, source.videoHeight))
    canvas.width = Math.round(source.videoWidth * scale)
    canvas.height = Math.round(source.videoHeight * scale)
    canvas.getContext('2d')?.drawImage(source, 0, 0, canvas.width, canvas.height)

    const compressed = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', 0.82),
    )
    if (!compressed) return
    setBlob(compressed)
    setShot(URL.createObjectURL(compressed))
    stopCamera()
  }, [stopCamera])

  const choose = useCallback(async (file: File) => {
    const compressed = await compress(file)
    setBlob(compressed)
    setShot(URL.createObjectURL(compressed))
  }, [])

  const recognize = useCallback(async () => {
    if (!blob) return
    setBusy(true)
    try {
      const base64 = await toBase64(blob)
      const recognized = await api.recognize(base64, blob.type)
      setText(recognized)
      if (!recognized.trim()) toast('На снимке не нашлось текста')
    } catch (error) {
      toast(
        error instanceof api.ApiError
          ? error.message
          : 'Распознавание сейчас недоступно — нужна сеть',
      )
    } finally {
      setBusy(false)
    }
  }, [blob])

  const keep = useCallback(async () => {
    const result = await appendSnapshot(text)
    if (result.kind === 'refused') {
      toast(result.message)
      return
    }
    toast('Страница добавлена в книгу снимков')
    void navigate({ to: '/reader/$bookId', params: { bookId: result.book.id } })
  }, [text, navigate])

  if (account.data === null) {
    return (
      <WolfyCompanion mood="kind" title="Для распознавания нужен вход">
        <p className={page.muted} style={{ maxWidth: '32rem' }}>
          Вход нужен только для отправки снимка. <strong>Чтение и колоды</strong>
          работают без аккаунта.
        </p>
        <Button variant="primary" onClick={() => void navigate({ to: '/account' })}>
          Войти
        </Button>
      </WolfyCompanion>
    )
  }

  if (capabilities.data && !capabilities.data.ocr) {
    return (
      <WolfyCompanion mood="kind" title="Распознавание не настроено">
        <p className={page.muted}>На этом сервере распознавание страниц выключено.</p>
      </WolfyCompanion>
    )
  }

  return (
    <div className={page.page}>
      <header className={page.head}>
        <div>
          <div className={page.kicker}>Библиотека</div>
          <h1 className={page.title}>Страница по фото</h1>
          <p className={page.subtitle}>
            Снимите разворот бумажной книги — Wolfy соберёт из страниц обычную
            книгу с разбором и колодой.
          </p>
        </div>
      </header>

      <div className={styles.camera}>
        {camera ? (
          <>
            <div className={styles.preview}>
              <video ref={video} playsInline muted />
            </div>
            <div className={page.row}>
              <Button variant="primary" onClick={() => void capture()}>
                Снять
              </Button>
              <Button variant="quiet" onClick={stopCamera}>
                Отмена
              </Button>
            </div>
          </>
        ) : shot ? (
          <>
            <div className={styles.preview}>
              <img src={shot} alt="Снятая страница" />
            </div>
            <div className={page.row}>
              <Button variant="primary" onClick={() => void recognize()} disabled={busy}>
                {busy ? 'Распознаём…' : 'Распознать'}
              </Button>
              <Button
                variant="quiet"
                onClick={() => {
                  setShot(null)
                  setBlob(null)
                  setText('')
                }}
              >
                Другой снимок
              </Button>
            </div>
          </>
        ) : (
          <div className={page.row}>
            <Button variant="primary" onClick={() => void startCamera()}>
              <CameraIcon size={16} /> Снять камерой
            </Button>
            <Button onClick={() => chooser.current?.click()}>Выбрать снимок</Button>
            <input
              ref={chooser}
              type="file"
              accept="image/*"
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0]
                if (file) void choose(file)
                event.target.value = ''
              }}
            />
          </div>
        )}

        {text && (
          <>
            <h2 className={page.sectionTitle}>Что распозналось</h2>
            <div className={styles.recognized} lang="en">
              {text}
            </div>
            <div className={page.row}>
              <Button variant="primary" onClick={() => void keep()}>
                <CheckIcon size={16} /> Добавить страницу в книгу
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Сжимает снимок до разумного размера.
 *
 * `createImageBitmap` + `canvas.toBlob` — тот же путь, что в задании: он
 * работает в браузере, не тянет библиотек и не разворачивает картинку в
 * память дважды.
 */
async function compress(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bitmap.width * scale)
  canvas.height = Math.round(bitmap.height * scale)
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close()

  const compressed = await new Promise<Blob | null>((resolve) =>
    canvas.toBlob(resolve, 'image/jpeg', 0.82),
  )
  return compressed ?? file
}

/** Снимок уходит в base64: весь остальной API говорит на JSON. */
function toBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result)
      resolve(result.slice(result.indexOf(',') + 1))
    }
    reader.onerror = () => reject(new Error('снимок не прочитался'))
    reader.readAsDataURL(blob)
  })
}
