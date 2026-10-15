import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const [mode, controls, token] = process.argv.slice(2);
if (!['run', 'cancel'].includes(mode) || !controls?.startsWith('/') || !/^[a-f0-9-]{36}$/.test(token)) {
  throw new Error('Invalid launcher arguments');
}
fs.mkdirSync(controls, { recursive: true, mode: 0o700 });
const pidFile = path.join(controls, token + '.pid');
const cancelledFile = path.join(controls, token + '.cancel');
const canary = path.join(controls, token + '.canary');
const statIdentity = pid => fs.readFileSync(`/proc/${pid}/stat`, 'utf8').split(') ').at(-1).split(' ')[19];

if (mode === 'cancel') {
  // A tombstone also cancels a launcher delayed in WSL startup before it registers its PID.
  fs.writeFileSync(cancelledFile, '', { mode: 0o600 });
  let registration;
  for (let i = 0; i < 30; i++) {
    try { registration = JSON.parse(fs.readFileSync(pidFile, 'utf8')); break; }
    catch (e) { if (e.code !== 'ENOENT') throw e; }
    await delay(100);
  }
  if (registration) {
    const { pid, start } = registration;
    const alive = () => {
      try { return statIdentity(pid) === start && fs.readFileSync(`/proc/${pid}/cmdline`).includes(Buffer.from(token)); }
      catch (e) { if (['ENOENT', 'ESRCH'].includes(e.code)) return false; throw e; }
    };
    if (alive()) process.kill(pid, 'SIGTERM');
    for (let i = 0; i < 20 && alive(); i++) await delay(100);
    if (alive()) process.kill(-pid, 'SIGKILL');
    for (const file of [pidFile, cancelledFile, canary]) fs.rmSync(file, { force: true });
  }
  process.exit(0);
}

let active;
let cancelling = false;
const stop = () => { cancelling = true; active?.kill(); };
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
fs.writeFileSync(pidFile, JSON.stringify({ pid: process.pid, start: statIdentity(process.pid) }), { flag: 'wx', mode: 0o600 });
try {
  if (fs.existsSync(cancelledFile)) throw new Error('Request cancelled before startup');
  let text = '';
  for await (const chunk of process.stdin) {
    text += chunk;
    if (text.length > 40 * 1024 * 1024) throw new Error('Request exceeds limit');
  }
  const r = JSON.parse(text);
  if (process.platform !== 'linux' || Number(process.versions.node.split('.')[0]) < 24) {
    throw new Error('MXC adapter requires Linux Node >=24');
  }
  const sdk = fs.realpathSync(r.sdkDirectory);
  const sdkPackage = JSON.parse(fs.readFileSync(path.join(sdk, 'package.json'), 'utf8'));
  if (sdkPackage.name !== '@microsoft/mxc-sdk' || sdkPackage.version !== '1.0.0') {
    throw new Error('Expected @microsoft/mxc-sdk 1.0.0');
  }
  const node = fs.realpathSync(process.execPath);
  const helper = fs.realpathSync(import.meta.dirname);
  const canonical = values => [...new Set(values.map(v => fs.realpathSync(v)))];
  const root = fs.realpathSync(r.root);
  r.root = root;
  const rw = canonical([root, ...r.readwritePaths]);
  const ro = canonical(r.readonlyPaths);
  const nodeRoot = path.dirname(path.dirname(node));
  const sdkDependencies = path.basename(path.dirname(sdk)).startsWith('@') ? path.dirname(path.dirname(sdk)) : sdk;
  const inside = (parent, child) => child === parent || child.startsWith(parent + '/');
  for (const grant of [...rw, ...ro]) {
    if (grant === '/') throw new Error('Whole filesystem grants are unsupported');
  }
  for (const grant of rw) {
    for (const protectedPath of [nodeRoot, sdkDependencies, helper, fs.realpathSync(controls),
      '/usr', '/etc', '/bin', '/sbin', '/lib', '/lib64', '/proc', '/sys', '/dev']) {
      if (inside(grant, protectedPath) || inside(protectedPath, grant)) {
        throw new Error('Writable grant overlaps deployment-owned runtime/control files');
      }
    }
    if (ro.some(readonly => inside(readonly, grant))) throw new Error('Writable grant nested in readonly grant is unsupported');
  }
  const { spawn } = await import(pathToFileURL(path.join(sdk, 'dist/v1/index.js')).href);
  const q = value => "'" + value.replaceAll("'", "'\\''") + "'";
  const request = {
    command: r.operation === 'execute'
      ? `/bin/bash --noprofile --norc -c ${q(r.command)}`
      : `${q(node)} ${q(path.join(helper, 'operations.mjs'))}`,
    workingDirectory: r.workDir ?? root,
    timeoutMs: r.timeoutMs,
    containment: { type: 'bubblewrap' },
    filesystem: { readwritePaths: rw, readonlyPaths: canonical([...ro, nodeRoot, helper]) },
    network: { egress: { default: 'deny' }, ingress: { default: 'deny', hostLoopback: 'deny' } },
    environment: { PATH: '/usr/bin:/bin', HOME: root, TMPDIR: '/tmp', LC_ALL: 'C.UTF-8',
      ...(r.operation === 'execute' ? r.environment : {}),
      HARNESS_WORKSPACE: root },
    inheritDefaultEnvironment: false,
  };
  if (r.operation === 'probe') {
    fs.writeFileSync(canary, 'outside-control', { flag: 'wx', mode: 0o600 });
    r.outside = canary;
    r.namespaces = Object.fromEntries(['mnt', 'pid', 'net'].map(ns => [ns, fs.readlinkSync(`/proc/self/ns/${ns}`)]));
  }
  if (cancelling || fs.existsSync(cancelledFile)) throw new Error('Request cancelled before spawn');
  active = await spawn(request, { telemetry: { enabled: false } });
  if (cancelling || fs.existsSync(cancelledFile)) active.kill();
  let size = 0, truncated = false;
  const buffers = [];
  const consume = stream => {
    if (!stream) throw new Error('Backend did not expose output streams');
    stream.on('data', bytes => {
      const keep = Math.max(0, Math.min(bytes.length, r.maxBytes - size));
      if (keep) { buffers.push(Buffer.from(bytes.subarray(0, keep))); size += keep; }
      if (keep < bytes.length) truncated = true;
    });
  };
  consume(active.standardOutput);
  consume(active.standardError);
  const stdin = active.standardInput;
  if (!stdin) throw new Error('Backend did not expose stdin');
  stdin.end(r.operation === 'execute' ? '' : JSON.stringify(r));
  const result = await active.wait();
  if (active.warnings.length) throw new Error('Backend warnings: ' + active.warnings.join('; '));
  process.stdout.write(JSON.stringify({ ...result, outputBase64: Buffer.concat(buffers).toString('base64'), truncated }));
} catch (error) {
  active?.kill();
  process.stdout.write(JSON.stringify({ error: `${error.code ?? 'mxc_error'}: ${error.message}` }));
  process.exitCode = 1;
} finally {
  for (const file of [pidFile, cancelledFile, canary]) fs.rmSync(file, { force: true });
}
