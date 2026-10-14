import { readRequest } from './request.mjs';
import { stat } from 'node:fs/promises';

try {
    const { path } = await readRequest();
    if (typeof path !== 'string' || path.length === 0) throw new TypeError('Expected a non-empty path');
    let exists = true;
    try {
        await stat(path);
    } catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error;
        exists = false;
    }
    process.stdout.write(JSON.stringify({ exists }));
} catch (error) {
    console.error(`${error.code ?? 'EXISTS_FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
