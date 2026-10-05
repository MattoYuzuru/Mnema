#!/usr/bin/env node
/**
 * Proves the dev-only styleguide did not reach a production build: no output file is named after it and none contains its
 * route, its component or its copy. Run after `npm run build`:
 *
 *   node scripts/verify-no-styleguide.mjs [dist/mnema-frontend]
 *
 * Exit code 1 lists the offending files. Pointed at a development build (`npx ng build --configuration development`) it
 * must fail: that is how the check itself is kept honest (see docs/frontend/styleguide.md).
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const MARKERS = ['styleguide', 'StyleguidePage', 'sg-specimen', 'Только для разработки'];
const TEXT_FILE = /\.(?:js|mjs|css|html|json|txt|map)$/u;

function walk(directory) {
    return readdirSync(directory).flatMap(name => {
        const path = join(directory, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
}

const root = resolve(process.argv[2] ?? 'dist/mnema-frontend');
let files;
try {
    files = walk(root);
} catch {
    console.error(`No build output at ${root}. Run \`npm run build\` first.`);
    process.exit(2);
}
if (files.length === 0) {
    console.error(`${root} is empty.`);
    process.exit(2);
}

const offenders = [];
for (const file of files) {
    const name = file.slice(root.length + 1);
    if (/styleguide/iu.test(name)) { offenders.push(`${name} (file name)`); continue; }
    if (!TEXT_FILE.test(name)) continue;
    const content = readFileSync(file, 'utf8');
    const marker = MARKERS.find(candidate => content.includes(candidate));
    if (marker) offenders.push(`${name} (contains "${marker}")`);
}

if (offenders.length > 0) {
    console.error(`The production bundle must not contain the dev-only styleguide:\n  ${offenders.join('\n  ')}`);
    process.exit(1);
}
console.log(`OK: ${files.length} files in ${root} contain no styleguide route, chunk or copy.`);
