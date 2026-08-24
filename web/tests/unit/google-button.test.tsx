import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'

import { GoogleButton } from '../../src/account/GoogleButton'

const mounted: Array<{ root: ReturnType<typeof createRoot>; node: HTMLDivElement }> = []
const actEnvironment = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT: boolean
}

beforeAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = true
})

afterAll(() => {
  actEnvironment.IS_REACT_ACT_ENVIRONMENT = false
})

afterEach(() => {
  for (const { root, node } of mounted.splice(0)) {
    act(() => root.unmount())
    node.remove()
  }
  delete document.documentElement.dataset.theme
  Object.defineProperty(window, 'google', { value: undefined, configurable: true })
  document
    .querySelectorAll('script[src="https://accounts.google.com/gsi/client"]')
    .forEach((script) => script.remove())
})

describe('GoogleButton', () => {
  it('передаёт GIS тёмную тему и выключает мышь и клавиатуру через inert', async () => {
    let renderedTheme = ''
    const initialize = vi.fn()
    Object.defineProperty(window, 'google', {
      configurable: true,
      value: {
        accounts: {
          id: {
            initialize,
            renderButton(parent: HTMLElement, options: { theme: string }) {
              renderedTheme = options.theme
              parent.append(document.createElement('button'))
            },
          },
        },
      },
    })
    // Настройки приложения используют capitalized ThemeName; компонент
    // нормализует dataset перед передачей значения в GIS.
    document.documentElement.dataset.theme = 'Dark'

    const node = document.createElement('div')
    document.body.append(node)
    const root = createRoot(node)
    mounted.push({ root, node })

    await act(async () => {
      root.render(
        <GoogleButton
          clientID="web-client.apps.googleusercontent.com"
          disabled
          onCredential={() => undefined}
          onError={() => undefined}
        />,
      )
    })

    const wrapper = node.firstElementChild as HTMLElement | null
    expect(initialize).toHaveBeenCalledWith(expect.objectContaining({
      client_id: 'web-client.apps.googleusercontent.com',
    }))
    expect(renderedTheme).toBe('filled_black')
    expect(wrapper?.getAttribute('aria-disabled')).toBe('true')
    // inert исключает и фирменную iframe/button GIS из tab order, чего одного
    // pointer-events для клавиатуры не делает.
    expect(wrapper?.hasAttribute('inert')).toBe(true)
    expect(wrapper?.style.pointerEvents).toBe('none')
  })

  it('после ошибки удаляет script и позволяет повторить загрузку при новом mount', async () => {
    const report = vi.fn()
    const firstNode = document.createElement('div')
    document.body.append(firstNode)
    const firstRoot = createRoot(firstNode)
    mounted.push({ root: firstRoot, node: firstNode })

    await act(async () => {
      firstRoot.render(
        <GoogleButton
          clientID="web-client.apps.googleusercontent.com"
          onCredential={() => undefined}
          onError={report}
        />,
      )
    })
    const failedScript = document.querySelector<HTMLScriptElement>(
      'script[src="https://accounts.google.com/gsi/client"]',
    )
    expect(failedScript).not.toBeNull()

    await act(async () => {
      failedScript?.dispatchEvent(new Event('error'))
    })
    expect(report).toHaveBeenCalledWith('Не удалось загрузить вход через Google.')
    expect(failedScript?.isConnected).toBe(false)

    const initialize = vi.fn()
    const secondNode = document.createElement('div')
    document.body.append(secondNode)
    const secondRoot = createRoot(secondNode)
    mounted.push({ root: secondRoot, node: secondNode })
    await act(async () => {
      secondRoot.render(
        <GoogleButton
          clientID="web-client.apps.googleusercontent.com"
          onCredential={() => undefined}
          onError={report}
        />,
      )
    })

    const retryScript = document.querySelector<HTMLScriptElement>(
      'script[src="https://accounts.google.com/gsi/client"]',
    )
    expect(retryScript).not.toBeNull()
    expect(retryScript).not.toBe(failedScript)

    Object.defineProperty(window, 'google', {
      configurable: true,
      value: {
        accounts: {
          id: {
            initialize,
            renderButton(parent: HTMLElement) {
              parent.append(document.createElement('button'))
            },
          },
        },
      },
    })
    await act(async () => {
      retryScript?.dispatchEvent(new Event('load'))
    })

    expect(initialize).toHaveBeenCalledTimes(1)
  })
})
