export type SocialProvider = 'google' | 'yandex'

export interface SocialReturn {
  provider: SocialProvider | null
  code: string
  error: string
  state: string
  next: '/account' | '/discovery'
}

const YANDEX_STATE_KEY = 'wolfy:yandex-oauth-state'

/**
 * Адрес возврата принадлежит Wolfy и одновременно сообщает, какой провайдер
 * должен завершить поток. Google к этому моменту уже обменял GIS ID token на
 * httpOnly cookie через Wolfy, а Яндекс приносит completion code Читавука.
 */
export function socialReturnURL(
  provider: SocialProvider,
  origin: string,
  next: '/account' | '/discovery' = '/account',
  state = '',
): string {
  const target = new URL('/auth/return', origin)
  target.searchParams.set('provider', provider)
  target.searchParams.set('next', next)
  if (state) target.searchParams.set('state', state)
  return target.toString()
}

/**
 * Готовит Яндекс-вход и привязывает будущий completion code к этой вкладке.
 * Citavuk сохраняет query trusted returnUrl, поэтому nonce проходит редирект
 * провайдера, но не покидает браузерное хранилище как секрет проверки.
 */
export function beginYandexReturnURL(
  origin: string,
  next: '/account' | '/discovery' = '/account',
): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const state = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  sessionStorage.setItem(YANDEX_STATE_KEY, state)
  return socialReturnURL('yandex', origin, next, state)
}

/**
 * Одноразово подтверждает, что Яндекс-flow начался именно в этой вкладке.
 * Несовпадение не удаляет настоящий nonce: чужая ссылка не должна уметь
 * сорвать уже начатый пользователем вход.
 */
export function consumeYandexState(received: string): boolean {
  let expected = ''
  try {
    expected = sessionStorage.getItem(YANDEX_STATE_KEY) ?? ''
  } catch {
    return false
  }
  if (!received || !expected || received.length !== expected.length) return false

  // Сравниваем всю строку, не останавливаясь на первом различии.
  let difference = 0
  for (let index = 0; index < expected.length; index += 1) {
    difference |= expected.charCodeAt(index) ^ received.charCodeAt(index)
  }
  if (difference !== 0) return false
  sessionStorage.removeItem(YANDEX_STATE_KEY)
  return true
}

/** Разбирает только закрытый набор значений: URL провайдера не управляет роутером. */
export function parseSocialReturn(search: string): SocialReturn {
  const params = new URLSearchParams(search)
  const rawProvider = params.get('provider')
  const provider = rawProvider === 'google' || rawProvider === 'yandex'
    ? rawProvider
    : null
  return {
    provider,
    code: params.get('code')?.trim() ?? '',
    error: params.get('error')?.trim() ?? '',
    state: params.get('state')?.trim() ?? '',
    next: params.get('next') === '/discovery' ? '/discovery' : '/account',
  }
}
