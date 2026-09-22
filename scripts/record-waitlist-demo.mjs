/**
 * Records the cross-service story as an animated GIF for the README: a patient cancels, and the
 * waitlist matcher hands the freed slot to the next patient in line, through Kafka.
 *
 *   docker compose down -v && docker compose up -d --build   # a freshly seeded stack
 *   npx playwright@1.63.0 install chromium
 *   npm install --no-save playwright@1.63.0 ffmpeg-static
 *   node scripts/record-waitlist-demo.mjs
 *
 * Start from a fresh seed. The matcher will not offer a slot to someone who has already turned
 * that slot down, so running this repeatedly against the same data eventually finds no match,
 * and the script fails rather than filming a stale offer.
 *
 * Neither package is a project dependency: this is an authoring task, not part of the build.
 *
 * Run it against a freshly seeded stack. The matcher will not re-offer a slot a patient has
 * already turned down, so a database carrying offers from an earlier run can leave the clip
 * showing a stale offer rather than the one the recorded cancellation caused. The script
 * withdraws pending offers first and asserts afterwards that the offer on screen is the new one.
 */
import { chromium } from 'playwright';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import ffmpeg from 'ffmpeg-static';

const run = promisify(execFile);

const API = process.env.BOOKING_API ?? 'http://localhost:8081';
const APP = process.env.APP_URL ?? 'http://localhost:3001';
const OUT = 'docs/images';
const CLEANING = '10000000-0000-0000-0000-000000000002'; // 45 min, what the waiting patients want

const json = async (path, init) => {
  const response = await fetch(API + path, init);
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${path} → ${response.status} ${JSON.stringify(body)}`);
  return body;
};

/** The earliest weekday-morning slot, which is the window the seeded waitlist is waiting for. */
async function findMorningSlot() {
  const day = (offset) => new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);
  const { days } = await json(`/api/availability?treatmentTypeId=${CLEANING}&from=${day(1)}&to=${day(14)}`);
  for (const d of days) {
    for (const slot of d.slots) {
      const at = slot.startTime;
      const weekday = new Date(at).getDay();
      if (at.slice(11, 16) < '12:00' && weekday >= 1 && weekday <= 5) return slot;
    }
  }
  throw new Error('No weekday-morning slot in the next fortnight');
}

/** A caption bar, clearly an annotation rather than part of the app. It shifts the page down
 *  rather than covering its header. */
async function caption(page, text) {
  await page.evaluate((message) => {
    let bar = document.getElementById('demo-caption');
    if (!bar) {
      bar = document.createElement('div');
      bar.id = 'demo-caption';
      Object.assign(bar.style, {
        position: 'fixed', top: '0', left: '0', right: '0', zIndex: '9999', height: '44px',
        boxSizing: 'border-box', display: 'flex', alignItems: 'center',
        background: '#1E2A32', color: '#FFF', font: '600 15px/1 Inter, system-ui, sans-serif',
        padding: '0 20px', letterSpacing: '.01em',
      });
      document.body.appendChild(bar);
      document.body.style.marginTop = '44px';
    }
    bar.textContent = message;
  }, text);
}

/** Who is already holding an offer, so the clip can prove the new one is its own doing. */
const offersBefore = new Set((await json('/api/staff/waitlist')).filter((e) => e.status === 'OFFERED').map((e) => e.id));

const slot = await findMorningSlot();
const appointment = await json('/api/appointments', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    treatmentTypeId: CLEANING,
    practitionerId: slot.practitionerId,
    startTime: slot.startTime,
    patient: { name: 'Henrik Dahl', phone: '+47 902 31 447', email: 'henrik.dahl@example.com' },
  }),
});
console.log(`Booked ${appointment.reference} at ${appointment.startTime}`);

await mkdir(OUT, { recursive: true });
const videoDir = await mkdtemp(join(tmpdir(), 'dentline-demo-'));
const browser = await chromium.launch();

// Warm the services and the bundle up outside the recording, so the clip does not open on a
// loading skeleton while the JVM reaches its stride.
const warmUp = await browser.newContext({ viewport: { width: 1280, height: 800 } });
warmUp.setDefaultTimeout(60_000);
warmUp.setDefaultNavigationTimeout(60_000);
const warmUpPage = await warmUp.newPage();
for (const url of [`${APP}/appointments/${appointment.id}`, `${APP}/staff/waitlist`]) {
  await warmUpPage.goto(url, { waitUntil: 'domcontentloaded' });
  await warmUpPage.waitForTimeout(2500);
}
await warmUp.close();

const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  recordVideo: { dir: videoDir, size: { width: 1280, height: 800 } },
});
context.setDefaultTimeout(60_000);
context.setDefaultNavigationTimeout(60_000);
const page = await context.newPage();

// Waiting for the content the clip is about, rather than for the network to fall quiet.
await page.goto(`${APP}/appointments/${appointment.id}`, { waitUntil: 'domcontentloaded' });
await page.evaluate(() => document.fonts.ready);
await page.getByRole('button', { name: 'Cancel appointment' }).waitFor();
await caption(page, 'A patient opens the appointment from their reminder link');
await page.waitForTimeout(2400);

await caption(page, 'They cancel it');
await page.getByRole('button', { name: 'Cancel appointment' }).click();
await page.waitForTimeout(1500);
await page.getByRole('button', { name: 'Yes, cancel' }).click();
await page.waitForTimeout(2600); // the cancelled panel and the toast

await caption(page, 'booking-service publishes CANCELLED to Kafka once the transaction commits');

// Wait for the matcher to actually do its work before cutting to the clinic's screen. Others may
// already be holding offers from the seed, so look for one that was not there a moment ago.
const deadline = Date.now() + 30_000;
let winner;
while (!winner && Date.now() < deadline) {
  winner = (await json('/api/staff/waitlist')).find((e) => e.status === 'OFFERED' && !offersBefore.has(e.id));
  if (!winner) await page.waitForTimeout(500);
}
if (!winner) throw new Error('The matcher made no new offer within 30s');
console.log(`Matcher offered the freed slot to ${winner.patient.name}`);

// Client-side navigation rather than a reload, so the clip cuts to the clinic's screen without a
// white flash and the caption survives.
await caption(page, 'The waitlist matcher consumes it and offers the freed slot to the next match');
await page.evaluate(() => {
  window.history.pushState({}, '', '/staff/waitlist');
  window.dispatchEvent(new PopStateEvent('popstate'));
});
// The winner's own row has to show the offer, not merely some row that already had one.
await page.getByText(new RegExp(`Offer sent.*${winner.patient.name}|${winner.patient.name}`)).first().waitFor();
await page.waitForFunction(
  (name) => {
    const cell = [...document.querySelectorAll('span')].find((el) => el.textContent?.trim() === name);
    return cell?.closest('div[style*="grid"]')?.textContent?.includes('Offer sent') ?? false;
  },
  winner.patient.name,
  { timeout: 30_000 },
);
await page.waitForTimeout(2500);

await caption(page, 'The offer holds the slot for 15 minutes, counting down from the server deadline');
await page.waitForTimeout(5000); // let the countdown tick visibly

await context.close();
await browser.close();

// The clip is only honest if the offer on screen is the one this cancellation produced.
const fresh = (await json('/api/staff/waitlist')).filter((e) => e.status === 'OFFERED' && !offersBefore.has(e.id));
if (fresh.length === 0) {
  throw new Error(
    'The cancellation produced no new offer, so the clip would be showing a stale one. Reset the ' +
    'demo data first: docker compose down -v && docker compose up -d. The matcher refuses to ' +
    'offer the same slot to someone who has already turned it down, so repeated runs exhaust it.',
  );
}
for (const entry of fresh) {
  const heldFor = Math.round((Date.parse(entry.offer.expiresAt) - Date.now()) / 60_000);
  console.log(`New offer: ${entry.patient.name} got ${entry.offer.startTime}, held ${heldFor} more minutes`);
}

const [recorded] = (await readdir(videoDir)).filter((f) => f.endsWith('.webm'));
const webm = join(videoDir, recorded);
const gif = join(OUT, 'waitlist-offer.gif');
const filters = 'fps=10,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=3';
// -ss trims the frame or two before the first paint.
await run(ffmpeg, ['-y', '-ss', '0.6', '-i', webm, '-vf', filters, '-loop', '0', gif]);
await rm(videoDir, { recursive: true, force: true });
console.log(`${gif} written`);
