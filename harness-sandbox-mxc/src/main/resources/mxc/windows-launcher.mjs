import fs from 'node:fs';
import path from 'node:path';
import {spawn, spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';

const [mode, controls, token] = process.argv.slice(2);
if (process.platform !== 'win32' || !['run', 'cancel'].includes(mode) || !path.isAbsolute(controls ?? '') || !/^[a-f0-9-]{36}$/.test(token)) {
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
const inside = (parent, child) => {
    const rel = path.relative(parent, child);
    return rel === '' || rel !== '..' && !rel.startsWith('..' + path.sep) && !path.isAbsolute(rel);
};
const canonical = values => [...new Set(values.map(value => fs.realpathSync(value)))];
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
    let input = '';
    for await (const chunk of process.stdin) {
        input += chunk;
        if (input.length > 40 * 1024 * 1024) throw new Error('Request exceeds limit');
    }
    const r = JSON.parse(input);
    if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('MXC requires Node >=24');
    const sdk = fs.realpathSync(r.sdkDirectory);
    const metadata = JSON.parse(fs.readFileSync(path.join(sdk, 'package.json'), 'utf8'));
    if (metadata.name !== '@microsoft/mxc-sdk' || metadata.version !== '1.0.0') throw new Error('Expected @microsoft/mxc-sdk 1.0.0');
    const executor = path.join(sdk, 'bin', process.arch, 'wxc-exec.exe');
    const koffi = createRequire(path.join(sdk, 'package.json'))('koffi');
    r.commandCharset = 'cp' + koffi.load('kernel32.dll').func('unsigned int GetOEMCP()')();
    const root = fs.realpathSync(r.root);
    r.root = root;
    const helper = fs.realpathSync(import.meta.dirname);
    const node = fs.realpathSync(process.execPath);
    const nodeRoot = path.dirname(node);
    const sdkDependencies = path.basename(path.dirname(sdk)).startsWith('@') ? path.dirname(path.dirname(sdk)) : sdk;
    const rw = canonical([...(r.writable ? [root] : []), ...r.readwritePaths]);
    const ro = canonical([...r.readonlyPaths, ...(r.writable ? [] : [root])]);
    for (const grant of [...rw, ...ro]) if (path.parse(grant).root.toLowerCase() === grant.toLowerCase()) throw new Error('Whole drive grants are unsupported');
    for (const grant of rw) {
        for (const protectedPath of [nodeRoot, sdkDependencies, helper, fs.realpathSync(controls), systemRoot]) {
            if (inside(grant, protectedPath) || inside(protectedPath, grant)) throw new Error('Writable grant overlaps runtime/control files');
        }
        if (ro.some(readonly => inside(readonly, grant))) throw new Error('Writable grant nested in readonly grant is unsupported');
    }
    if (['write', 'mkdir', 'delete'].includes(r.operation)) {
        const target = r.operation === 'delete' ? path.join(realTarget(path.dirname(r.path)), path.basename(r.path)) : realTarget(r.path);
        if (!rw.some(grant => inside(grant, target)) || ro.some(grant => inside(grant, target))) throw new Error('File operation is outside explicit writable grants');
    }
    const cmd = path.join(systemRoot, 'System32/cmd.exe');
    const command = r.operation === 'execute'
        ? `${quote(cmd)} /d /s /c "${r.command}"`
        : `${quote(node)} --preserve-symlinks-main --preserve-symlinks ${quote(path.join(helper, 'operations.mjs'))}`;
    const environment = {
        SystemRoot: systemRoot, WINDIR: systemRoot, SystemDrive: path.parse(systemRoot).root.slice(0, 2),
        PATHEXT: '.COM;.EXE;.BAT;.CMD', ComSpec: path.join(systemRoot, 'System32/cmd.exe'),
        PATH: `${nodeRoot};${path.join(systemRoot, 'System32')}`, USERPROFILE: root, HOME: root, LOCALAPPDATA: root,
        TEMP: root, TMP: root,
        NODE_OPTIONS: '--preserve-symlinks-main --preserve-symlinks',
        ...(r.operation === 'execute' ? r.environment : {}),
        HARNESS_WORKSPACE: root,
        WORKDIR: r.workDir ?? root
    };

    const config = {
        version: '1.0.0',
        containment: 'processcontainer',
        containerId: 'harness-' + token,
        process: {
            commandLine: command, cwd: r.workDir ?? root, timeout: r.timeoutMs + 10000,
            env: Object.entries(environment).map(([key, value]) => `${key}=${value}`),
            inheritDefaultEnv: false
        },
        fallback: {allowDaclMutation: false},
        ui: {disable: false, clipboard: 'none', injection: false},
        filesystem: {readwritePaths: rw, readonlyPaths: canonical([...ro, nodeRoot, helper, systemRoot])},
        network: {egress: {default: 'deny'}, ingress: {default: 'deny', hostLoopback: 'deny'}},
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
    if (r.operation === 'probe') {
        fs.writeFileSync(canary, 'outside-control', {flag: 'wx'});
        r.outside = canary;
    }
    if (fs.existsSync(cancelFile)) throw new Error('Request cancelled before spawn');
    active = spawn(executor, [policyFile], {windowsHide: true, env: cleanHostEnv, stdio: ['pipe', 'pipe', 'pipe']});
    let size = 0, truncated = false, timedOut = false;
    const chunks = [];
    for (const stream of [active.stdout, active.stderr]) stream.on('data', bytes => {
        const keep = Math.max(0, Math.min(bytes.length, r.maxBytes - size));
        if (keep) {
            chunks.push(Buffer.from(bytes.subarray(0, keep)));
            size += keep;
        }
        if (keep < bytes.length) truncated = true;
    });
    active.stdin.on('error', error => {
        if (error.code !== 'EPIPE') kill();
    });

    active.stdin.end(r.operation === 'execute' ? '' : JSON.stringify(r));

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
    process.stdout.write(JSON.stringify({
        exitCode,
        timedOut,
        outputBase64: Buffer.concat(chunks).toString('base64'),
        truncated
    }));
} catch (error) {
    kill();
    process.stdout.write(JSON.stringify({error: `${error.code ?? 'mxc_error'}: ${error.message}`}));
    process.exitCode = 1;
} finally {
    clearInterval(monitor);
    for (const file of [policyFile, cancelFile, canary]) fs.rmSync(file, {force: true});
}
