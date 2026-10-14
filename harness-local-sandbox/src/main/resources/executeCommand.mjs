import { readRequest } from './request.mjs';
import { spawn } from 'node:child_process';

try {
    const { argv, env = {} } = await readRequest();
    if (!Array.isArray(argv) || argv.length === 0 || argv.some(arg => typeof arg !== 'string')) {
        throw new TypeError('Expected a non-empty string argv array');
    }

    // The caller launches this helper through SRT. The workspace shell receives
    // the complete script as one argument, without another shell parsing argv.
    // CMD interprets a command line itself; its /s /c script already has an
    // outer quote pair supplied by ShellType. CRT escaping would alter it.
    const isCmd = /(^|[\\/])cmd(?:\.exe)?$/i.test(argv[0]);
    const child = spawn(argv[0], argv.slice(1), {
        shell: false,
        env: { ...process.env, ...env },
        windowsVerbatimArguments: process.platform === 'win32' && isCmd,
        stdio: ['ignore', 'inherit', 'inherit'],
    });
    process.exitCode = await new Promise((resolve, reject) => {
        child.once('error', reject);
        child.once('exit', code => resolve(code ?? 1));
    });
} catch (error) {
    console.error(`${error.code ?? 'EXECUTE_FAILED'}: ${error.message}`);
    process.exitCode = 1;
}
