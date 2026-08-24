import { afterEach, describe, expect, it, vi } from 'vitest'

import { googleComplete, socialStart } from '../../src/api/client'
import {
  beginYandexReturnURL,
  consumeYandexState,
  parseSocialReturn,
  socialReturnURL,
} from '../../src/account/socialFlow'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('браузерный социальный вход', () => {
  it('создаёт отдельный Wolfy return URL для каждого провайдера', () => {
    expect(socialReturnURL('google', 'https://wolfy.citavuk.ru')).toBe(
      'https://wolfy.citavuk.ru/auth/return?provider=google&next=%2Faccount',
    )
    expect(socialReturnURL('yandex', 'https://wolfy.citavuk.ru', '/discovery')).toBe(
      'https://wolfy.citavuk.ru/auth/return?provider=yandex&next=%2Fdiscovery',
    )
  })

  it('не принимает чужой provider и внешний маршрут после возврата', () => {
    expect(parseSocialReturn('?provider=github&next=https://evil.example&code=abc')).toEqual({
      provider: null,
      code: 'abc',
      error: '',
      state: '',
      next: '/account',
    })
  })

  it('принимает Yandex completion только в начавшей вход вкладке и один раз', () => {
    const returnUrl = new URL(beginYandexReturnURL('https://wolfy.citavuk.ru'))
    const state = returnUrl.searchParams.get('state') ?? ''

    expect(state).toMatch(/^[0-9a-f]{32}$/)
    expect(consumeYandexState('0'.repeat(32))).toBe(false)
    expect(consumeYandexState(state)).toBe(true)
    expect(consumeYandexState(state)).toBe(false)
  })

  it('запускает именно Yandex authorization endpoint', async () => {
    const provider = 'yandex' as const
    const fetchMock = vi.fn(async (
      _input: RequestInfo | URL,
      _init?: RequestInit,
    ) => new Response(JSON.stringify({
      authorizationUrl: `https://${provider}.example/authorize`,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const returnUrl = beginYandexReturnURL('https://wolfy.citavuk.ru')
    await expect(socialStart(provider, returnUrl, {
      id: 'browser-1', name: 'Chrome', platform: 'web',
    })).resolves.toBe(`https://${provider}.example/authorize`)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe(`/v1/auth/${provider}/start`)
    expect(JSON.parse(String(init?.body))).toEqual({
      returnUrl,
      returnTarget: 'web',
      device: { id: 'browser-1', name: 'Chrome', platform: 'web' },
    })
  })

  it('меняет Google GIS token только через Google endpoint Wolfy', async () => {
    const fetchMock = vi.fn(async (
      _input: RequestInfo | URL,
      _init?: RequestInit,
    ) => new Response(JSON.stringify({
      user: { id: 'reader-1', email: 'reader@example.test', displayName: 'Reader' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(googleComplete('signed-google-id-token', {
      id: 'browser-1', name: 'Chrome', platform: 'web',
    })).resolves.toEqual({
      kind: 'signedIn',
      account: { id: 'reader-1', email: 'reader@example.test', displayName: 'Reader' },
    })

    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/v1/auth/google')
    expect(init?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-Wolfy-Session': 'cookie',
    })
    expect(JSON.parse(String(init?.body))).toEqual({
      idToken: 'signed-google-id-token',
      device: { id: 'browser-1', name: 'Chrome', platform: 'web' },
    })
  })
})
