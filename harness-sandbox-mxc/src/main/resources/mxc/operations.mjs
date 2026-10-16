import fs from 'node:fs';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';

const LIMIT = 16 * 1024 * 1024;
try {
  let input = '';
  for await (const chunk of process.stdin) {
    input += chunk;
    if (input.length > LIMIT * 2) throw new Error('File request exceeds limit');
  }
  const r = JSON.parse(input);
  let result = {};
  switch (r.operation) {
    case 'execute': {
      if (process.platform !== 'win32') throw new Error('Command bootstrap is Windows-only');
      // Inherit the sandbox's hidden console: a detached child gets a fresh OEM code page.
      const page = spawnSync(r.codePageExecutable, ['65001'], {stdio: 'ignore', timeout: 5000});
      if (page.error || page.status !== 0) throw new Error('Could not initialize UTF-8 command output');
      // CMD caches its output code page at startup. Pass the script only to this new CMD.
      const child = spawnSync(r.shellExecutable, ['/d', '/s', '/c', `"${r.command}"`], {
        stdio: 'inherit', windowsVerbatimArguments: true, env: r.commandEnvironment
      });
      if (child.error) throw child.error;
      process.exitCode = child.status ?? 1;
      break;
    }
    case 'probe': {
      if (!fs.statSync(r.root).isDirectory()) throw new Error('Workspace is not a directory');
      const canary = `${r.root}/.mxc-probe-${crypto.randomUUID()}`;
      if (r.writable) {
        try {
          fs.writeFileSync(canary, 'probe', { flag: 'wx' });
          if (fs.readFileSync(canary, 'utf8') !== 'probe') throw new Error('Workspace readback failed');
        } finally { fs.rmSync(canary, { force: true }); }
      } else {
        fs.readdirSync(r.root);
        let blocked = false;
        try { fs.writeFileSync(canary, 'probe', { flag: 'wx' }); }
        catch (e) { if (!['EROFS', 'EACCES', 'EPERM'].includes(e.code)) throw e; blocked = true; }
        if (!blocked) { fs.rmSync(canary, { force: true }); throw new Error('Readonly workspace is writable'); }
      }
      let readonly = false;
      try { const fd = fs.openSync(import.meta.filename, 'r+'); fs.closeSync(fd); }
      catch (e) { if (!['EROFS', 'EACCES', 'EPERM'].includes(e.code)) throw e; readonly = true; }
      if (!readonly) throw new Error('Helper is writable inside sandbox');
      let hidden = false;
      try { fs.readFileSync(r.outside); }
      catch (e) { if (!['ENOENT', 'EACCES', 'EPERM'].includes(e.code)) throw e; hidden = true; }
      if (!hidden) throw new Error('Control directory is visible inside sandbox');
      for (const ns of process.platform === 'linux' ? ['mnt', 'pid', 'net'] : []) {
        if (fs.readlinkSync(`/proc/self/ns/${ns}`) === r.namespaces[ns]) {
          throw new Error(`Sandbox did not isolate ${ns} namespace`);
        }
      }
      result = { ready: true, commandCharset: r.commandCharset ?? 'UTF-8' };
      break;
    }
    case 'exists':
      try { fs.statSync(r.path); result.exists = true; }
      catch (e) { if (!['ENOENT', 'ENOTDIR'].includes(e.code)) throw e; result.exists = false; }
      break;
    case 'mkdir': fs.mkdirSync(r.path, { recursive: true }); break;
    case 'delete':
      try { fs.unlinkSync(r.path); } catch (e) { if (e.code !== 'ENOENT') throw e; }
      break;
    case 'read': {
      const fd = fs.openSync(r.path, 'r');
      try {
        if (!fs.fstatSync(fd).isFile()) throw new Error('Only regular files can be read');
        const bytes = Buffer.alloc(LIMIT + 1);
        let count = 0;
        while (count <= LIMIT) {
          const read = fs.readSync(fd, bytes, count, bytes.length - count, null);
          if (read === 0) break;
          count += read;
        }
        if (count > LIMIT) throw new Error('File exceeds 16 MiB limit');
        result.bytesBase64 = bytes.subarray(0, count).toString('base64');
      } finally { fs.closeSync(fd); }
      break;
    }
    case 'write': {
      const bytes = Buffer.from(r.bytesBase64, 'base64');
      if (bytes.length > LIMIT) throw new Error('File exceeds 16 MiB limit');
      fs.writeFileSync(r.path, bytes); break;
    }
    default: throw new Error('Unsupported file operation');
  }
  if (r.operation !== 'execute') process.stdout.write(JSON.stringify(result));
} catch (error) {
  process.stderr.write(`${error.code ?? 'io_error'}: ${error.message}`);
  process.exitCode = 1;
}
