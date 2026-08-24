import { useEffect, useRef, useState } from 'react'

const SCRIPT_URL = 'https://accounts.google.com/gsi/client'

interface CredentialResponse {
  credential?: string
}

interface GoogleIdentity {
  accounts: {
    id: {
      initialize(config: {
        client_id: string
        callback: (response: CredentialResponse) => void
        auto_select?: boolean
        cancel_on_tap_outside?: boolean
      }): void
      renderButton(parent: HTMLElement, options: {
        type: 'standard'
        theme: 'outline' | 'filled_black'
        size: 'large'
        text: 'continue_with'
        shape: 'pill'
        logo_alignment: 'center'
        width: number
        locale: string
      }): void
    }
  }
}

declare global {
  interface Window {
    google?: GoogleIdentity
  }
}

let loader: Promise<GoogleIdentity> | null = null

function loadGoogleIdentity(): Promise<GoogleIdentity> {
  if (window.google?.accounts.id) return Promise.resolve(window.google)
  if (loader) return loader
  loader = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_URL}"]`)
    // Чужой или уже завершившийся script мог отдать load/error до нашего
    // mount. Подписка на прошедшее событие зависла бы навсегда, поэтому
    // переиспользуем только собственную незавершённую попытку.
    const reusable = existing?.dataset.wolfyGoogleIdentity === 'loading'
    const script = reusable ? existing : document.createElement('script')
    script.dataset.wolfyGoogleIdentity = 'loading'

    const failed = (message: string) => {
      loader = null
      if (script.dataset.wolfyGoogleIdentity) script.remove()
      reject(new Error(message))
    }
    const loaded = () => {
      if (!window.google?.accounts.id) {
        failed('Библиотека Google загрузилась не полностью.')
        return
      }
      script.dataset.wolfyGoogleIdentity = 'ready'
      loader = null
      resolve(window.google)
    }
    script.addEventListener('load', loaded, { once: true })
    script.addEventListener(
      'error',
      () => failed('Не удалось загрузить вход через Google.'),
      { once: true },
    )
    if (!reusable) {
      script.src = SCRIPT_URL
      script.async = true
      script.defer = true
      document.head.append(script)
    }
  })
  return loader
}

/** Фирменную кнопку рисует сама GIS — это сохраняет корректный Google brand. */
export function GoogleButton({
  clientID,
  disabled,
  onCredential,
  onError,
}: {
  clientID: string
  disabled?: boolean
  onCredential: (idToken: string) => void
  onError: (message: string) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const callback = useRef(onCredential)
  const report = useRef(onError)
  const [failed, setFailed] = useState(false)
  callback.current = onCredential
  report.current = onError

  useEffect(() => {
    let cancelled = false
    setFailed(false)
    void loadGoogleIdentity().then((google) => {
      if (cancelled || !container.current) return
      google.accounts.id.initialize({
        client_id: clientID,
        callback: (response) => {
          if (response.credential) callback.current(response.credential)
        },
        auto_select: false,
        cancel_on_tap_outside: true,
      })
      container.current.replaceChildren()
      const theme = (document.documentElement.dataset.theme ?? '').toLowerCase()
      const dark = theme === 'dark' || theme === 'oled'
      google.accounts.id.renderButton(container.current, {
        type: 'standard',
        theme: dark ? 'filled_black' : 'outline',
        size: 'large',
        text: 'continue_with',
        shape: 'pill',
        logo_alignment: 'center',
        width: 240,
        locale: 'ru',
      })
    }).catch((error: Error) => {
      if (cancelled) return
      setFailed(true)
      report.current(error.message)
    })
    return () => { cancelled = true }
  }, [clientID])

  if (failed) return null
  return <div ref={container} aria-disabled={disabled} inert={disabled ? true : undefined} style={{
    minHeight: 40,
    pointerEvents: disabled ? 'none' : 'auto',
    opacity: disabled ? 0.6 : 1,
  }} />
}
