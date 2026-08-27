/**
 * Отрисовка компаньона слоями пака.
 *
 * Безопасный конвейер: слой приезжает как <img> с data-URI. Внутри
 * изолированного SVG-документа скрипты не исполняются и внешние стили не
 * видны, а токены цветов подставляются строкой до сборки URI. Никакого
 * dangerouslySetInnerHTML.
 *
 * Контурный цвет зашит в фигуру, а не берётся из темы: иллюстрацию всегда
 * показывают на тёплой бумажной подложке, поэтому тёмная тема не делает её
 * невидимой.
 */
import { useEffect, useState } from 'react'

import { LAYER_ORDER, appearanceAsset, PALETTES, type CompanionAppearance } from './model'

interface ManifestAsset { id: string; slot: string; file: string }
interface Manifest { packId: string; canvas: { width: number; height: number }; layerOrder: string[]; assets: ManifestAsset[] }

let manifestPromise: Promise<Manifest> | null = null

function manifest(): Promise<Manifest> {
  manifestPromise ??= fetch('/companions/manifest.json', { cache: 'force-cache' })
    .then((response) => response.json() as Promise<Manifest>)
  return manifestPromise
}

const layerCache = new Map<string, string>()

async function layerText(file: string): Promise<string> {
  const cached = layerCache.get(file)
  if (cached) return cached
  const response = await fetch(`/companions/${file}`, { cache: 'force-cache' })
  const text = await response.text()
  layerCache.set(file, text)
  return text
}

const INK = '#1A1816'

function paletteColor(name: string): string | null {
  for (const group of Object.values(PALETTES)) {
    for (const [key, value] of group as ReadonlyArray<readonly [string, string]>) {
      if (key === name) return value
    }
  }
  return null
}

function recolor(xml: string, appearance: CompanionAppearance): string {
  const skin = paletteColor(appearance.skin) ?? '#F2C6A0'
  const hair = paletteColor(appearance.hairColor) ?? '#1A1816'
  const outfit = paletteColor(appearance.outfitColor) ?? '#8C3B2E'
  const accent = paletteColor(appearance.accentColor) ?? '#C9A227'
  return xml
    .replaceAll('var(--wolfy-skin)', skin)
    .replaceAll('var(--wolfy-hair)', hair)
    .replaceAll('var(--wolfy-outfit)', outfit)
    .replaceAll('var(--wolfy-accent)', accent)
    .replaceAll('var(--wolfy-ink)', INK)
}

export function useCompanionFigure(appearance: CompanionAppearance): string | null {
  const [uri, setUri] = useState<string | null>(null)
  const key = [
    appearance.skin, appearance.hairColor, appearance.outfitColor, appearance.accentColor,
    LAYER_ORDER.map((slot) => appearanceAsset(appearance, slot)).join(','),
  ].join('|')

  useEffect(() => {
    let alive = true
    void (async () => {
      const data = await manifest()
      const parts: string[] = []
      for (const slot of data.layerOrder) {
        const assetId = appearanceAsset(appearance, slot)
        if (assetId.endsWith('.none') && slot !== 'base') continue
        const fallbackId = slot === 'base' ? 'base.base' : `${slot}.none`
        const asset = data.assets.find((item) => item.id === assetId)
          ?? data.assets.find((item) => item.id === fallbackId)
        if (!asset) continue
        parts.push(recolor(await layerText(asset.file), appearance))
      }
      const combined =
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${data.canvas.width} ${data.canvas.height}">` +
        parts.join('') +
        '</svg>'
      if (alive) setUri(`data:image/svg+xml;utf8,${encodeURIComponent(combined)}`)
    })()
    return () => { alive = false }
  }, [key]) // eslint-disable-line react-hooks/exhaustive-deps

  return uri
}

export function CompanionFigure({ appearance, size = 180 }: { appearance: CompanionAppearance; size?: number }) {
  const uri = useCompanionFigure(appearance)
  if (!uri) return <div style={{ width: size, height: size }} aria-hidden />
  return (
    <img
      src={uri}
      width={size}
      height={size}
      alt=""
      aria-hidden
      style={{ display: 'block', maxWidth: '100%' }}
    />
  )
}
