import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const root = new URL('../', import.meta.url);

test('every bundled publisher has an accessible data credit', async () => {
    const index = JSON.parse(await readFile(new URL('app/src/main/assets/slackwater/station-index.json', root)));
    const notices = await readFile(new URL('app/src/main/res/raw/third_party_notices.txt', root), 'utf8');
    const names = { noaa: 'NOAA', ticon: 'TICON-4', dmi: 'Danish Meteorological Institute', jma: 'Japan Meteorological Agency', jhod: 'Japan Coast Guard', linz: 'Land Information New Zealand', kartverket: 'Kartverket' };
    for (const station of [...index.tides, ...index.currents]) {
        const source = station.id.split('/')[0];
        assert.ok(names[source], `Unreviewed publisher: ${source}`);
        assert.ok(notices.includes(names[source]), `Missing credit: ${source}`);
    }
    assert.ok(notices.includes('Canadian Hydrographic Service'));
    assert.ok(notices.includes('CC BY-NC 4.0'));
});

test('credits include notices for the pinned MapLibre distribution', async () => {
    const versions = await readFile(new URL('gradle/libs.versions.toml', root), 'utf8');
    const [, sdk] = versions.match(/org\.maplibre\.gl:android-sdk-opengl", version = "([^"]+)"/);
    const notices = await readFile(new URL('app/src/main/res/raw/third_party_notices.txt', root), 'utf8');
    for (const credit of [
        `maplibre-native/blob/android-v${sdk}/platform/android/LICENSE.md`,
        'GeoJSON and Turf', 'MapLibre Android Gestures', 'OkHttp 4.12.0 public suffix list',
        'kdbush.hpp', 'FreeType Project', 'PMTiles C++', 'jni.hpp', 'unordered_dense',
        'MapLibre Tile decoder', 'HarfBuzz', 'ICU 58 and later',
    ]) {
        assert.ok(notices.includes(credit), `Missing MapLibre notice: ${credit}`);
    }
});

test('Canadian assets contain only reviewed identity and recipe fields', async () => {
    const fields = {
        'chs-stations.json': 'aliases id latitude longitude name region timezone',
        'chs-tombstones.json': 'id latitude longitude name region',
        'chs-gates.json': 'aliases hwLagMinutes id latitude longitude lwLagMinutes magnitudeNote name reference referenceName region timezone',
        'chs-current-gates.json': 'aliases clause fitDays id latitude longitude magnitudeNote missContext name nearBar online onlineMiss provisionalSlackMinutes recipe region slackBoundMinutes tideReference timezone value',
    };
    for (const [file, keys] of Object.entries(fields)) {
        const allowed = new Set(keys.split(' '));
        const scan = value => {
            if (Array.isArray(value)) value.forEach(scan);
            else if (value && typeof value === 'object') for (const [key, child] of Object.entries(value)) {
                assert.ok(allowed.has(key), `Unreviewed field in ${file}: ${key}`);
                scan(child);
            }
        };
        scan(JSON.parse(await readFile(new URL(`app/src/main/assets/slackwater/${file}`, root))));
    }
});

test('bundled current gates have the evidence fields required by Android', async () => {
    const gates = JSON.parse(await readFile(new URL('app/src/main/assets/slackwater/chs-current-gates.json', root)));
    for (const gate of gates) {
        assert.equal(typeof gate.id, 'string');
        assert.ok(Number.isFinite(gate.fitDays), gate.id);
        if (gate.onlineMiss) assert.equal(typeof gate.onlineMiss.clause, 'string', gate.id);
    }
});

test('live station IDs are unique and disjoint from restricted identities', async () => {
    const read = async name => JSON.parse(await readFile(new URL(
        `app/src/main/assets/slackwater/${name}.json`, root)));
    const index = await read('station-index');
    const live = index.tides.map(row => row.id);
    for (const row of index.currents) {
        assert.ok(!row.id.startsWith('current:'), `Already namespaced: ${row.id}`);
        live.push(`current:${row.id}`);
    }
    for (const name of ['chs-stations', 'chs-gates', 'chs-current-gates']) {
        live.push(...(await read(name)).map(row => row.id));
    }
    const ids = new Set(live);
    assert.equal(ids.size, live.length);
    for (const row of await read('unavailable-stations')) {
        assert.ok(!ids.has(row.id), `Restricted station in live catalog: ${row.id}`);
    }
});
