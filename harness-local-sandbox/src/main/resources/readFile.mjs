import { readRequest } from './request.mjs';
import { readFile } from 'node:fs/promises';

try {
    const { path } = await readRequest();

    if (typeof path !== 'string' || path.length === 0) {
        throw new TypeError('Expected a non-empty path');
    }

    const content = await readFile(path);
    // Transport original bytes; decoding belongs to the bridge's requested charset.
    process.stdout.write(JSON.stringify({ bytesBase64: content.toString('base64') }));
} catch (error) {
    console.error(`${error.code ?? 'FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
