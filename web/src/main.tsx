/**
 * Точка входа.
 *
 * Порядок здесь важен и не случаен:
 *
 * 1. Тема ставится **до** первой отрисовки React — иначе читатель, выбравший
 *    OLED-чёрную, получит вспышку кремовой бумаги в лицо.
 * 2. Ядро поднимается параллельно, а не до: разметка первого экрана уже в
 *    HTML, и ждать `.wasm`, чтобы её показать, незачем.
 * 3. Открытие файла из системы (`launch_queue`) перехватывается сразу, до
 *    того как приложение успеет отрисоваться: событие приходит один раз, и
 *    пропустить его значит потерять книгу, которую читатель уже открыл.
 */

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'

import './theme/theme.css'
import './theme/base.css'

import { router } from './app/router'
import { applyStoredTheme } from './app/theme'
import { claimLaunchFiles } from './library/launch'
import { useSession } from './core/session'
import { applyReaderPreferences } from './reader/preferences'

applyStoredTheme()
applyReaderPreferences()
claimLaunchFiles()

/**
 * Кэш серверных данных.
 *
 * Повторов немного и они недолгие: приложение работает офлайн, и десять
 * попыток дозвониться до сервера — это десять секунд, в течение которых
 * экран врёт, будто что-то грузится, вместо честного «нет сети».
 */
const queries = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 60_000,
      refetchOnWindowFocus: false,
      networkMode: 'offlineFirst',
    },
  },
})

void useSession.getState().boot()

const root = document.getElementById('root')
if (root) {
  createRoot(root).render(
    <StrictMode>
      <QueryClientProvider client={queries}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  )
}
