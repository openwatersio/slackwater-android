#!/usr/bin/env node
import { pathToFileURL } from 'node:url';

// YYMMDDnn in UTC, where nn counts quarter hours: readable in Play Console and below Play's 2,100,000,000 cap through 2099.
export function versionCode(date) {
  const two = (n) => String(n).padStart(2, '0');
  const quarterHour = Math.floor((date.getUTCHours() * 60 + date.getUTCMinutes()) / 15);
  return Number(two(date.getUTCFullYear() % 100) + two(date.getUTCMonth() + 1) + two(date.getUTCDate()) + two(quarterHour));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) console.log(versionCode(new Date()));
