import { readFile, readdir, mkdir, writeFile, rename, access, mkdtemp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join, resolve } from 'node:path';
export function requireSupportedNode(version) {
 const [major,minor]=version.split('.').map(Number);
 if(major<24 || (major===24 && minor<2)) throw Error('Node 24.2 or later is required');
}
requireSupportedNode(process.versions.node);
const root = fileURLToPath(new URL('../', import.meta.url));
const hash = data => createHash('sha256').update(data).digest('hex');
export async function verifyAssets(directory, entries) {
 const allowed = new Set(entries.map(e => e.name));
 for (const name of await readdir(directory)) if (!allowed.has(name)) throw Error(`Unexpected asset: ${name}`);
 for (const e of entries) if (hash(await readFile(join(directory,e.name))) !== e.sha256) throw Error(`Asset hash mismatch: ${e.name}`);
}
const git = (cwd,...args) => execFileSync('git',args,{cwd,maxBuffer:32*1024*1024});
const remote = source => `https://github.com/${source.repository}.git`;
const shallow = path => git(path,'rev-parse','--is-shallow-repository').toString().trim() === 'true';
// Whether the commit survives HEAD moving away from it.
async function kept(path, commit) {
 try { git(path,'symbolic-ref','--quiet','HEAD'); return true; } catch {}
 if (git(path,'for-each-ref','--contains',commit,'--count=1').length) return true;
 if (!shallow(path)) return false;
 // A shallow fetch records the commits it stopped at, so one listed there came from upstream and not from an edit here.
 // git answers with a path relative to the checkout, or an absolute one for a linked worktree.
 const boundary = await readFile(resolve(path, git(path,'rev-parse','--git-path','shallow').toString().trim()),'utf8').catch(() => '');
 return boundary.split('\n').includes(commit);
}
async function checkout(name, source, check) {
 if (!/^[a-f0-9]{40}$/.test(source.commit)) throw Error(`Unpinned source: ${name}`);
 const path = join(root,'vendor',name);
 try { await access(join(path,'.git')); }
 catch {
  if (check) throw Error(`Missing ${name}; run node scripts/sync-upstream.mjs`);
  await mkdir(join(root,'vendor'),{recursive:true});
  const staging=await mkdtemp(join(root,'vendor',name+'.tmp-'));
  try {
   git(staging,'init','--quiet');
   git(staging,'remote','add','origin',`https://github.com/${source.repository}.git`);
   git(staging,'fetch','--depth=1','origin',source.commit);
   git(staging,'checkout','--detach','FETCH_HEAD');
   await rename(staging,path);
  } finally { await rm(staging,{recursive:true,force:true}); }
 }
 const modified = () => git(path,'status','--porcelain','--untracked-files=all','--','.',':(exclude)kotlin/.kotlin/').length > 0;
 const head = git(path,'rev-parse','HEAD').toString().trim();
 if (head !== source.commit) {
  if (check) throw Error(`Wrong revision: ${name}; run node scripts/sync-upstream.mjs`);
  // A checkout with local edits is someone's work, so only a clean one is moved to the new pin.
  if (modified()) throw Error(`Modified source: ${name} is at ${head.slice(0,7)}, not the pinned ${source.commit.slice(0,7)}; commit or discard its changes, then run sync again`);
  // A clean tree can still hold commits made in the checkout. Leaving a detached commit that nothing else keeps
  // would strand it in the reflog, so the move needs a branch, a ref, or a commit that came from upstream.
  if (!(await kept(path, head))) throw Error(`Unsaved commits: ${name} is at ${head}, which no branch keeps; run git -C vendor/${name} switch -c keep, then run sync again`);
  try { git(path,'cat-file','-e',`${source.commit}^{commit}`); }
  catch {
   // --depth would cut the history of a full clone, so it is kept for the shallow ones sync makes.
   git(path,'fetch',...(shallow(path) ? ['--depth=1'] : []),remote(source),source.commit);
  }
  // Detaching leaves any branch in place. A file git ignores is not in the status above, so the checkout
  // itself must refuse to write over one.
  git(path,'checkout','--quiet','--no-overwrite-ignore','--detach',source.commit);
  console.log(`Moved vendor/${name} from ${head} to ${source.commit}.`);
 }
 if (modified()) throw Error(`Modified source: ${name}`);
 return path;
}
async function main() {
 const args=process.argv.slice(2);
 if(args.some(a=>a!=='--check')) throw Error('Usage: node scripts/sync-upstream.mjs [--check]');
 const check=args.includes('--check');
 const lock=JSON.parse(await readFile(join(root,'upstream.lock.json'),'utf8'));
 for(const [name,source] of Object.entries(lock.sources)) await checkout(name,source,check);
 const dir=join(root,'app/src/main/assets/slackwater');
 // Gradle reads fixtures from this checkout's files, so a stale one must fail the check like any other source.
 if(check && lock.resources.repository) await checkout('slackwater-ios',lock.resources,true);
 if(!check) {
  const ios=await checkout('slackwater-ios',lock.resources,false);
  if (lock.productDocs) {
   if (lock.productDocs.repository !== lock.resources.repository || !/^[a-f0-9]{40}$/.test(lock.productDocs.commit)) throw Error('Invalid iOS documentation source');
   try { git(ios,'cat-file','-e',`${lock.productDocs.commit}^{commit}`); }
   catch { git(ios,'fetch',...(shallow(ios) ? ['--depth=1'] : []),remote(lock.productDocs),lock.productDocs.commit); }
  }
  await mkdir(dir,{recursive:true});
  for(const entry of lock.resources.assets) {
   if(!/^[\w][\w.-]*$/.test(entry.name)) throw Error('Invalid asset name');
   const data=git(ios,'show',`${lock.resources.commit}:${entry.path}`);
   if(hash(data)!==entry.sha256) throw Error(`Upstream hash mismatch: ${entry.name}`);
   const dest=join(dir,entry.name); await writeFile(dest+'.tmp',data); await rename(dest+'.tmp',dest);
  }
 }
 await verifyAssets(dir,lock.resources.assets);
 const names=await readdir(join(root,'app/src/main/assets'));
 if(names.some(n=>n !== 'slackwater')) throw Error('Unexpected top-level asset');
 console.log('Pinned sources and packaged assets verified.');
}
if(import.meta.main) main().catch(e=>{console.error(e.message);process.exitCode=1;});
