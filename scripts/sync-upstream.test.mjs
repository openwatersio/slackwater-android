import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyAssets, requireSupportedNode } from './sync-upstream.mjs';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
test('rejects changed and unexpected packaged files', async () => {
 const dir = await mkdtemp(join(tmpdir(), 'slackwater-assets-'));
 try {
  await writeFile(join(dir, 'station.json'), 'identity');
  const entries = [{name:'station.json', sha256:createHash('sha256').update('identity').digest('hex')}];
  await verifyAssets(dir, entries);
  await writeFile(join(dir, 'station.json'), 'changed');
  await assert.rejects(verifyAssets(dir, entries), /hash/);
  await writeFile(join(dir, 'station.json'), 'identity');
  await writeFile(join(dir, 'user-model.json'), 'private');
  await assert.rejects(verifyAssets(dir, entries), /Unexpected/);
 } finally { await rm(dir,{recursive:true,force:true}); }
});

import { cp, mkdir, symlink, realpath, access } from 'node:fs/promises';
import { spawnSync, execFileSync } from 'node:child_process';

async function sandbox() {
 const root = await realpath(await mkdtemp(join(tmpdir(), 'slackwater-sync-test-')));
 await mkdir(join(root, 'scripts'));
 await cp(new URL('./sync-upstream.mjs', import.meta.url), join(root, 'scripts/sync-upstream.mjs'));
 return root;
}
const run = (root, args = [], env = {}) => spawnSync(process.execPath, [join(root, 'scripts/sync-upstream.mjs'), ...args], {encoding:'utf8', env:{...process.env,...env}});

test('CLI runs when invoked through a symlink', async () => {
 const root = await sandbox();
 try {
  await symlink(join(root, 'scripts'), join(root, 'linked'), 'dir');
  const result = spawnSync(process.execPath, [join(root,'linked/sync-upstream.mjs'),'--unknown'], {encoding:'utf8'});
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Usage/);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('failed fetch does not publish an incomplete checkout', async () => {
 const root = await sandbox();
 try {
  await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:'0'.repeat(40)}}}));
  const result=run(root,[],{GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:`url.file://${root}/missing/.insteadOf`,GIT_CONFIG_VALUE_0:'https://github.com/'});
  assert.equal(result.status,1);
  await assert.rejects(access(join(root,'vendor/fixture')));
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('rejects untracked source but permits ignored build output', async () => {
 const root=await sandbox();
 try {
  const repo=join(root,'vendor/fixture');
  await mkdir(repo,{recursive:true});
  const git=(...args)=>execFileSync('git',args,{cwd:repo,encoding:'utf8'}).trim();
  git('init','--quiet');
  await writeFile(join(repo,'.gitignore'),'build/\n');
  git('add','.gitignore');
  git('-c','user.name=Fixture','-c','user.email=fixture@example.test','commit','--quiet','-m','fixture');
  await mkdir(join(repo,'kotlin/.kotlin/errors'),{recursive:true});
  await writeFile(join(repo,'kotlin/.kotlin/errors/compiler.log'),'generated');
  await mkdir(join(repo,'build')); await writeFile(join(repo,'build/output'),'ignored');
  await mkdir(join(root,'app/src/main/assets/slackwater'),{recursive:true});
  await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:git('rev-parse','HEAD')}},resources:{assets:[]}}));
  assert.equal(run(root,['--check']).status,0);
  await writeFile(join(repo,'Local.kt'),'untracked source');
  const result=run(root,['--check']);
  assert.equal(result.status,1);
  assert.match(result.stderr,/Modified source/);
 } finally { await rm(root,{recursive:true,force:true}); }
});

async function movedPin() {
 const root = await sandbox();
 const remote = join(root, 'remotes', 'fixture', 'source.git');
 await mkdir(remote, {recursive:true});
 const upstream = (...args) => execFileSync('git', args, {cwd:remote, encoding:'utf8'}).trim();
 const commit = message => upstream('-c','user.name=Fixture','-c','user.email=fixture@example.test','-c','commit.gpgsign=false','commit','--quiet','-m',message);
 upstream('init', '--quiet');
 await writeFile(join(remote, 'source.txt'), 'first');
 upstream('add', '.'); commit('first');
 const first = upstream('rev-parse','HEAD');
 await writeFile(join(remote, 'source.txt'), 'second');
 upstream('add', '.'); commit('second');
 const second = upstream('rev-parse','HEAD');
 await mkdir(join(root,'app/src/main/assets/slackwater'),{recursive:true});
 const pin = sha => writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:sha}},resources:{repository:'fixture/source',commit:sha,assets:[]}}));
 const env = {GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:`url.file://${root}/remotes/.insteadOf`,GIT_CONFIG_VALUE_0:'https://github.com/'};
 const checkout = join(root,'vendor/fixture');
 const head = () => execFileSync('git',['rev-parse','HEAD'],{cwd:checkout,encoding:'utf8'}).trim();
 await pin(first);
 assert.equal(run(root, [], env).status, 0);
 assert.equal(head(), first);
 await pin(second);
 return { root, env, checkout, head, first, second };
}

test('moves a clean checkout to a changed pin', async () => {
 const { root, env, head, second } = await movedPin();
 try {
  const result = run(root, [], env);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(head(), second);
  assert.match(result.stdout, /Moved vendor\/fixture from [a-f0-9]{40} to [a-f0-9]{40}\./);
  assert.equal(run(root, ['--check']).status, 0);
 } finally { await rm(root,{recursive:true,force:true}); }
});

const inCheckout = checkout => (...args) => execFileSync('git', args, {cwd:checkout, encoding:'utf8'}).trim();
const author = ['-c','user.name=Fixture','-c','user.email=fixture@example.test','-c','commit.gpgsign=false'];

test('moves again each time the pin changes', async () => {
 const { root, env, head, second } = await movedPin();
 try {
  assert.equal(run(root, [], env).status, 0);
  assert.equal(head(), second);
  // The checkout now sits on a commit sync fetched itself, which must still count as upstream's and not as local work.
  const remote = join(root, 'remotes', 'fixture', 'source.git');
  const upstream = inCheckout(remote);
  await writeFile(join(remote, 'source.txt'), 'third');
  upstream('add', '.'); upstream(...author,'commit','--quiet','-m','third');
  const third = upstream('rev-parse','HEAD');
  await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:third}},resources:{repository:'fixture/source',commit:third,assets:[]}}));
  const result = run(root, [], env);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(head(), third);
  // And back to an earlier pin, as a revert of the lock would ask.
  await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:second}},resources:{repository:'fixture/source',commit:second,assets:[]}}));
  const back = run(root, [], env);
  assert.equal(back.status, 0, back.stderr);
  assert.equal(head(), second);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('refuses to leave a commit made in the checkout that no branch keeps', async () => {
 const { root, env, checkout, head } = await movedPin();
 try {
  const git = inCheckout(checkout);
  await writeFile(join(checkout,'source.txt'),'local commit');
  git(...author,'commit','--quiet','-am','local');
  const local = head();
  const result = run(root, [], env);
  assert.equal(result.status, 1);
  assert.match(result.stderr, new RegExp(`Unsaved commits: fixture is at ${local}`));
  assert.equal(head(), local);
  // On a branch the commit is kept, so the move goes ahead and the branch stays.
  git('switch','--quiet','-c','keep');
  const moved = run(root, [], env);
  assert.equal(moved.status, 0, moved.stderr);
  assert.equal(git('rev-parse','keep'), local);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('does not write over an ignored file the new pin starts tracking', async () => {
 const root = await sandbox();
 try {
  const remote = join(root, 'remotes', 'fixture', 'source.git');
  await mkdir(remote, {recursive:true});
  const upstream = inCheckout(remote);
  upstream('init', '--quiet');
  await writeFile(join(remote, '.gitignore'), 'local.txt\n');
  upstream('add', '.'); upstream(...author,'commit','--quiet','-m','first');
  const first = upstream('rev-parse','HEAD');
  await writeFile(join(remote, 'local.txt'), 'upstream');
  upstream('add', '--force', 'local.txt'); upstream(...author,'commit','--quiet','-m','second');
  const second = upstream('rev-parse','HEAD');
  await mkdir(join(root,'app/src/main/assets/slackwater'),{recursive:true});
  const pin = sha => writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:sha}},resources:{repository:'fixture/source',commit:first,assets:[]}}));
  const env = {GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:`url.file://${root}/remotes/.insteadOf`,GIT_CONFIG_VALUE_0:'https://github.com/'};
  await pin(first);
  assert.equal(run(root, [], env).status, 0);
  const checkout = join(root,'vendor/fixture');
  await writeFile(join(checkout,'local.txt'),'mine');
  await pin(second);
  assert.equal(run(root, [], env).status, 1);
  assert.equal(await readFile(join(checkout,'local.txt'),'utf8'), 'mine');
  assert.equal(inCheckout(checkout)('rev-parse','HEAD'), first);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('moves a full clone again and again without cutting its history', async () => {
 const root = await sandbox();
 try {
  const remote = join(root, 'remotes', 'fixture', 'source.git');
  await mkdir(remote, {recursive:true});
  const upstream = inCheckout(remote);
  const publish = async text => {
   await writeFile(join(remote, 'source.txt'), text);
   upstream('add', '.'); upstream(...author,'commit','--quiet','-m',text);
   return upstream('rev-parse','HEAD');
  };
  upstream('init', '--quiet');
  const first = await publish('first');
  // Cloned before the later commits exist, so each move has to fetch its pin.
  const checkout = join(root,'vendor/fixture');
  await mkdir(join(root,'vendor'));
  execFileSync('git',['clone','--quiet',`file://${remote}`,checkout]);
  const git = inCheckout(checkout);
  git('checkout','--quiet','--detach',first);
  await mkdir(join(root,'app/src/main/assets/slackwater'),{recursive:true});
  const env = {GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:`url.file://${root}/remotes/.insteadOf`,GIT_CONFIG_VALUE_0:'https://github.com/'};
  let count = 1;
  for (const text of ['second', 'third']) {
   const pin = await publish(text);
   await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:pin}},resources:{repository:'fixture/source',commit:pin,assets:[]}}));
   const result = run(root, [], env);
   assert.equal(result.status, 0, result.stderr);
   assert.equal(git('rev-parse','HEAD'), pin);
   assert.equal(git('rev-parse','--is-shallow-repository'), 'false');
   assert.equal(git('rev-list','--count','HEAD'), String(++count));
  }
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('a stash does not count as keeping a commit', async () => {
 const { root, env, checkout, head } = await movedPin();
 try {
  const git = inCheckout(checkout);
  await writeFile(join(checkout,'source.txt'),'local commit');
  git(...author,'commit','--quiet','-am','local');
  const local = head();
  await writeFile(join(checkout,'source.txt'),'more');
  git(...author,'stash','--quiet');
  const result = run(root, [], env);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Unsaved commits/);
  assert.equal(head(), local);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('--check fails when the iOS checkout Gradle reads is not at its pin', async () => {
 const { root, env, first, second } = await movedPin();
 try {
  assert.equal(run(root, [], env).status, 0);
  assert.equal(run(root, ['--check']).status, 0);
  await writeFile(join(root,'upstream.lock.json'),JSON.stringify({sources:{fixture:{repository:'fixture/source',commit:second}},resources:{repository:'fixture/source',commit:first,assets:[]}}));
  const result = run(root, ['--check']);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Wrong revision: slackwater-ios/);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('leaves a checkout with local changes at its old revision', async () => {
 const { root, env, checkout, head, first } = await movedPin();
 try {
  await writeFile(join(checkout,'source.txt'),'local work');
  const result = run(root, [], env);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Modified source: fixture is at [a-f0-9]{7}, not the pinned [a-f0-9]{7}/);
  assert.equal(head(), first);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('--check reports a changed pin without moving the checkout', async () => {
 const { root, head, first } = await movedPin();
 try {
  const result = run(root, ['--check']);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Wrong revision: fixture; run node scripts\/sync-upstream\.mjs/);
  assert.equal(head(), first);
 } finally { await rm(root,{recursive:true,force:true}); }
});

test('unsupported Node versions fail explicitly', () => {
 assert.throws(()=>requireSupportedNode('22.17.0'),/Node 24.2/);
 assert.throws(()=>requireSupportedNode('24.1.0'),/Node 24.2/);
 requireSupportedNode('24.2.0');
 requireSupportedNode('26.0.0');
});

test('fetches a separately pinned iOS documentation commit without changing the resource checkout', async () => {
 const root = await sandbox();
 try {
  const remote = join(root, 'remotes', 'fixture', 'ios.git');
  await mkdir(remote, {recursive:true});
  const git = (...args) => execFileSync('git', args, {cwd:remote, encoding:'utf8'}).trim();
  git('init', '--quiet');
  await writeFile(join(remote, 'catalog.txt'), 'translations');
  await writeFile(join(remote, 'asset.txt'), 'first');
  git('add', '.');
  git('-c','user.name=Fixture','-c','user.email=fixture@example.test','-c','commit.gpgsign=false','commit','--quiet','-m','docs');
  const docs = git('rev-parse','HEAD');
  await writeFile(join(remote, 'asset.txt'), 'second');
  git('add', 'asset.txt');
  git('-c','user.name=Fixture','-c','user.email=fixture@example.test','-c','commit.gpgsign=false','commit','--quiet','-m','assets');
  const resources = git('rev-parse','HEAD');
  await writeFile(join(root, 'upstream.lock.json'), JSON.stringify({sources:{}, productDocs:{repository:'fixture/ios',commit:docs}, resources:{repository:'fixture/ios',commit:resources,assets:[{name:'asset.txt',path:'asset.txt',sha256:createHash('sha256').update('second').digest('hex')}]}}));
  const env = {GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:`url.file://${root}/remotes/.insteadOf`,GIT_CONFIG_VALUE_0:'https://github.com/'};
  const result = run(root, [], env);
  assert.equal(result.status, 0, result.stderr);
  const checkout = join(root,'vendor/slackwater-ios');
  const shown = execFileSync('git',['show',`${docs}:catalog.txt`],{cwd:checkout,encoding:'utf8'});
  assert.equal(shown, 'translations');
  assert.equal(execFileSync('git',['rev-parse','HEAD'],{cwd:checkout,encoding:'utf8'}).trim(), resources);
  assert.equal(run(root,['--check']).status, 0);
 } finally { await rm(root,{recursive:true,force:true}); }
});
