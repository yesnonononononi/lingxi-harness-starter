// Minimal MCP server over stdio, used by the harness transport tests.
// It records where it runs and which process it is, so a test can prove that the configured
// working directory took effect and that the process really died after the session closed.
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const mode = process.argv[2] ?? 'plain';

writeFileSync(resolve(process.cwd(), 'child-cwd.txt'), process.cwd(), 'utf8');
writeFileSync(resolve(process.cwd(), 'child-pid.txt'), String(process.pid), 'utf8');

let grandchild = null;
if (mode === 'with-grandchild') {
  // A deliberately long-lived grandchild: the direct child is expected to disappear on close, a
  // grandchild started this way is exactly the orphan risk the tests document rather than assert.
  grandchild = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000);'], { stdio: 'ignore' });
  writeFileSync(resolve(process.cwd(), 'grandchild-pid.txt'), String(grandchild.pid), 'utf8');
}

const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');

const respond = (request) => {
  switch (request.method) {
    case 'initialize':
      return {
        protocolVersion: request.params?.protocolVersion ?? '2025-11-25',
        capabilities: { tools: {} },
        serverInfo: { name: 'fixture-server', version: '1.0.0' },
        instructions: 'fixture instructions',
      };
    case 'ping':
      return {};
    case 'tools/list':
      return {
        tools: [{
          name: 'echo',
          description: 'Echo the input',
          inputSchema: {
            type: 'object',
            properties: { value: { type: 'string' } },
            required: ['value'],
          },
        }],
      };
    case 'tools/call':
      return {
        content: [{ type: 'text', text: `echo:${request.params?.arguments?.value ?? ''}` }],
        isError: false,
      };
    default:
      return {};
  }
};

const handle = (line) => {
  const request = JSON.parse(line);
  if (request.id === undefined || request.id === null) {
    return; // notification: nothing to answer
  }
  send({ jsonrpc: '2.0', id: request.id, result: respond(request) });
};

let buffer = '';
process.stdin.on('data', (chunk) => {
  buffer += chunk.toString('utf8');
  let index = buffer.indexOf('\n');
  while (index >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (line) {
      handle(line);
    }
    index = buffer.indexOf('\n');
  }
});
process.stdin.on('end', () => {
  if (grandchild) {
    grandchild.kill();
  }
  process.exit(0);
});
