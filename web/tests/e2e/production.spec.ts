import { expect, test } from '@playwright/test'

test('production-бандл открывает библиотеку без ошибок выполнения', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.goto('/library')
  await expect(page.getByRole('heading', { name: 'Книги' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Добавить книгу/ })).toBeVisible()
  expect(pageErrors).toEqual([])
})
