// Runs inside the sandbox; executable lookup uses only the sandbox environment.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const argv = JSON.parse(Buffer.from(process.argv[2], 'base64').toString('utf8'));
const windows = process.platform === 'win32';
let executable = argv[0];
if (windows && !path.extname(executable)) {
  const dirs = executable.includes('/') || executable.includes('\\')
    ? [''] : (process.env.PATH ?? '').split(';');
  executable = dirs.flatMap(dir => ['.exe', '.com', '.cmd', '.bat'].map(ext => path.join(dir, argv[0] + ext)))
    .find(candidate => fs.existsSync(candidate)) ?? executable;
}
let command = executable, args = argv.slice(1), verbatim = false;
if (windows && /\.(cmd|bat)$/i.test(executable)) {
  // cmd cannot represent these values reliably. Refuse rather than interpret them as commands.
  if ([executable, ...args].some(v => /["%\r\n]/.test(v))) throw new Error('Unsupported character in Windows batch argv; use an executable entrypoint');
  const quote = v => '"' + v + '"';
  command = process.env.ComSpec;
  args = ['/d', '/s', '/v:off', '/c', '"' + [executable, ...args].map(quote).join(' ') + '"'];
  verbatim = true;
}
const child = spawn(command, args, { stdio: 'inherit', windowsHide: true, windowsVerbatimArguments: verbatim });
child.once('error', error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
child.once('exit', code => { process.exitCode = code ?? 1; });
