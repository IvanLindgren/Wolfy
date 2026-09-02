import { afterEach, describe, expect, it, vi } from 'vitest'

import { EDITIONS, fileSize, latestRelease, shortSum } from '../../src/legal/downloads'

afterEach(() => {
  vi.unstubAllGlobals()
})

function serverReplies(reply: (url: string) => Response) {
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => Promise.resolve(reply(String(input)))))
}

describe('страница загрузок', () => {
  it('спрашивает сервер так, будто ничего не установлено', async () => {
    // current=0.0.0 — единственный способ получить последний пакет: сервер
    // отвечает 204, когда предложить нечего нового, а не «вот последний».
    let asked = ''
    serverReplies((url) => {
      asked = url
      return new Response(
        JSON.stringify({ version: '1.2.3', url: '/v1/update/files/Wolfy-1.2.3.msi', sha256: 'a'.repeat(64), size: 70_000_000 }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )
    })

    const release = await latestRelease('windows')
    expect(asked).toContain('platform=windows')
    expect(asked).toContain('current=0.0.0')
    expect(release?.version).toBe('1.2.3')
  })

  it('пустой ответ означает «сборки нет», а не ошибку', async () => {
    // 204 приходит у платформы, для которой выпуск ещё не собирался. Считать
    // это отказом значило бы пугать читателя сломанным сервером.
    serverReplies(() => new Response(null, { status: 204 }))
    await expect(latestRelease('windows-arm64')).resolves.toBeNull()
  })

  it('ответ не той формы не превращается в ссылку в никуда', async () => {
    serverReplies(() => new Response(JSON.stringify({ hello: 'world' }), { status: 200 }))
    await expect(latestRelease('linux')).rejects.toThrow()
  })

  it('отказ сервера доходит наверх', async () => {
    serverReplies(() => new Response('нет', { status: 503 }))
    await expect(latestRelease('android')).rejects.toThrow('503')
  })

  it('размер округляется до того, что читают глазами', () => {
    expect(fileSize(70_000_000)).toBe('66,8 МБ')
    expect(fileSize(300_000)).toBe('293 КБ')
    // Трёхзначные мегабайты — без десятых: «103,4 МБ» точнее, чем нужно.
    expect(fileSize(220 * 1024 * 1024)).toBe('220 МБ')
    expect(fileSize(0)).toBe('')
    expect(fileSize(Number.NaN)).toBe('')
  })

  it('отпечаток показывается только настоящий', () => {
    expect(shortSum('AB'.repeat(32))).toBe('abababab…abababab')
    expect(shortSum('короткий')).toBe('')
  })

  it('каждая платформа названа тем же ключом, что у сервера', () => {
    // Ключи сверяются с updates.patternFor: расхождение здесь — это 400 от
    // сервера на живой странице, и заметить его можно только глазами.
    expect(EDITIONS.map((item) => item.platform)).toEqual([
      'android',
      'windows',
      'windows-arm64',
      'linux',
    ])
  })
})
