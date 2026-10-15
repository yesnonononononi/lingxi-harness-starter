import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

try {
  if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('MXC requires Node >=24');
  if (!['win32', 'linux'].includes(process.platform)) throw new Error('Unsupported host platform');
  const sdk = fs.realpathSync(process.argv[2]);
  const metadata = JSON.parse(fs.readFileSync(path.join(sdk, 'package.json'), 'utf8'));
  if (metadata.name !== '@microsoft/mxc-sdk' || metadata.version !== '1.0.0') throw new Error('Expected @microsoft/mxc-sdk 1.0.0');
  const api = await import(pathToFileURL(path.join(sdk, 'dist/v1/index.js')).href);
  const backend = process.platform === 'win32' ? 'BaseContainer' : 'Bubblewrap';
  let available, detail;
  if (process.platform === 'win32') {
    // No policy, workspace paths, container creation or DACL grants are requested.
    const result = api.probe();
    available = result.tier === 'base-container' && result.needsDaclAugmentation === false && !result.error && result.warnings.length === 0;
    detail = available ? 'BaseContainer capability available' : JSON.stringify(result);
  } else {
    const result = api.getAvailableBackends();
    const selected = result.find(entry => entry.backend === 'bubblewrap');
    // Discovery can warn about optional proxy support; a deny-all workload does not require it.
    available = selected !== undefined;
    detail = JSON.stringify(selected ?? result);
  }
  process.stdout.write(JSON.stringify({ available: Boolean(available), backend, detail }));
} catch (error) {
  process.stdout.write(JSON.stringify({ error: `${error.code ?? 'mxc_error'}: ${error.message}` }));
  process.exitCode = 1;
}
