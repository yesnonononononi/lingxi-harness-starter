import { readRequest } from './request.mjs';
import { unlink } from 'node:fs/promises';

const rm = async (path) => {
    try {
        await unlink(path);
    } catch (error) {
        if (error.code !== 'ENOENT') throw error;
    }
};

try {
    const { path } = await readRequest();

    if (typeof path !== 'string' || path.length === 0) {
        throw new TypeError('Expected a non-empty path');
    }

    await rm(path);
} catch (error) {
    console.error(`${error.code ?? 'FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
