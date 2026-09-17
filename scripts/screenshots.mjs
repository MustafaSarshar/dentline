/**
 * Captures the README screenshots from the running stack.
 *
 *   docker compose up -d --build        # wait until http://localhost:3001 answers
 *   npx playwright@1.63.0 install chromium
 *   node scripts/screenshots.mjs
 *
 * Playwright is deliberately not a project dependency: this is an authoring task, not part of
 * the build. The images it writes to docs/images are committed.
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const APP = process.env.APP_URL ?? 'http://localhost:3001';
const GRAFANA = process.env.GRAFANA_URL ?? 'http://localhost:3000';
const OUT = 'docs/images';

const PHONE = { width: 430, height: 940 };
const DESKTOP = { width: 1440, height: 900 };

await mkdir(OUT, { recursive: true });
const browser = await chromium.launch();

/** Waits for fonts and the entrance animations before shooting. */
async function settle(page, ms = 900) {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(ms);
}

async function shoot(page, name) {
  await page.screenshot({ path: `${OUT}/${name}.png` });
  console.log(`${name}.png`);
}

// --- Patient flow -----------------------------------------------------------
{
  const context = await browser.newContext({ viewport: PHONE, deviceScaleFactor: 2 });
  const page = await context.newPage();

  await page.goto(APP, { waitUntil: 'networkidle' });
  await settle(page);
  await shoot(page, 'patient-treatments');

  await page.getByRole('button', { name: /Check-up/ }).click();
  await settle(page, 600);
  await shoot(page, 'patient-practitioners');

  await page.getByRole('button', { name: /First available/ }).click();
  await page.waitForTimeout(1200); // availability request plus the fade-in
  await settle(page, 400);
  await shoot(page, 'patient-slots');

  // Straight into the details step by picking the first offered time.
  const slot = page.locator('button', { hasText: /^\d\d:\d\d$/ }).first();
  if (await slot.count()) {
    await slot.click();
    await settle(page, 600);
    await shoot(page, 'patient-details');
  }

  await context.close();
}

// --- Staff dashboard --------------------------------------------------------
{
  const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 });
  const page = await context.newPage();

  await page.goto(`${APP}/staff`, { waitUntil: 'networkidle' });
  await settle(page);
  await shoot(page, 'staff-today');

  // The drawer, opened from the first appointment block on the board.
  const block = page.locator('[data-appt] button').first();
  if (await block.count()) {
    await block.click();
    await page.getByText(/^History$/i).waitFor();
    await settle(page, 1500); // the drawer also fetches the notification history from :8082
    await shoot(page, 'staff-drawer');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  }

  await page.goto(`${APP}/staff/week`, { waitUntil: 'networkidle' });
  await settle(page);
  await shoot(page, 'staff-week');

  await page.goto(`${APP}/staff/waitlist`, { waitUntil: 'networkidle' });
  await settle(page);
  await shoot(page, 'staff-waitlist');

  await page.goto(`${APP}/staff/recalls`, { waitUntil: 'networkidle' });
  await settle(page);
  await shoot(page, 'staff-recalls');

  await context.close();
}

// --- Grafana ----------------------------------------------------------------
{
  const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 });
  const page = await context.newPage();
  try {
    // Grafana holds live connections open, so networkidle never fires here.
    await page.goto(`${GRAFANA}/d/dentline-overview?kiosk`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForTimeout(25_000); // eleven panels have to query Prometheus and draw
    await shoot(page, 'grafana');
  } catch (error) {
    console.warn('Skipped Grafana:', error.message);
  }
  await context.close();
}

await browser.close();
