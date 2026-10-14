import { pathToFileURL } from 'node:url';

// Host-side transport adapter: reconstruct the exact CLI argv without passing
// embedded command quotes through Java's native Windows argument serializer.
const [cli, encodedCommand, settingsFile] = process.argv.slice(2);
if (!cli || !encodedCommand) throw new Error('Expected SRT CLI and encoded helper command');
process.argv = [process.execPath, cli, ...(settingsFile ? ['--settings', settingsFile] : []),
    '-c', Buffer.from(encodedCommand, 'base64').toString('utf8')];
await import(pathToFileURL(cli).href);
