import fs from 'node:fs';
import net from 'node:net';
import readline from 'node:readline';
import { spawn } from 'node:child_process';
fs.writeFileSync('server.pid', String(process.pid));
let child;
const reply = (id, result) => process.stdout.write(JSON.stringify({jsonrpc:'2.0', id, result}) + '\n');
for await (const line of readline.createInterface({input:process.stdin})) {
  const message = JSON.parse(line);
  if (message.method === 'initialize') {
    if (process.argv.includes('fail-init')) process.exit(7);
    reply(message.id, {protocolVersion:message.params.protocolVersion, capabilities:{tools:{}}, serverInfo:{name:'fixture',version:'1'}});
  } else if (message.method === 'tools/list') {
    reply(message.id, {tools:[{name:'probe', description:'fixture', inputSchema:{type:'object',properties:{},additionalProperties:true}}]});
  } else if (message.method === 'tools/call') {
    const a = message.params.arguments;
    let text;
    if (a.op === 'read') {
      try { text = fs.readFileSync(a.path, 'utf8'); } catch { text = 'DENIED'; }
    } else if (a.op === 'write') {
      try { fs.writeFileSync(a.path, 'bad'); text = 'WROTE'; } catch { text = 'DENIED'; }
    } else if (a.op === 'network') {
      text = await new Promise(resolve => {
        const s = net.connect({host:'127.0.0.1',port:a.port});
        s.setTimeout(1500);
        s.once('connect', () => {s.destroy(); resolve('CONNECTED');});
        s.once('error', () => resolve('DENIED'));
        s.once('timeout', () => {s.destroy(); resolve('DENIED');});
      });
    } else if (a.op === 'child') {
      child = spawn(process.execPath, ['-e', "require('fs').writeFileSync('child.pid',String(process.pid));setInterval(()=>{},1000)"], {stdio:'ignore'});
      while (!fs.existsSync('child.pid')) await new Promise(r => setTimeout(r, 20));
      text = String(child.pid);
    } else {
      process.stderr.write('diagnostic '.repeat(20000));
      text = JSON.stringify({value:a.value, argv:process.argv.slice(2), env:process.env.VISIBLE});
    }
    reply(message.id, {content:[{type:'text',text}]});
  } else if (message.method === 'ping') reply(message.id, {});
}
child?.kill();
