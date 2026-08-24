import { expect, test } from '@playwright/test'

function textPdf(text: string): Buffer {
  const escaped = text.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)')
  const stream = `BT\n/F1 18 Tf\n72 720 Td\n(${escaped}) Tj\nET`
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${Buffer.byteLength(stream, 'ascii')} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  const offsets: number[] = []
  let pdf = '%PDF-1.4\n'
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(pdf, 'ascii'))
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
  })
  const xref = Buffer.byteLength(pdf, 'ascii')
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  pdf += offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(pdf, 'ascii')
}

test('production-бандл открывает библиотеку без ошибок выполнения', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  await expect(page.getByRole('heading', { name: 'Книги' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Добавить книгу/ })).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('навигация связывает библиотеку, общий словарь и читалку', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  await page.getByRole('link', { name: 'Все слова' }).click()
  await expect(page.getByRole('heading', { name: 'Слова и фразы' })).toBeVisible()

  await page.getByRole('link', { name: 'К книгам' }).click()
  await expect(page.getByRole('heading', { name: 'Книги' })).toBeVisible()

  await page.goto('/reader')
  await expect(page.getByRole('heading', { name: /Нечего продолжать|Продолжить/ })).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('навигационные кнопки остаются одной ссылкой, а фильтры доступны с клавиатуры', async ({ page }) => {
  await page.goto('/library')

  const allWords = page.getByRole('link', { name: 'Все слова' })
  await expect(allWords).toHaveCount(1)
  await expect(allWords.locator('button')).toHaveCount(0)
  await allWords.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: 'Слова и фразы' })).toBeVisible()

  // На пустой библиотеке фильтры не показываются, поэтому добавляем слово
  // через обычный текстовый файл и затем возвращаемся к общему словарю.
  await page.goto('/library')
  await page.locator('input[type="file"]').setInputFiles({
    name: 'Keyboard navigation.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('Keyboard navigation book.'),
  })
  await expect(page).toHaveURL(/\/reader\/[^/]+$/)
  await page.locator('[data-t]').filter({ hasText: 'Keyboard' }).click()
  await page.getByRole('button', { name: 'В колоду книги' }).click()

  await page.goto('/library/words')
  const phrases = page.getByRole('button', { name: 'Фразы' })
  await expect(phrases).toHaveAttribute('aria-pressed', 'false')
  await phrases.focus()
  await page.keyboard.press('Space')
  await expect(phrases).toHaveAttribute('aria-pressed', 'true')

  await page.goto('/library')
  await expect(page.locator('a button')).toHaveCount(0)
})

test('PDF с текстовым слоем импортируется production-воркером', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  await page.locator('input[type="file"]').setInputFiles({
    name: 'Wolfy PDF test.pdf',
    mimeType: 'application/pdf',
    buffer: textPdf('Hello Wolfy'),
  })

  await expect(page).toHaveURL(/\/reader\/[^/]+$/, { timeout: 20_000 })
  await expect(page.getByText('Wolfy PDF test', { exact: true })).toBeVisible()
  await expect(page.getByText('Hello Wolfy', { exact: true })).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('из оглавления можно переключить две книги, открытые на первой главе', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  await page.locator('input[type="file"]').setInputFiles({
    name: 'Lifecycle first.pdf',
    mimeType: 'application/pdf',
    buffer: textPdf('First lifecycle book'),
  })
  await expect(page).toHaveURL(/\/reader\/[^/]+$/, { timeout: 20_000 })

  await page.goto('/library')
  await page.locator('input[type="file"]').setInputFiles({
    name: 'Lifecycle second.pdf',
    mimeType: 'application/pdf',
    buffer: textPdf('Second lifecycle book'),
  })
  await expect(page).toHaveURL(/\/reader\/[^/]+$/, { timeout: 20_000 })

  await page.getByRole('button', { name: 'Оглавление' }).click()
  await page.getByRole('link', { name: 'Lifecycle first' }).click()

  await expect(page.getByText('First lifecycle book', { exact: true })).toBeVisible()
  await expect(page.getByText('книга не открыта', { exact: true })).toHaveCount(0)
  expect(pageErrors).toEqual([])
})

test('drop в библиотеке добавляет одну книгу, а не обрабатывается повторно оболочкой', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  const transfer = await page.evaluateHandle(() => {
    const value = new DataTransfer()
    value.items.add(new File(['One dropped book.'], 'Wolfy single drop.txt', {
      type: 'text/plain',
    }))
    return value
  })
  const zone = page.getByText('EPUB · PDF · TXT', { exact: true }).locator('..').locator('..')

  await zone.dispatchEvent('dragenter', { dataTransfer: transfer })
  await zone.dispatchEvent('dragover', { dataTransfer: transfer })
  await zone.dispatchEvent('drop', { dataTransfer: transfer })

  await expect(page).toHaveURL(/\/reader\/[^/]+$/, { timeout: 20_000 })
  await page.goto('/library')
  await expect(
    page.locator('a[href^="/reader/"]').filter({ hasText: 'Wolfy single drop' }),
  ).toHaveCount(1)
  expect(pageErrors).toEqual([])
})
