import assert from 'node:assert/strict';
import test from 'node:test';
import { playAccessToken, uploadBundle } from './play-upload.mjs';
import { versionCode } from './version-code.mjs';

const PROVIDER = 'projects/123/locations/global/workloadIdentityPools/github/providers/slackwater-android';
const APP = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/io.example.app';

function fakeFetch(routes) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, method: init.method, headers: init.headers, body: init.body });
    const route = routes.find(([method, prefix]) => method === init.method && url.startsWith(prefix));
    assert.ok(route, `unexpected ${init.method} ${url}`);
    const [, , status, body] = route;
    return { ok: status < 400, status, text: async () => JSON.stringify(body) };
  };
  return { fetchImpl, calls };
}

const upload = (fetchImpl, overrides = {}) => uploadBundle({
  fetchImpl, token: 'play-token', packageName: 'io.example.app', track: 'internal', status: 'completed', releaseName: '26112637 (abc1234)', bundle: Buffer.from('aab'), expectedVersionCode: '26112637', ...overrides,
});

test('version codes rise through a day and across days, and fit Play\'s cap', () => {
  assert.equal(versionCode(new Date('2026-11-26T09:23:00Z')), 26112637);
  assert.equal(versionCode(new Date('2026-11-26T00:00:00Z')), 26112600);
  assert.equal(versionCode(new Date('2026-11-26T23:59:59Z')), 26112695);
  assert.ok(versionCode(new Date('2026-11-27T00:00:00Z')) > versionCode(new Date('2026-11-26T23:59:59Z')));
  assert.ok(versionCode(new Date('2027-01-01T00:00:00Z')) > versionCode(new Date('2026-12-31T23:59:59Z')));
  assert.ok(versionCode(new Date('2099-12-31T23:59:59Z')) < 2_100_000_000);
});

test('trades the GitHub identity for a Play-scoped service account token', async () => {
  const { fetchImpl, calls } = fakeFetch([
    ['GET', 'https://github.example/token', 200, { value: 'github-jwt' }],
    ['POST', 'https://sts.googleapis.com/v1/token', 200, { access_token: 'federated' }],
    ['POST', 'https://iamcredentials.googleapis.com/', 200, { accessToken: 'play-token' }],
  ]);
  const masked = [];
  const env = { PLAY_WORKLOAD_IDENTITY_PROVIDER: PROVIDER, PLAY_SERVICE_ACCOUNT: 'play@example.iam.gserviceaccount.com', ACTIONS_ID_TOKEN_REQUEST_URL: 'https://github.example/token?api-version=2', ACTIONS_ID_TOKEN_REQUEST_TOKEN: 'runner' };
  assert.equal(await playAccessToken({ env, fetchImpl, mask: (secret) => masked.push(secret) }), 'play-token');
  assert.deepEqual(masked, ['github-jwt', 'federated', 'play-token']);
  assert.equal(calls[0].url, `https://github.example/token?api-version=2&audience=${encodeURIComponent(`https://iam.googleapis.com/${PROVIDER}`)}`);
  const exchange = JSON.parse(calls[1].body);
  assert.equal(exchange.audience, `//iam.googleapis.com/${PROVIDER}`);
  assert.equal(exchange.subjectToken, 'github-jwt');
  assert.equal(calls[1].headers.authorization, undefined);
  assert.equal(calls[2].url, 'https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/play%40example.iam.gserviceaccount.com:generateAccessToken');
  assert.equal(calls[2].headers.authorization, 'Bearer federated');
  assert.deepEqual(JSON.parse(calls[2].body).scope, ['https://www.googleapis.com/auth/androidpublisher']);
});

test('names the missing setting or permission', async () => {
  const { fetchImpl } = fakeFetch([]);
  await assert.rejects(playAccessToken({ env: {}, fetchImpl }), /PLAY_WORKLOAD_IDENTITY_PROVIDER is not set/);
  await assert.rejects(playAccessToken({ env: { PLAY_WORKLOAD_IDENTITY_PROVIDER: PROVIDER, PLAY_SERVICE_ACCOUNT: 'a@b' }, fetchImpl }), /id-token: write/);
});

test('uploads, assigns the track and commits in one edit', async () => {
  const { fetchImpl, calls } = fakeFetch([
    ['POST', `${APP}/edits/7:commit`, 200, { id: '7' }],
    ['POST', `${APP}/edits`, 200, { id: '7' }],
    ['POST', 'https://androidpublisher.googleapis.com/upload/', 200, { versionCode: 26112637, sha256: 'abc' }],
    ['PUT', `${APP}/edits/7/tracks/internal`, 200, {}],
  ]);
  assert.deepEqual(await upload(fetchImpl), { versionCode: 26112637, sha256: 'abc' });
  assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
    `POST ${APP}/edits`,
    'POST https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/io.example.app/edits/7/bundles?uploadType=media',
    `PUT ${APP}/edits/7/tracks/internal`,
    `POST ${APP}/edits/7:commit`,
  ]);
  assert.deepEqual(JSON.parse(calls[2].body), { track: 'internal', releases: [{ versionCodes: ['26112637'], status: 'completed', name: '26112637 (abc1234)' }] });
  assert.ok(calls.every((call) => call.headers.authorization === 'Bearer play-token'));
});

test('explains that the first upload is made in Play Console', async () => {
  const { fetchImpl, calls } = fakeFetch([['POST', `${APP}/edits`, 404, { error: { message: 'Package not found: io.example.app.' } }]]);
  await assert.rejects(upload(fetchImpl), /Package not found[\s\S]*upload the first bundle in Play Console/);
  assert.equal(calls.length, 1);
});

test('explains a service account without access', async () => {
  const { fetchImpl } = fakeFetch([['POST', `${APP}/edits`, 403, { error: { message: 'The caller does not have permission' } }]]);
  await assert.rejects(upload(fetchImpl), /Users and permissions/);
});

test('never uploads to production or open testing', async () => {
  const { fetchImpl, calls } = fakeFetch([]);
  for (const track of ['production', 'wear:production', 'beta', 'Beta']) {
    await assert.rejects(upload(fetchImpl, { track }), /only publishes to internal and closed testing tracks/);
  }
  assert.equal(calls.length, 0);
});

test('stops when Play answers 200 without an edit id or a versionCode', async () => {
  const noEdit = fakeFetch([['POST', `${APP}/edits`, 200, null]]);
  await assert.rejects(upload(noEdit.fetchImpl), /held no edit id/);
  assert.equal(noEdit.calls.length, 1);
  const noCode = fakeFetch([
    ['POST', `${APP}/edits`, 200, { id: '7' }],
    ['POST', 'https://androidpublisher.googleapis.com/upload/', 200, {}],
    ['DELETE', `${APP}/edits/7`, 204, null],
  ]);
  await assert.rejects(upload(noCode.fetchImpl), /held no versionCode/);
  assert.equal(noCode.calls.at(-1).method, 'DELETE');
});

test('discards the edit and explains a draft app', async () => {
  const { fetchImpl, calls } = fakeFetch([
    ['POST', `${APP}/edits/7:commit`, 400, { error: { message: 'Only releases with status draft may be created on draft app.' } }],
    ['POST', `${APP}/edits`, 200, { id: '7' }],
    ['POST', 'https://androidpublisher.googleapis.com/upload/', 200, { versionCode: 26112637 }],
    ['PUT', `${APP}/edits/7/tracks/internal`, 200, {}],
    ['DELETE', `${APP}/edits/7`, 204, null],
  ]);
  await assert.rejects(upload(fetchImpl), /PLAY_RELEASE_STATUS variable to "draft"/);
  assert.equal(calls.at(-1).method, 'DELETE');
});

test('lists the app\'s tracks when the requested one is refused', async () => {
  const { fetchImpl } = fakeFetch([
    ['POST', `${APP}/edits`, 200, { id: '7' }],
    ['POST', 'https://androidpublisher.googleapis.com/upload/', 200, { versionCode: 26112637 }],
    ['PUT', `${APP}/edits/7/tracks/nightly`, 404, { error: { message: 'Track not found: nightly' } }],
    ['GET', `${APP}/edits/7/tracks`, 200, { tracks: [{ track: 'production' }, { track: 'alpha' }, { track: 'internal' }] }],
    ['DELETE', `${APP}/edits/7`, 204, null],
  ]);
  await assert.rejects(upload(fetchImpl, { track: 'nightly' }), /Tracks on this app: production, alpha, internal\./);
});

test('rejects a bundle whose versionCode is not the one the build was given', async () => {
  const { fetchImpl, calls } = fakeFetch([
    ['POST', `${APP}/edits`, 200, { id: '7' }],
    ['POST', 'https://androidpublisher.googleapis.com/upload/', 200, { versionCode: 1 }],
    ['DELETE', `${APP}/edits/7`, 204, null],
  ]);
  await assert.rejects(upload(fetchImpl), /Play read versionCode 1/);
  assert.equal(calls.at(-1).method, 'DELETE');
});
