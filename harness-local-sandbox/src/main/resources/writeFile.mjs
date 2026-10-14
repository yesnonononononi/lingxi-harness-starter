import { readRequest } from './request.mjs';
import { writeFile } from 'node:fs/promises';

try {
    const { path, bytesBase64 } = await readRequest();

    if (typeof path !== 'string' || path.length === 0 || typeof bytesBase64 !== 'string') {
        throw new TypeError('Expected path and bytesBase64 strings');
    }

    const bytes = Buffer.from(bytesBase64, 'base64');
    if (bytes.toString('base64') !== bytesBase64) {
        throw new TypeError('Invalid Base64 content');
    }

    // The caller must launch this process through SRT to enforce sandbox permissions.
    await writeFile(path, bytes);
} catch (error) {
    console.error(`${error.code ?? 'WRITE_FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
