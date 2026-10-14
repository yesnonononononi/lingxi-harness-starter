import { readRequest } from './request.mjs';
import { mkdir, open, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

async function mustDeny(operation, path, hiddenFileIsDenied = false) {
    try {
        await operation();
    } catch (error) {
        if (error.code === 'EACCES' || error.code === 'EPERM' || error.code === 'EROFS'
            || (hiddenFileIsDenied && error.code === 'ENOENT')) return;
        throw error;
    }
    throw new Error(`Sandbox policy did not deny access: ${path}`);
}

async function checkWriteDenied(path, directory) {
    const marker = directory ? join(path, `sandbox-probe-${randomUUID()}`) : path;
    let created = false;
    try {
        await mustDeny(async () => {
            // Existing files are only opened; their bytes are never changed.
            const handle = await open(marker, directory ? 'wx' : 'r+');
            created = directory;
            await handle.close();
        }, path);
    } finally {
        if (created) await rm(marker);
    }
}

try {
    const { root, argv, env = {}, protectedDirectories, denyRead, denyWrite, workspaceWritable } = await readRequest();
    if (workspaceWritable) {
        const directory = join(root, `sandbox-probe-${randomUUID()}`);
        try {
            await mkdir(directory);
            const file = join(directory, 'io.txt');
            const text = '\uFEFFsandbox\r\n\r\nready\u0000';
            await writeFile(file, text, 'utf8');
            if (await readFile(file, 'utf8') !== text) throw new Error('Sandbox IO probe changed file contents');
            await rm(file);
        } finally {
            await rm(directory, { recursive: true, force: true });
        }
    } else {
        await readdir(root);
        await checkWriteDenied(root, true);
    }

    const isCmd = /(^|[\\/])cmd(?:\.exe)?$/i.test(argv[0]);
    const { stdout } = await promisify(execFile)(argv[0], argv.slice(1), {
        cwd: root, shell: false, windowsVerbatimArguments: process.platform === 'win32' && isCmd,
        env: { ...process.env, ...env },
    });
    if (stdout.trim() !== 'sandbox-ready') throw new Error(`Sandbox shell probe returned unexpected output: ${stdout}`);

    for (const path of protectedDirectories) await checkWriteDenied(path, true);
    for (const { path, directory } of denyWrite) await checkWriteDenied(path, directory);
    for (const { path } of denyRead) {
        await mustDeny(async () => {
            await (await open(path, 'r')).close();
        }, path, true);
    }
    process.stdout.write(JSON.stringify({ ready: true }));
} catch (error) {
    console.error(`${error.code ?? 'SANDBOX_NOT_READY'}: ${error.message}`);
    process.exitCode = 1;
}
