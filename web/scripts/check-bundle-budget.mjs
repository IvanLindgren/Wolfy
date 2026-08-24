#!/usr/bin/env node
/**
 * Проверяет бюджет начальной оболочки и ленивых чанков.
 * shell (initial) должен уложиться в 200 KiB gzip, WASM отдельно, PDF отдельно.
 * Запускается после `vite build`, без тяжёлых зависимостей.
 */
import { readdir, readFile, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { gzipSync } from 'node:zlib'

const DIST = 'dist'
const BUDGET_KIB = 200
const BUDGET_BYTES = BUDGET_KIB * 1024

// Ленивые чанки, которые не входят в initial
const LAZY_PATTERNS = [
  /pdf/i,
  /dnd/i,
  /LibraryScreen/i,
  /ReaderScreen/i,
  /BookCover/i,
  /BookWords/i,
  /BookNotes/i,
  /AllWords/i,
  /DecksScreen/i,
  /TrainingScreen/i,
  /GrammarScreen/i,
  /ArticleScreen/i,
  /DiscoveryScreen/i,
  /PhotoScreen/i,
  /AccountScreen/i,
  /Onboarding/i,
  /SettingsScreen/i,
  /core\.worker/i,
  /pdf\.worker/i,
]

function isLazy(name) {
  return LAZY_PATTERNS.some((re) => re.test(name))
}

async function collectAssets(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const files = []
  for (const e of entries) {
    const full = join(dir, e.name)
    if (e.isDirectory()) files.push(...(await collectAssets(full)))
    else files.push(full)
  }
  return files
}

async function main() {
  const assetsDir = join(DIST, 'assets')
  let allFiles = []
  try {
    allFiles = await collectAssets(DIST)
  } catch (e) {
    console.error(`dist not found: ${e.message}`)
    process.exit(1)
  }

  const relevant = allFiles.filter((p) => p.endsWith('.js') || p.endsWith('.css'))
  const rows = []
  let initialGzip = 0
  let initialRaw = 0
  let lazyGzip = 0
  let wasmGzip = 0
  let pdfGzip = 0

  for (const file of relevant) {
    const buf = await readFile(file)
    const gz = gzipSync(buf).length
    const raw = buf.length
    const name = file.replace(/\\/g, '/')
    const base = name.split('/').pop()
    const isWasm = name.endsWith('.wasm')
    const isPdf = /pdf(\.worker)?/i.test(base)
    const lazy = isLazy(base) || isPdf // pdf counted separately

    let bucket = 'initial'
    if (isWasm) {
      bucket = 'wasm'
      wasmGzip += gz
    } else if (isPdf) {
      bucket = 'pdf'
      pdfGzip += gz
    } else if (lazy) {
      bucket = 'lazy'
      lazyGzip += gz
    } else {
      bucket = 'initial'
      initialGzip += gz
      initialRaw += raw
    }

    rows.push({ file: name.replace(DIST + '/', ''), raw, gz, bucket })
  }

  // Сортируем для красивого вывода
  rows.sort((a, b) => b.gz - a.gz)

  console.log('\nBundle budget report (gzip):')
  console.log('  file'.padEnd(48) + ' raw'.padStart(9) + ' gzip'.padStart(9) + ' bucket')
  console.log('─'.repeat(80))
  for (const r of rows) {
    console.log(
      `  ${r.file.padEnd(48)} ${String(r.raw).padStart(9)} ${String(r.gz).padStart(9)} ${r.bucket}`,
    )
  }
  console.log('─'.repeat(80))
  console.log(`  initial (shell) gzip: ${(initialGzip / 1024).toFixed(1)} KiB (raw ${(initialRaw / 1024).toFixed(1)} KiB)`)
  console.log(`  wasm gzip: ${(wasmGzip / 1024).toFixed(1)} KiB (не входит в бюджет)`)
  console.log(`  pdf (lazy) gzip: ${(pdfGzip / 1024).toFixed(1)} KiB (не входит в initial)`)
  console.log(`  lazy chunks gzip: ${(lazyGzip / 1024).toFixed(1)} KiB`)
  console.log(`  budget: ${BUDGET_KIB} KiB gzip для initial (без wasm/pdf)`)

  if (initialGzip > BUDGET_BYTES) {
    console.error(`\nFAIL: initial shell ${(initialGzip / 1024).toFixed(1)} KiB > ${BUDGET_KIB} KiB budget`)
    process.exit(1)
  } else {
    console.log(`\nPASS: initial shell укладывается в бюджет`)
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
