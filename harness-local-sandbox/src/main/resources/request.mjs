import { readFile } from 'node:fs/promises';

export async function readRequest() {
    const encodedPath = process.argv[2];
    if (!encodedPath) throw new TypeError('Expected an encoded request-file path');
    const path = Buffer.from(encodedPath, 'base64').toString('utf8');
    return JSON.parse(await readFile(path, 'utf8'));
}
