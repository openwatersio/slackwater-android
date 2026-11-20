import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyAssets, requireSupportedNode } from './sync-upstream.mjs';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
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
