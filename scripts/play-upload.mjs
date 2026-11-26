#!/usr/bin/env node
import { appendFile, readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const PUBLISHER = 'https://androidpublisher.googleapis.com';
const SETUP = 'docs/releasing.md';

export class UploadError extends Error {}

async function request(fetchImpl, step, url, { method = 'GET', token, json, body, headers = {}, timeoutMs = 60_000 } = {}) {
  const init = { method, headers: { ...headers }, signal: AbortSignal.timeout(timeoutMs) };
  if (token) init.headers.authorization = `Bearer ${token}`;
  if (json) {
    init.headers['content-type'] = 'application/json';
    init.body = JSON.stringify(json);
  } else if (body) init.body = body;
  let response;
  try {
    response = await fetchImpl(url, init);
  } catch (cause) {
    throw new UploadError(`${step}: no response (${cause.message})`);
  }
  const text = await response.text();
  let parsed = {};
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch {
    parsed = {};
  }
  if (parsed === null || typeof parsed !== 'object') parsed = {};
  if (!response.ok) {
    const detail = parsed.error?.message ?? parsed.error_description ?? parsed.message ?? text.slice(0, 300);
    const error = new UploadError(`${step}: HTTP ${response.status}: ${detail}`);
    error.status = response.status;
    error.detail = String(detail);
    throw error;
  }
  return parsed;
}

function withHint(error, hint) {
  const hinted = new UploadError(`${error.message}\n${hint}`);
  hinted.status = error.status;
  return hinted;
}

function required(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new UploadError(`${name} is not set. See ${SETUP}.`);
  return value;
}

// Exchanges this job's GitHub identity for a short-lived token of the Play service account, so no Google key is stored.
export async function playAccessToken({ env, fetchImpl, mask = () => {} }) {
  const provider = required(env, 'PLAY_WORKLOAD_IDENTITY_PROVIDER');
  const serviceAccount = required(env, 'PLAY_SERVICE_ACCOUNT');
  if (!/^projects\/\d+\/locations\/global\/workloadIdentityPools\/[^/]+\/providers\/[^/]+$/.test(provider)) {
    throw new UploadError(`PLAY_WORKLOAD_IDENTITY_PROVIDER must look like projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>. See ${SETUP}.`);
  }
  const tokenUrl = env.ACTIONS_ID_TOKEN_REQUEST_URL;
  const tokenBearer = env.ACTIONS_ID_TOKEN_REQUEST_TOKEN;
  if (!tokenUrl || !tokenBearer) throw new UploadError('GitHub offered no identity token: the job needs the `id-token: write` permission.');

  const audience = `https://iam.googleapis.com/${provider}`;
  const github = await request(fetchImpl, 'GitHub identity token', `${tokenUrl}&audience=${encodeURIComponent(audience)}`, { token: tokenBearer });
  mask(github.value);

  let federated;
  try {
    federated = await request(fetchImpl, 'Google token exchange', 'https://sts.googleapis.com/v1/token', {
      method: 'POST',
      json: {
        grantType: 'urn:ietf:params:oauth:grant-type:token-exchange',
        audience: `//iam.googleapis.com/${provider}`,
        scope: 'https://www.googleapis.com/auth/cloud-platform',
        requestedTokenType: 'urn:ietf:params:oauth:token-type:access_token',
        subjectTokenType: 'urn:ietf:params:oauth:token-type:jwt',
        subjectToken: github.value,
      },
    });
  } catch (error) {
    throw withHint(error, `Google did not accept this workflow's identity. Check PLAY_WORKLOAD_IDENTITY_PROVIDER and the provider's attribute condition (${SETUP}).`);
  }
  mask(federated.access_token);

  let impersonated;
  try {
    impersonated = await request(fetchImpl, 'Service account token', `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encodeURIComponent(serviceAccount)}:generateAccessToken`, {
      method: 'POST',
      token: federated.access_token,
      json: { scope: ['https://www.googleapis.com/auth/androidpublisher'], lifetime: '900s' },
    });
  } catch (error) {
    throw withHint(error, `The workflow may not act as ${serviceAccount}. Check its Workload Identity User binding and that the IAM Service Account Credentials API is enabled (${SETUP}).`);
  }
  mask(impersonated.accessToken);
  return impersonated.accessToken;
}

export async function uploadBundle({ fetchImpl, token, packageName, track, status, releaseName, bundle, expectedVersionCode, log = () => {} }) {
  // Production rollout is a separate, deliberate release action; this uploader only reaches testing tracks.
  // Open testing is public too: anyone can join it, so an unreviewed build has no place there either.
  if (/(^|:)(production|beta)$/i.test(track)) throw new UploadError(`Refusing to upload to "${track}": this uploader only publishes to internal and closed testing tracks.`);
  if (!['completed', 'draft'].includes(status)) throw new UploadError(`Release status must be "completed" or "draft", not "${status}".`);

  const app = `${PUBLISHER}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}`;
  let edit;
  try {
    edit = await request(fetchImpl, 'Open a Play edit', `${app}/edits`, { method: 'POST', token, json: {} });
  } catch (error) {
    if (error.status === 404) {
      throw withHint(error, `Google Play has no app the API can edit for ${packageName}. The Publisher API cannot create an app's first release: upload the first bundle in Play Console, then run this again (${SETUP}, "Make the first upload by hand").`);
    }
    if (error.status === 401 || error.status === 403) {
      throw withHint(error, `The service account cannot edit ${packageName}. Invite it under Users and permissions in Play Console with "Release apps to testing tracks" for this app, and check that the Google Play Developer API is enabled in its Cloud project (${SETUP}).`);
    }
    throw error;
  }
  // A 200 that is not Play's JSON, such as a proxy's page, must not become a request to edits/undefined.
  if (!edit.id) throw new UploadError('Open a Play edit: the answer held no edit id.');
  const edits = `${app}/edits/${encodeURIComponent(edit.id)}`;

  try {
    log(`Uploading ${bundle.byteLength} bytes to Google Play.`);
    let uploaded;
    try {
      uploaded = await request(fetchImpl, 'Upload the bundle', `${PUBLISHER}/upload/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/edits/${encodeURIComponent(edit.id)}/bundles?uploadType=media`, {
        method: 'POST',
        token,
        body: bundle,
        headers: { 'content-type': 'application/octet-stream' },
        timeoutMs: 600_000,
      });
    } catch (error) {
      if (/already been used/i.test(error.detail ?? '')) throw withHint(error, 'Play has already seen this versionCode. Start a new run of the workflow, which builds with a new one; re-running this failed job would upload the same bundle again.');
      throw error;
    }
    if (uploaded.versionCode == null) throw new UploadError('Upload the bundle: the answer held no versionCode.');
    if (expectedVersionCode && String(uploaded.versionCode) !== String(expectedVersionCode)) {
      throw new UploadError(`Play read versionCode ${uploaded.versionCode} from the bundle, but the build was given ${expectedVersionCode}.`);
    }

    const release = { versionCodes: [String(uploaded.versionCode)], status };
    if (releaseName) release.name = releaseName;
    try {
      await request(fetchImpl, `Assign the bundle to the ${track} track`, `${edits}/tracks/${encodeURIComponent(track)}`, { method: 'PUT', token, json: { track, releases: [release] } });
      await request(fetchImpl, 'Commit the Play edit', `${edits}:commit`, { method: 'POST', token });
    } catch (error) {
      if (/draft app/i.test(error.detail ?? '')) {
        throw withHint(error, `Play still treats this app as a draft and accepts only draft releases. Set the PLAY_RELEASE_STATUS variable to "draft" and roll each upload out from Play Console (${SETUP}).`);
      }
      const tracks = await request(fetchImpl, 'List tracks', `${edits}/tracks`, { token }).catch(() => null);
      const names = tracks?.tracks?.map((entry) => entry.track).join(', ');
      throw names ? withHint(error, `Tracks on this app: ${names}.`) : error;
    }
    return { versionCode: uploaded.versionCode, sha256: uploaded.sha256 };
  } catch (error) {
    // An abandoned edit would otherwise stay open until it expires.
    await request(fetchImpl, 'Discard the Play edit', edits, { method: 'DELETE', token }).catch(() => {});
    throw error;
  }
}

async function main() {
  const env = process.env;
  const [bundlePath] = process.argv.slice(2);
  if (!bundlePath) throw new UploadError('Usage: node scripts/play-upload.mjs <bundle.aab>');
  const track = required(env, 'PLAY_TRACK');
  const status = env.PLAY_RELEASE_STATUS?.trim() || 'completed';
  const packageName = required(env, 'PLAY_PACKAGE_NAME');
  // Registering the tokens with the runner keeps them out of the log even if a later step prints them.
  const mask = (secret) => {
    if (env.GITHUB_ACTIONS && secret) console.log(`::add-mask::${secret}`);
  };
  const token = await playAccessToken({ env, fetchImpl: fetch, mask });
  const result = await uploadBundle({
    fetchImpl: fetch,
    token,
    packageName,
    track,
    status,
    releaseName: env.PLAY_RELEASE_NAME?.trim(),
    bundle: await readFile(bundlePath),
    expectedVersionCode: required(env, 'PLAY_VERSION_CODE'),
    log: console.log,
  });
  const summary = `Uploaded versionCode ${result.versionCode} to the ${track} track as a ${status} release.`;
  console.log(summary);
  if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `${summary}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    const message = error instanceof UploadError ? error.message : (error.stack ?? String(error));
    console.error(message);
    // The annotation puts the reason on the run page; %0A is a newline in a workflow command.
    if (process.env.GITHUB_ACTIONS) console.log(`::error title=Play upload failed::${message.replaceAll('%', '%25').replaceAll('\n', '%0A')}`);
    process.exit(1);
  });
}
