// Unit tests for the wake function: node --test deploy/azure/wake.test.js
// Azure is replaced by a fake fetch, so what is tested is the decision logic: when a start is
// requested, when the visitor is sent on, and what is refused.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const VM = '/subscriptions/s/resourceGroups/artha-rg/providers/Microsoft.Compute/virtualMachines/artha';
const APP = 'https://app.example.test';

process.env.VM_RESOURCE_ID = VM;
process.env.APP_URL = APP + '/';
process.env.IDENTITY_ENDPOINT = 'http://identity.local/token';
process.env.IDENTITY_HEADER = 'header-value';

const wake = require('./wake/open/index.js');

/** Installs a fake Azure and application, and records what the function asked of them. */
function world({ power, app = 'down', startStatus = 202 }) {
  const calls = [];
  wake.resetForTests();
  global.fetch = async (url, init = {}) => {
    const method = init.method || 'GET';
    const target = String(url);
    calls.push({ method, url: target, headers: init.headers || {} });
    if (target.startsWith(process.env.IDENTITY_ENDPOINT)) {
      return Response.json({ access_token: 'token-1', expires_on: String(Math.floor(Date.now() / 1000) + 3600) });
    }
    if (target.startsWith(`https://management.azure.com${VM}/instanceView`)) {
      return Response.json({ statuses: [{ code: 'ProvisioningState/succeeded' }, { code: `PowerState/${power}` }] });
    }
    if (target.startsWith(`https://management.azure.com${VM}/start`)) {
      return new Response(null, { status: startStatus });
    }
    if (target.startsWith(APP + '/')) {
      if (app === 'down') throw new TypeError('fetch failed');
      if (target === `${APP}/v1/loans`) return new Response('', { status: app === 'up' ? 401 : 502 });
      return new Response('<!doctype html>', { status: 200 });
    }
    throw new Error(`unexpected request to ${target}`);
  };
  return calls;
}

const fromPage = { 'x-wake': '1' };
const logged = [];
const call = (method, path, headers = {}) => wake.handle(method, path, headers, (line) => logged.push(line));
const answer = async (path) => JSON.parse((await call('POST', path, fromPage)).body);
const starts = (calls) => calls.filter((c) => c.method === 'POST' && c.url.includes('/start'));
const visits = (calls) => calls.filter((c) => c.url === `${APP}/wake-visit`);

test('asking for the status never starts the VM', async () => {
  for (const power of ['deallocated', 'stopped', 'deallocating', 'starting']) {
    const calls = world({ power });
    assert.deepEqual(await answer('/status'), { state: power, appUp: false });
    assert.equal(starts(calls).length, 0, power);
    assert.equal(calls.some((c) => c.url.startsWith(APP)), false, 'a VM that is not running is not probed');
  }
});

test('the status of a running VM says whether the application answers, without recording a visit', async () => {
  for (const [app, appUp] of [['down', false], ['starting', false], ['up', true]]) {
    const calls = world({ power: 'running', app });
    assert.deepEqual(await answer('/status'), { state: 'running', appUp });
    assert.equal(visits(calls).length, 0);
  }
});

test('a deallocated VM is started and the visitor is told to wait', async () => {
  const calls = world({ power: 'deallocated' });
  assert.deepEqual(await answer('/wake'), { state: 'starting', appUp: false });
  assert.equal(starts(calls).length, 1);
  assert.equal(starts(calls)[0].headers.Authorization, 'Bearer token-1');
});

test('a VM that was shut down from the inside is started too', async () => {
  const calls = world({ power: 'stopped' });
  assert.equal((await answer('/wake')).state, 'starting');
  assert.equal(starts(calls).length, 1);
});

test('a VM that is already starting, or still going down, is left alone', async () => {
  for (const power of ['starting', 'deallocating', 'stopping']) {
    const calls = world({ power });
    assert.deepEqual(await answer('/wake'), { state: power, appUp: false });
    assert.equal(starts(calls).length, 0, power);
  }
});

test('a running VM whose application does not answer yet keeps the visitor waiting', async () => {
  for (const app of ['down', 'starting']) {
    const calls = world({ power: 'running', app });
    assert.deepEqual(await answer('/wake'), { state: 'running', appUp: false });
    assert.equal(starts(calls).length, 0);
    assert.equal(visits(calls).length, 0, 'no visit is recorded before there is something to visit');
  }
});

test('once the application answers, the visit is recorded and the visitor is sent on', async () => {
  const calls = world({ power: 'running', app: 'up' });
  assert.deepEqual(await answer('/wake'), { state: 'running', appUp: true });
  assert.equal(visits(calls).length, 1);
  for (const request of calls.filter((c) => c.url.startsWith(APP))) {
    assert.equal(request.headers['User-Agent'], 'arthadhruva-waker');
  }
});

test('a start that is already in progress elsewhere is not an error', async () => {
  world({ power: 'deallocated', startStatus: 409 });
  assert.equal((await call('POST', '/wake', fromPage)).status, 200);
});

test('a failure talking to Azure is reported without detail', async () => {
  world({ power: 'deallocated', startStatus: 403 });
  const res = await call('POST', '/wake', fromPage);
  assert.equal(res.status, 502);
  assert.deepEqual(JSON.parse(res.body), { state: 'error', appUp: false });
  assert.match(logged.at(-1), /starting the VM answered 403/);
});

test('the access token is fetched once and reused', async () => {
  const calls = world({ power: 'running', app: 'up' });
  await answer('/status');
  await answer('/wake');
  assert.equal(calls.filter((c) => c.url.startsWith(process.env.IDENTITY_ENDPOINT)).length, 1);
});

test('nothing but the page itself can ask', async () => {
  const calls = world({ power: 'deallocated' });
  for (const path of ['/wake', '/status']) {
    assert.equal((await call('POST', path, {})).status, 403);
    assert.equal((await call('GET', path, fromPage)).status, 405);
  }
  assert.equal(calls.length, 0, 'Azure was not contacted');
});

test('opening the page costs nothing: it contacts neither Azure nor the application', async () => {
  const calls = world({ power: 'deallocated' });
  for (const path of ['/', '/open']) {
    const res = await call('GET', path);
    assert.equal(res.status, 200);
    assert.match(res.headers['Content-Type'], /text\/html/);
    assert.ok(res.body.includes(`var APP = "${APP}";`), 'the page knows where to send the visitor');
    assert.ok(res.body.includes('<button id="start" type="button" hidden>'), 'starting is a button, not automatic');
    assert.match(res.headers['Content-Security-Policy'], /default-src 'none'/);
    assert.match(res.headers['X-Robots-Tag'], /noindex/);
  }
  assert.equal((await call('HEAD', '/')).body, '');
  assert.equal(calls.length, 0);
});

test('crawlers are told to stay away, and unknown paths are not found', async () => {
  world({ power: 'deallocated' });
  assert.match((await call('GET', '/robots.txt')).body, /Disallow: \//);
  assert.equal((await call('GET', '/wp-login.php')).status, 404);
  assert.equal((await call('POST', '/')).status, 404);
});
