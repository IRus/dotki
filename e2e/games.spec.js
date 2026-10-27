import { test, expect } from '@playwright/test';

async function pair(browser, kind) {
  const a = await browser.newContext({ viewport: { width: 1280, height: 960 } });
  const b = await browser.newContext({ viewport: { width: 1280, height: 960 } });
  const red = await a.newPage();
  const blue = await b.newPage();
  const snapshots = {};
  for (const [name, page] of [['red', red], ['blue', blue]]) {
    page.on('websocket', ws => ws.on('framereceived', frame => {
      const message = JSON.parse(frame.payload.toString());
      if (message.room) snapshots[name] = message.room.game;
    }));
  }
  await red.goto('/');
  await red.getByRole('textbox', { name: 'Твоё имя' }).fill('Аня');
  await red.locator('article').filter({ has: red.getByRole('heading', { name: kind, exact: true }) }).getByRole('button', { name: 'Начать ↗' }).click();
  await expect(red.locator('canvas')).toBeVisible();
  await expect(red.getByTestId('game-status')).toContainText('Ждём друга');
  const code = new URL(red.url()).searchParams.get('room');
  await blue.goto(`/?room=${code}`);
  await blue.getByRole('textbox', { name: 'Твоё имя' }).fill('Борис');
  await blue.getByRole('button', { name: 'Войти ↗' }).click();
  await expect(blue.locator('canvas')).toBeVisible();
  await expect(red.getByTestId('game-status')).toContainText('Твой ход');
  return { red, blue, a, b, code, snapshots };
}

async function move(page, x, y, count, dots = false) {
  const canvas = page.locator('canvas');
  const box = await canvas.boundingBox();
  const step = dots ? Math.max(5, Math.min((box.width - 48) / 29, (box.height - 48) / 42)) : 32;
  const px = box.width / 2 + (x - (dots ? 14.5 : 0)) * step;
  const py = box.height / 2 + (y - (dots ? 21 : 0)) * step;
  await canvas.click({ position: { x: px, y: py } });
  await expect(canvas).toHaveAttribute('data-stones', String(count));
}

test('lobby renders without JS errors and works at phone width', async ({ page }, testInfo) => {
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Лист в клетку. И вы вдвоём.' })).toBeVisible();
  await expect(page.locator('article')).toHaveCount(2);
  await page.screenshot({ path: testInfo.outputPath('lobby-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Крестики-нолики', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('lobby-mobile.png'), fullPage: true });
  expect(errors).toEqual([]);
});

test('five-in-a-row: two browsers, turn enforcement, reload, win, rematch', async ({ browser }, testInfo) => {
  const { red, blue, a, b } = await pair(browser, 'Крестики-нолики');
  try {
    // Blue cannot place while it is red's turn.
    const box = await blue.locator('canvas').boundingBox();
    await blue.locator('canvas').click({ position: { x: box.width / 2, y: box.height / 2 } });
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '0');
    await move(red, 0, 0, 1);
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '1');
    await blue.reload();
    await expect(blue.getByTestId('game-status')).toContainText('Твой ход');
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '1');
    for (let i = 0; i < 4; i++) {
      await move(blue, i, 2, i * 2 + 2);
      await expect(red.getByTestId('game-status')).toContainText('Твой ход');
      await move(red, i + 1, 0, i * 2 + 3);
    }
    await expect(red.getByTestId('game-status')).toContainText('Твоя победа');
    await expect(blue.getByTestId('game-status')).toContainText('Победа соперника');
    await red.screenshot({ path: testInfo.outputPath('five-victory.png'), fullPage: true });
    await red.getByRole('button', { name: 'Сыграть ещё раз ↗' }).click();
    await expect(red.getByRole('button', { name: 'Ждём согласия…' })).toBeDisabled();
    await blue.getByRole('button', { name: 'Сыграть ещё раз ↗' }).click();
    await expect(red.locator('canvas')).toHaveAttribute('data-stones', '0');
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '0');
    await expect(red.getByTestId('game-status')).toContainText('Твой ход');
  } finally { await a.close(); await b.close(); }
});

test('dots: A5 diagonal capture, score, two passes', async ({ browser }, testInfo) => {
  const { red, blue, a, b } = await pair(browser, 'Точки');
  try {
    const moves = [[red, 4, 5], [blue, 5, 5], [red, 5, 4], [blue, 20, 20], [red, 6, 5], [blue, 21, 20], [red, 5, 6]];
    for (let i = 0; i < moves.length; i++) {
      const [page, x, y] = moves[i];
      await expect(page.getByTestId('game-status')).toContainText('Твой ход');
      await move(page, x, y, i + 1, true);
    }
    await expect(red.locator('.score').first()).toHaveText('1');
    await expect(blue.locator('.score').first()).toHaveText('1');
    await red.screenshot({ path: testInfo.outputPath('dots-capture.png'), fullPage: true });
    await blue.getByRole('button', { name: 'Пропустить ход' }).click();
    await red.getByRole('button', { name: 'Пас · завершить партию' }).click();
    await expect(red.getByTestId('game-status')).toContainText('Твоя победа');
    await expect(blue.getByTestId('game-status')).toContainText('Победа соперника');
  } finally { await a.close(); await b.close(); }
});

test('full room is rejected; offline opponent pauses; phone keyboard plays', async ({ browser }, testInfo) => {
  const { red, blue, a, b, code } = await pair(browser, 'Крестики-нолики');
  const c = await browser.newContext();
  try {
    const visitor = await c.newPage();
    await visitor.goto(`/?room=${code}`);
    await visitor.getByRole('button', { name: 'Войти ↗' }).click();
    await expect(visitor.getByRole('status')).toContainText('В комнате уже два игрока');
    await red.setViewportSize({ width: 390, height: 844 });
    await red.locator('canvas').focus();
    await red.keyboard.press('ArrowRight');
    await red.keyboard.press('Enter');
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '1');
    expect(await red.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await red.screenshot({ path: testInfo.outputPath('game-mobile.png'), fullPage: true });
    await blue.close();
    await expect(red.getByTestId('game-status')).toContainText('Соперник отключился');
    await red.keyboard.press('ArrowDown');
    await red.keyboard.press('Enter');
    await expect(red.locator('canvas')).toHaveAttribute('data-stones', '1');
  } finally { await a.close(); await b.close(); await c.close(); }
});

test('duplicated player session replaces old tab without a reconnect fight', async ({ browser }) => {
  const { red, blue, a, b, code } = await pair(browser, 'Крестики-нолики');
  try {
    const token = await red.evaluate(code => sessionStorage.getItem(`dotki.token.${code}`), code);
    const replacement = await a.newPage();
    await replacement.addInitScript(({ code, token }) => sessionStorage.setItem(`dotki.token.${code}`, token), { code, token });
    await replacement.goto(`/?room=${code}`);
    await expect(replacement.getByTestId('game-status')).toContainText('Твой ход');
    await expect(red.getByRole('status')).toContainText('Игра открыта в другой вкладке');
    await move(replacement, 0, 0, 1);
    await move(blue, 0, 2, 2);
    await expect(replacement.getByTestId('game-status')).toContainText('Твой ход');
    await move(replacement, 1, 0, 3);
    await expect(red.getByTestId('game-status')).toContainText('Нет соединения');
  } finally { await a.close(); await b.close(); }
});

test('infinite board pans without making a move and zoom preserves coordinates', async ({ browser }) => {
  const { red, blue, a, b, snapshots } = await pair(browser, 'Крестики-нолики');
  try {
    const canvas = red.locator('canvas');
    const box = await canvas.boundingBox();
    const center = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
    await red.mouse.move(center.x, center.y);
    await red.mouse.down();
    await red.mouse.move(center.x + 160, center.y + 160, { steps: 10 });
    await red.mouse.up();
    await expect(canvas).toHaveAttribute('data-stones', '0');
    await canvas.click({ position: { x: box.width / 2, y: box.height / 2 } });
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '1');
    expect(snapshots.blue.stones[0].point).toEqual({ x: -5, y: -5 });
    await move(blue, 0, 2, 2);
    await red.getByRole('button', { name: '+', exact: true }).click();
    // Zoom is about viewport center; after scaling by 1.25, one square is 40 px.
    await canvas.click({ position: { x: box.width / 2 + 40, y: box.height / 2 } });
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '3');
    expect(snapshots.blue.stones[2].point).toEqual({ x: -4, y: -5 });
  } finally { await a.close(); await b.close(); }
});

test('five marks sit inside cells and clicks near the cell corners select that cell', async ({ browser }, testInfo) => {
  const { red, blue, a, b, snapshots } = await pair(browser, 'Крестики-нолики');
  try {
    const canvas = red.locator('canvas');
    const box = await canvas.boundingBox();
    await canvas.click({ position: { x: box.width / 2 + 14, y: box.height / 2 + 14 } });
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '1');
    expect(snapshots.blue.stones[0].point).toEqual({ x: 0, y: 0 });
    await move(blue, 0, 1, 2);
    await expect(canvas).toHaveAttribute('data-stones', '2');
    const pixels = await canvas.evaluate(c => {
      const ctx = c.getContext('2d');
      const r = c.getBoundingClientRect();
      const ratio = c.width / r.width;
      function sample(x, y) {
        const p = ctx.getImageData(Math.round(x * ratio) - 2, Math.round(y * ratio) - 2, 5, 5).data;
        return Array.from({ length: 25 }, (_, i) => Array.from(p.slice(i * 4, i * 4 + 3)));
      }
      return {
        emptyCenter: sample(r.width / 2 + 64, r.height / 2 + 64),
        cellBorder: sample(r.width / 2 + 80, r.height / 2 + 64),
        cross: sample(r.width / 2, r.height / 2),
        circleCenter: sample(r.width / 2, r.height / 2 + 32),
        circleRing: sample(r.width / 2 + 9, r.height / 2 + 32),
      };
    });
    expect(pixels.emptyCenter.every(([r, g, b]) => r === 255 && g === 254 && b === 249)).toBe(true);
    expect(pixels.cellBorder.some(([, g]) => g < 245)).toBe(true);
    expect(pixels.cross.some(([r, g]) => r > 160 && g < 140)).toBe(true);
    expect(pixels.circleCenter.every(([r, g, b]) => r === 255 && g === 254 && b === 249)).toBe(true);
    expect(pixels.circleRing.some(([r, , b]) => r < 120 && b > 140)).toBe(true);
    await red.screenshot({ path: testInfo.outputPath('five-cell-centers.png'), fullPage: true });
  } finally { await a.close(); await b.close(); }
});

for (const kind of ['Точки', 'Крестики-нолики']) for (const level of ['Medium', 'Hard']) {
  test(`AI ${kind} ${level}: starts alone, replies, survives reload, resigns and rematches`, async ({ page }, testInfo) => {
    const errors = [];
    const states = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('websocket', ws => ws.on('framereceived', frame => {
      const message = JSON.parse(frame.payload.toString());
      if (message.room) states.push(message.room);
    }));
    await page.goto('/');
    await page.locator('article').filter({ has: page.getByRole('heading', { name: kind, exact: true }) })
      .getByRole('button', { name: level, exact: true }).click();
    await page.locator('article').filter({ has: page.getByRole('heading', { name: kind, exact: true }) })
      .getByRole('button', { name: 'Против ИИ', exact: true }).click();
    await expect(page.getByTestId('game-status')).toContainText('Твой ход');
    await expect(page.getByText('Компьютерный соперник')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Пригласить друга ↗' })).toHaveCount(0);
    expect(states.at(-1).mode).toBe('AI');
    expect(states.at(-1).botLevel).toBe(level.toUpperCase());
    expect(states.at(-1).seats.find(s => s.bot).player).toBe('BLUE');
    const dots = kind === 'Точки';
    await move(page, dots ? 14 : 0, dots ? 21 : 0, 1, dots);
    await expect(page.getByTestId('game-status')).toContainText('ИИ думает');
    await expect(page.locator('canvas')).toHaveAttribute('data-stones', '2', { timeout: 15_000 });
    await expect(page.getByTestId('game-status')).toContainText('Твой ход');
    expect(states.at(-1).botLevel).toBe(level.toUpperCase());
    expect(states.at(-1).game.stones.map(s => s.player)).toEqual(['RED', 'BLUE']);
    await page.reload();
    await expect(page.locator('canvas')).toHaveAttribute('data-stones', '2');
    await expect(page.getByTestId('game-status')).toContainText('Твой ход');
    await page.screenshot({ path: testInfo.outputPath('ai-game.png'), fullPage: true });
    await page.getByRole('button', { name: 'Сдаться', exact: true }).click();
    await page.getByRole('button', { name: 'Да, сдаться', exact: true }).click();
    await expect(page.getByTestId('game-status')).toContainText('Победа соперника');
    await page.getByRole('button', { name: 'Сыграть ещё раз ↗' }).click();
    await expect(page.locator('canvas')).toHaveAttribute('data-stones', '0');
    await expect(page.getByTestId('game-status')).toContainText('Твой ход');
    if (dots && level === 'Medium') {
      await page.getByRole('button', { name: 'Пропустить ход' }).click();
      await expect(page.getByTestId('game-status')).toContainText('Ничья', { timeout: 15_000 });
    }
    expect(errors).toEqual([]);
  });
}

test('undo restores the move in both browsers; reply and timeout close the window', async ({ browser }) => {
  const { red, blue, a, b } = await pair(browser, 'Крестики-нолики');
  try {
    await move(red, 0, 0, 1);
    await expect(red.getByRole('button', { name: /Отменить ход/ })).toBeVisible();
    await expect(blue.getByRole('button', { name: /Отменить ход/ })).toHaveCount(0);
    await red.getByRole('button', { name: /Отменить ход/ }).click();
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '0');
    await expect(red.getByTestId('game-status')).toContainText('Твой ход');
    await move(red, 1, 0, 1);
    await move(blue, 0, 2, 2);
    await expect(red.getByRole('button', { name: /Отменить ход/ })).toHaveCount(0);
    await expect(blue.getByRole('button', { name: /Отменить ход/ })).toBeVisible();
    await expect(blue.getByRole('button', { name: /Отменить ход/ })).toHaveCount(0, { timeout: 6500 });
    await expect(blue.locator('canvas')).toHaveAttribute('data-stones', '2');
  } finally { await a.close(); await b.close(); }
});

test('undo while hard AI thinks cancels its reply', async ({ page }) => {
  await page.goto('/');
  const card = page.locator('article').filter({ has: page.getByRole('heading', { name: 'Точки', exact: true }) });
  await card.getByRole('button', { name: 'Hard', exact: true }).click();
  await card.getByRole('button', { name: 'Против ИИ', exact: true }).click();
  await expect(page.getByTestId('game-status')).toContainText('Твой ход');
  await move(page, 14, 21, 1, true);
  await page.getByRole('button', { name: /Отменить ход/ }).click();
  await expect(page.locator('canvas')).toHaveAttribute('data-stones', '0');
  await expect(page.getByTestId('game-status')).toContainText('Твой ход');
  await page.reload();
  await expect(page.locator('canvas')).toHaveAttribute('data-stones', '0');
  await expect(page.getByTestId('game-status')).toContainText('Твой ход');
});
