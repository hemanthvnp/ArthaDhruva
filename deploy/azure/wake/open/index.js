// Wake on visit. The VM that runs ArthaDhruva is deallocated while nobody is using it (see
// ../../idle-stop.sh), which is what makes it cheap; this function is the part that is always there.
// It serves a small page. If the application is up, the page sends the visitor straight to it. If the
// VM is asleep, the page offers to start it, shows how long is left, and sends the visitor on once
// the application answers.
//
// Starting takes a click on purpose. Crawlers find any public address within the hour and load it,
// scripts and all; one of them must not be able to turn a paid machine on, and none of them clicks.
//
// The function acts with its own managed identity, whose only permissions are to read this one VM's
// power state and to start it (role "ArthaDhruva VM waker", created by ../../setup-wake.sh). There is
// no secret to leak and nothing else it could be made to do.
//
// No dependencies: the Functions host runs this file as it is (programming model v3, function.json).
'use strict';

const ARM = 'https://management.azure.com';
const API_VERSION = '2023-09-01';
const USER_AGENT = 'arthadhruva-waker';
/** Requested from the application when a visitor is sent in. The path is what the VM's idle check
 * looks for in the proxy's log, so the visitor is not switched off while still reading the sign-in
 * page. It is an ordinary page request; nothing on the application side knows about it. */
const VISIT_PATH = '/wake-visit';

let cachedToken = null;

function settings() {
  const vm = process.env.VM_RESOURCE_ID;
  const app = (process.env.APP_URL || '').replace(/\/+$/, '');
  if (!vm || !app) throw new Error('VM_RESOURCE_ID and APP_URL must be set');
  return { vm, app };
}

async function armToken() {
  if (cachedToken && cachedToken.expiresAt - 60_000 > Date.now()) return cachedToken.value;
  const url = `${process.env.IDENTITY_ENDPOINT}?resource=${encodeURIComponent(ARM + '/')}&api-version=2019-08-01`;
  const res = await fetch(url, { headers: { 'X-IDENTITY-HEADER': process.env.IDENTITY_HEADER }, signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`the identity endpoint answered ${res.status}`);
  const body = await res.json();
  cachedToken = { value: body.access_token, expiresAt: Number(body.expires_on) * 1000 };
  return cachedToken.value;
}

async function arm(method, action) {
  const { vm } = settings();
  return fetch(`${ARM}${vm}/${action}?api-version=${API_VERSION}`, {
    method,
    headers: { Authorization: `Bearer ${await armToken()}` },
    signal: AbortSignal.timeout(15_000),
  });
}

/** running, starting, stopping, stopped, deallocating, deallocated, or unknown. */
async function powerState() {
  const res = await arm('GET', 'instanceView');
  if (!res.ok) throw new Error(`reading the power state answered ${res.status}`);
  const statuses = (await res.json()).statuses || [];
  const power = statuses.find((s) => typeof s.code === 'string' && s.code.startsWith('PowerState/'));
  return power ? power.code.slice('PowerState/'.length) : 'unknown';
}

async function fromApp(path) {
  return fetch(`${settings().app}${path}`, {
    headers: { 'User-Agent': USER_AGENT },
    redirect: 'manual',
    signal: AbortSignal.timeout(4_000),
  });
}

/** The application is up when its API answers. Without a token that answer is 401; a proxy that
 * cannot reach the backend yet says 502, and a VM that is still booting does not answer at all. */
async function appIsUp() {
  try {
    const status = (await fromApp('/v1/loans')).status;
    return status === 401 || status === 200;
  } catch {
    return false;
  }
}

/** Where things stand. Never starts anything, so it is safe for the page to ask on load. */
async function status() {
  const state = await powerState();
  return { state, appUp: state === 'running' && (await appIsUp()) };
}

/** What a visitor who wants in gets: the VM is started if it is down, and if the application is
 * already up the visit is recorded so the VM stays up for them. Safe to repeat: a start is only
 * requested from the two states in which the VM is actually down. */
async function wake() {
  const state = await powerState();
  if (state === 'deallocated' || state === 'stopped') {
    const res = await arm('POST', 'start');
    // 409: another operation on the VM is already in progress, which a later poll will see through.
    if (!res.ok && res.status !== 409) throw new Error(`starting the VM answered ${res.status}`);
    return { state: 'starting', appUp: false };
  }
  if (state !== 'running' || !(await appIsUp())) return { state, appUp: false };
  await fromApp(VISIT_PATH).catch(() => {});
  return { state, appUp: true };
}

const PAGE_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-store',
  'X-Robots-Tag': 'noindex, nofollow',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy':
    "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};

function page(appUrl) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>ArthaDhruva</title>
<style>
  :root { color-scheme: light; }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px;
         background: #f4f2ed; color: #15130f; font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { width: 100%; max-width: 440px; background: #fff; border: 1px solid #e6e2d9; border-radius: 12px; padding: 32px 28px; }
  h1 { margin: 14px 0 2px; font: 600 28px/1.2 Georgia, "Times New Roman", serif; }
  p { margin: 0 0 12px; color: #6a665c; }
  #status { margin-top: 22px; color: #15130f; font-weight: 600; }
  .track { height: 4px; border-radius: 2px; background: #eeebe3; overflow: hidden; margin: 10px 0; }
  #bar { height: 100%; width: 0; background: #15130f; transition: width 1s linear; }
  .small { font-size: 14px; margin: 0; }
  button { margin: 6px 0 14px; padding: 11px 18px; border: 0; border-radius: 8px; background: #15130f; color: #fff;
           font: inherit; font-weight: 600; cursor: pointer; }
  button:hover { background: #34312a; }
  button:focus-visible, a:focus-visible { outline: 2px solid #15130f; outline-offset: 3px; }
  a { color: #15130f; }
  [hidden] { display: none; }
</style>
</head>
<body>
<main>
  <svg width="36" height="36" viewBox="0 0 32 32" aria-hidden="true">
    <rect x="3" y="17" width="7" height="12" rx="2" fill="#2f9e6b"/>
    <rect x="12.5" y="10" width="7" height="19" rx="2" fill="#e0a526"/>
    <rect x="22" y="3" width="7" height="26" rx="2" fill="#d64545"/>
  </svg>
  <h1>ArthaDhruva</h1>
  <p>Credit risk platform for mortgage lenders.</p>
  <p id="status" role="status" aria-live="polite">Checking whether the demo is running.</p>
  <button id="start" type="button" hidden>Start the demo</button>
  <div class="track" id="track" aria-hidden="true" hidden><div id="bar"></div></div>
  <p class="small" id="detail">&nbsp;</p>
  <noscript><p class="small">This page needs JavaScript to start the demo.</p></noscript>
</main>
<script>
(function () {
  var APP = ${JSON.stringify(appUrl)};
  var USUAL_SECONDS = 150;
  var status = document.getElementById('status');
  var detail = document.getElementById('detail');
  var start = document.getElementById('start');
  var track = document.getElementById('track');
  var bar = document.getElementById('bar');
  var startedAt = 0;
  var failures = 0;

  function ask(path) {
    return fetch(path, { method: 'POST', headers: { 'X-Wake': '1' }, cache: 'no-store' })
      .then(function (res) {
        // Read the body either way, so that a failed request is finished and not left hanging.
        return res.json().then(function (body) { if (!res.ok) throw new Error(String(res.status)); return body; });
      });
  }

  function paint() {
    if (!startedAt) return;
    var seconds = Math.round((Date.now() - startedAt) / 1000);
    bar.style.width = Math.min(96, (seconds / USUAL_SECONDS) * 100) + '%';
    detail.textContent = 'This usually takes about two minutes; ' + seconds + ' s so far.';
  }

  function describe(state) {
    if (state === 'running') return 'The server is up. Starting the application.';
    if (state === 'stopping' || state === 'deallocating') return 'The server was just going to sleep. Waking it again.';
    return 'Waking the server.';
  }

  function giveUp() {
    startedAt = 0;
    track.hidden = true;
    status.textContent = 'The demo could not be started.';
    detail.textContent = 'Try again in a few minutes, or open the application directly: ';
    var link = document.createElement('a');
    link.href = APP;
    link.textContent = APP;
    detail.appendChild(link);
  }

  function enter() {
    status.textContent = 'Ready.';
    bar.style.width = '100%';
    window.location.replace(APP);
  }

  // Asks for the VM to be started, or for the visit to be recorded, until the application answers.
  function waiting() {
    ask('/wake').then(function (now) {
      failures = 0;
      if (now.appUp) { enter(); return; }
      status.textContent = describe(now.state);
      paint();
      setTimeout(waiting, 4000);
    }).catch(function () {
      failures += 1;
      if (failures >= 6) { giveUp(); return; }
      setTimeout(waiting, 5000);
    });
  }

  function begin() {
    start.hidden = true;
    track.hidden = false;
    startedAt = Date.now();
    status.textContent = 'Waking the server.';
    paint();
    waiting();
  }

  start.addEventListener('click', begin);
  setInterval(paint, 1000);

  ask('/status').then(function (now) {
    if (now.appUp || now.state === 'running' || now.state === 'starting') {
      // Already up, or already on its way up because someone else asked: nothing to decide.
      if (now.appUp) { status.textContent = 'Opening the application.'; waiting(); } else { begin(); }
      return;
    }
    status.textContent = 'The demo is asleep.';
    detail.textContent = 'It runs on a server that switches off when nobody is using it, which keeps it free. ' +
      'Starting it takes about two minutes.';
    start.hidden = false;
  }).catch(giveUp);
})();
</script>
</body>
</html>`;
}

function json(statusCode, body) {
  return { status: statusCode, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, body: JSON.stringify(body) };
}

async function handle(method, path, headers, log) {
  if (path === '/wake' || path === '/status') {
    if (method !== 'POST') return json(405, { error: 'POST only' });
    // Only this page sends the header. A browser will not let another site add it to a cross-origin
    // request without a preflight, which is never answered, so no third-party page can reach these
    // through its visitors; and crawlers that follow links do not send it either.
    if (headers['x-wake'] !== '1') return json(403, { error: 'not from the wake page' });
    try {
      return json(200, path === '/wake' ? await wake() : await status());
    } catch (error) {
      log(`${path} failed: ${error.message}`);
      return json(502, { state: 'error', appUp: false });
    }
  }
  if (method === 'GET' || method === 'HEAD') {
    if (path === '/' || path === '/open') {
      return { status: 200, headers: PAGE_HEADERS, body: method === 'HEAD' ? '' : page(settings().app) };
    }
    if (path === '/robots.txt') {
      return { status: 200, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'User-agent: *\nDisallow: /\n' };
    }
  }
  return { status: 404, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'Not found\n' };
}

module.exports = async function (context, req) {
  const path = '/' + String((req.params && req.params.path) || '').replace(/^\/+|\/+$/g, '');
  const headers = {};
  for (const [name, value] of Object.entries(req.headers || {})) headers[name.toLowerCase()] = value;
  context.res = await handle(String(req.method || 'GET').toUpperCase(), path, headers, (line) => context.log.error(line));
};

// For the unit tests (deploy/azure/wake.test.js).
module.exports.handle = handle;
module.exports.resetForTests = () => { cachedToken = null; };
