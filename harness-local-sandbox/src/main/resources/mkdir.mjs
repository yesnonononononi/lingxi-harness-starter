import { readRequest } from './request.mjs';
import { mkdir } from 'node:fs/promises';

try {
    const { path } = await readRequest();

    if (typeof path !== 'string' || path.length === 0) {
        throw new TypeError('Expected a non-empty path');
    }

    await mkdir(path, { recursive: true });
} catch (error) {
    console.error(`${error.code ?? 'FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
