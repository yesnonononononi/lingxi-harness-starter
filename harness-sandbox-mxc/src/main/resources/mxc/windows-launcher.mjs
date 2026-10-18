import {createOutputCollector} from './output-frames.mjs';
import {assertWritable, compileWindowsFilesystem} from './filesystem-policy.mjs';
import {readRequest, relayStdio, stdioCommand} from './stdio-session.mjs';
import fs from 'node:fs';
import path from 'node:path';
import {spawn, spawnSync} from 'node:child_process';

const [mode, controls, token] = process.argv.slice(2);
if (process.platform !== 'win32' || !['run', 'cancel', 'stdio'].includes(mode) || !path.isAbsolute(controls ?? '') || !/^[a-f0-9-]{36}$/.test(token)) {
    throw new Error('Invalid native Windows launcher arguments');
}
fs.mkdirSync(controls, {recursive: true});
const cancelFile = path.join(controls, token + '.cancel');
const policyFile = path.join(controls, token + '.json');
const canary = path.join(controls, token + '.canary');
if (mode === 'cancel') {
    fs.writeFileSync(cancelFile, '');
    process.exit(0);
}

let active, monitor;
let streaming = false;
const systemRoot = fs.realpathSync(process.env.SystemRoot ?? 'C:\\Windows');
const cleanHostEnv = {
    SystemRoot: systemRoot, WINDIR: systemRoot, PATH: path.join(systemRoot, 'System32'),
    TEMP: controls, TMP: controls
};
const kill = () => {
    if (active?.pid && active.exitCode === null) {
        const result = spawnSync(path.join(systemRoot, 'System32/taskkill.exe'), ['/PID', String(active.pid), '/T', '/F'],
            {windowsHide: true, timeout: 5000, env: cleanHostEnv});
        if (result.error) throw result.error;
    }
};
const quote = value => '"' + String(value).replace(/(\\*)"/g, '$1$1\\"').replace(/(\\+)$/g, '$1$1') + '"';
const realTarget = value => {
    let current = path.resolve(value);
    const suffix = [];
    for (; ;) {
        try {
            return path.join(fs.realpathSync(current), ...suffix.reverse());
        } catch (error) {
            if (error.code !== 'ENOENT' || path.dirname(current) === current) throw error;
            suffix.push(path.basename(current));
            current = path.dirname(current);
        }
    }
};

try {
    const r = await readRequest(mode, controls, token);
    streaming = r.streamOutput === true;
    if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('MXC requires Node >=24');
    const sdk = fs.realpathSync(r.sdkDirectory);
    const metadata = JSON.parse(fs.readFileSync(path.join(sdk, 'package.json'), 'utf8'));
    if (metadata.name !== '@microsoft/mxc-sdk' || metadata.version !== '1.0.0') throw new Error('Expected @microsoft/mxc-sdk 1.0.0');
    const executor = path.join(sdk, 'bin', process.arch, 'wxc-exec.exe');
    r.commandCharset = 'UTF-8';
    const root = fs.realpathSync(r.root);
    r.root = root;
    const helper = fs.realpathSync(import.meta.dirname);
    const node = fs.realpathSync(process.execPath);
    const nodeRoot = path.dirname(node);
    const sdkDependencies = path.basename(path.dirname(sdk)).startsWith('@') ? path.dirname(path.dirname(sdk)) : sdk;
    const filesystem = compileWindowsFilesystem(r, [nodeRoot, sdkDependencies, path.dirname(helper), systemRoot], controls);
    if (['write', 'mkdir', 'delete'].includes(r.operation)) {
        const target = r.operation === 'delete' ? path.join(realTarget(path.dirname(r.path)), path.basename(r.path)) : realTarget(r.path);
        assertWritable(filesystem, target);
    }
    const cmd = path.join(systemRoot, 'System32/cmd.exe');
    r.shellExecutable = cmd;
    r.codePageExecutable = path.join(systemRoot, 'System32/chcp.com');
    const command = mode === 'stdio' ? stdioCommand(r, node, helper, quote)
        : `${quote(node)} --preserve-symlinks-main --preserve-symlinks ${quote(path.join(helper, 'operations.mjs'))}`;
    const environment = {
        SystemRoot: systemRoot, WINDIR: systemRoot, SystemDrive: path.parse(systemRoot).root.slice(0, 2),
        PATHEXT: '.COM;.EXE;.BAT;.CMD', ComSpec: path.join(systemRoot, 'System32/cmd.exe'),
        PATH: `${nodeRoot};${path.join(systemRoot, 'System32')}`,
        ...(r.operation === 'execute' ? {} : {USERPROFILE: root, HOME: root, LOCALAPPDATA: root, TEMP: root, TMP: root}),
        NODE_OPTIONS: '--preserve-symlinks-main --preserve-symlinks',
        ...(r.operation === 'stdio' ? r.environment : {}),
        HARNESS_WORKSPACE: root,
        WORKDIR: r.workDir ?? root
    };
    if (r.operation === 'execute') {
        // Command preloads belong to the workload, not the encoding bootstrap.
        r.commandEnvironment = {...environment, ...r.environment, HARNESS_WORKSPACE: root, WORKDIR: r.workDir ?? root};
    }

    const config = {
        version: '1.0.0',
        containment: 'processcontainer',
        containerId: 'harness-' + token,
        process: {
            commandLine: command, cwd: r.workDir ?? root, ...(mode === 'stdio' ? {} : {timeout: r.timeoutMs + 10000}),
            env: Object.entries(environment).map(([key, value]) => `${key}=${value}`),
            inheritDefaultEnv: r.operation === 'execute'
        },
        fallback: {allowDaclMutation: false},
        ui: {disable: false, clipboard: 'none', injection: false},
        filesystem,
        network: {egress: {default: r.allowOutboundNetwork ? 'allow' : 'deny'}, ingress: {default: 'deny', hostLoopback: 'deny'}},
    };

    fs.writeFileSync(policyFile, JSON.stringify(config), {flag: 'wx'});

    const probe = spawnSync(executor, ['--probe', policyFile], {
        windowsHide: true,
        env: cleanHostEnv,
        encoding: 'utf8',
        timeout: 15000,
        maxBuffer: 1024 * 1024
    });

    if (probe.error || probe.status !== 0) throw new Error('BaseContainer probe failed: ' + (probe.error?.message ?? probe.stderr));
    const capability = JSON.parse(probe.stdout);
    if (capability.error || capability.tier !== 'base-container' || capability.needsDaclAugmentation !== false || capability.warnings?.length) {
        throw new Error('Native BaseContainer without DACL augmentation is required: ' + probe.stdout);
    }
    if (filesystem.deniedPaths.length && capability.probes?.baseContainerSupportsDenyPaths !== true) throw new Error('Native denied paths are unsupported on this host');
    if (r.operation === 'probe') {
        fs.writeFileSync(canary, 'outside-control', {flag: 'wx'});
        r.outside = canary;
    }
    if (fs.existsSync(cancelFile)) throw new Error('Request cancelled before spawn');
    active = spawn(executor, [policyFile], {windowsHide: true, env: cleanHostEnv, stdio: ['pipe', 'pipe', 'pipe']});
    if (mode === 'stdio') {
        monitor = setInterval(() => { if (fs.existsSync(cancelFile)) kill(); }, 50);
        const completion = () => new Promise((resolve, reject) => {
            active.once('error', reject);
            active.once('close', code => resolve(code ?? 1));
        });
        process.exitCode = await relayStdio(active.stdin, active.stdout, active.stderr, completion, kill);
    } else {
        let timedOut = false;
        const collector = createOutputCollector(r);
        for (const stream of [active.stdout, active.stderr]) stream.on('data', bytes => collector.consume(bytes));
        active.stdin.on('error', error => {
            if (error.code !== 'EPIPE') kill();
        });

        active.stdin.end(JSON.stringify(r));

        const timer = setTimeout(() => {
            timedOut = true;
            kill();
        }, r.timeoutMs);

        monitor = setInterval(() => {
            if (fs.existsSync(cancelFile)) {
                kill();
            }
        }, 50);

        let exitCode;
        try {
            exitCode = await new Promise((resolve, reject) => {
                active.once('error', reject);
                active.once('close', code => resolve(code ?? 1));
            });
        } finally {
            clearTimeout(timer);
            clearInterval(monitor);
        }
        collector.finish({exitCode, timedOut});
    }
} catch (error) {
    kill();
    (mode === 'stdio' ? process.stderr : process.stdout).write(JSON.stringify({type: streaming ? 'error' : undefined, error: `${error.code ?? 'mxc_error'}: ${error.message}`}) + (streaming ? '\n' : ''));
    process.exitCode = 1;
} finally {
    clearInterval(monitor);
    for (const file of [policyFile, cancelFile, canary, ...(mode === 'stdio' ? [path.join(controls, token + '.started'), path.join(controls, token + '.request.json')] : [])]) fs.rmSync(file, {force: true});
}
