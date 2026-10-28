import fs from 'node:fs';
import path from 'node:path';

// Launch metadata never occupies protocol stdin or stdout.
export async function readRequest(mode, controls, token) {
  if (mode === 'stdio') {
    fs.writeFileSync(path.join(controls, token + '.started'), '', { flag: 'wx', mode: 0o600 });
    const file = path.join(controls, token + '.request.json');
    if (fs.statSync(file).size > 40 * 1024 * 1024) throw new Error('Request exceeds limit');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  }
  let input = '';
  for await (const chunk of process.stdin) {
    input += chunk;
    if (input.length > 40 * 1024 * 1024) throw new Error('Request exceeds limit');
  }
  return JSON.parse(input);
}

export function stdioCommand(r, node, helper, quote) {
  if (!Array.isArray(r.argv) || !r.argv.length || r.argv.some(v => typeof v !== 'string' || v.includes('\0'))) {
    throw new Error('Invalid stdio argv');
  }
  const payload = Buffer.from(JSON.stringify(r.argv), 'utf8').toString('base64');
  return `${quote(node)} ${quote(path.join(helper, 'stdio-server.mjs'))} ${payload}`;
}

export async function relayStdio(input, output, error, completion, stop) {
  if (!input || !output || !error) throw new Error('Backend did not expose stdio streams');
  let diagnostics = 0;
  const onError = bytes => {
    const keep = Math.max(0, Math.min(bytes.length, 65536 - diagnostics));
    if (keep) { diagnostics += keep; process.stderr.write(bytes.subarray(0, keep)); }
  };
  const onPipeError = () => stop();
  const onEof = () => { eofTimer = setTimeout(stop, 1000); eofTimer.unref(); };
  let eofTimer;
  input.on('error', onPipeError);
  output.on('error', onPipeError);
  error.on('data', onError);
  process.stdin.once('end', onEof);
  process.stdin.pipe(input);
  output.pipe(process.stdout, { end: false });
  try { return await completion(); }
  finally {
    clearTimeout(eofTimer);
    process.stdin.unpipe(input);
    process.stdin.removeListener('end', onEof);
    process.stdin.pause();
    output.unpipe(process.stdout);
    error.removeListener('data', onError);
  }
}
