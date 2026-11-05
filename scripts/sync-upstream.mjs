import { readFile, readdir, mkdir, writeFile, rename, access, mkdtemp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
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
 if (git(path,'rev-parse','HEAD').toString().trim() !== source.commit) throw Error(`Wrong revision: ${name}`);
 if (git(path,'status','--porcelain','--untracked-files=all','--','.',':(exclude)kotlin/.kotlin/').length) throw Error(`Modified source: ${name}`);
 return path;
}
async function main() {
 const args=process.argv.slice(2);
 if(args.some(a=>a!=='--check')) throw Error('Usage: node scripts/sync-upstream.mjs [--check]');
 const check=args.includes('--check');
 const lock=JSON.parse(await readFile(join(root,'upstream.lock.json'),'utf8'));
 for(const [name,source] of Object.entries(lock.sources)) await checkout(name,source,check);
 const dir=join(root,'app/src/main/assets/slackwater');
 if(!check) {
  const ios=await checkout('slackwater-ios',lock.resources,false);
  await mkdir(dir,{recursive:true});
  for(const entry of lock.resources.assets) {
   if(!/^[\w][\w.-]*$/.test(entry.name)) throw Error('Invalid asset name');
   const data=git(ios,'show',`${lock.resources.commit}:${entry.path}`);
   if(hash(data)!==entry.sha256) throw Error(`Upstream hash mismatch: ${entry.name}`);
   const dest=join(dir,entry.name); await writeFile(dest+'.tmp',data); await rename(dest+'.tmp',dest);
  }
  const stations=JSON.parse(git(ios,'show',`${lock.resources.commit}:${lock.launchStation.sourcePath}`));
  const station=stations.find(s=>s.id===lock.launchStation.id);
  if(!station || station.id.startsWith('chs')) throw Error('Missing permitted launch station');
  const data=JSON.stringify(station,null,2)+'\n';
  if(hash(data)!==lock.launchStation.sha256) throw Error('Launch station hash mismatch');
  const launch=join(root,'app/src/main/assets/launch-station.json');
  await writeFile(launch+'.tmp',data); await rename(launch+'.tmp',launch);
 }
 await verifyAssets(dir,lock.resources.assets);
 const names=await readdir(join(root,'app/src/main/assets'));
 if(names.some(n=>!['slackwater','launch-station.json'].includes(n))) throw Error('Unexpected top-level asset');
 if(hash(await readFile(join(root,'app/src/main/assets/launch-station.json')))!==lock.launchStation.sha256) throw Error('Launch station hash mismatch');
 console.log('Pinned sources and packaged assets verified.');
}
if(import.meta.main) main().catch(e=>{console.error(e.message);process.exitCode=1;});
