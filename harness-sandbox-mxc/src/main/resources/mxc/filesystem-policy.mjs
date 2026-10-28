import fs from 'node:fs';
import path from 'node:path';

const inside = (parent, child) => {
  const relative = path.relative(parent, child);
  return relative === '' || relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
};
const realTarget = value => {
  let current = path.resolve(value); const suffix = [];
  for (;;) {
    try { return path.join(fs.realpathSync(current), ...suffix.reverse()); }
    catch (error) {
      if (error.code !== 'ENOENT' || path.dirname(current) === current) throw error;
      suffix.push(path.basename(current)); current = path.dirname(current);
    }
  }
};
const unique = values => [...new Set(values)];

// Only ancestors of protected runtime trees are enumerated. No file content is inspected.
export function compileWindowsFilesystem(r, runtimeReadonly, controls) {
  const denied = unique([...(r.deniedPaths ?? []).map(realTarget), fs.realpathSync(controls)]);
  const roots = unique((r.ambientRoots ?? []).map(value => fs.realpathSync(value)));
  const readwrite = [];
  const readonly = unique([...r.readonlyPaths.map(value => fs.realpathSync(value)), ...runtimeReadonly]);
  if (!r.writable) readonly.push(fs.realpathSync(r.root));
  const protectedTrees = unique([...readonly, fs.realpathSync(controls)]);
  const addWritable = candidate => {
    if (protectedTrees.some(tree => inside(tree, candidate))) return;
    if (!protectedTrees.some(tree => inside(candidate, tree))) { readwrite.push(candidate); return; }
    // Windows rejects conflicting writable ancestors of readonly runtime grants.
    for (const entry of fs.readdirSync(candidate, {withFileTypes: true})) {
      const child = path.join(candidate, entry.name);
      // Reparse points can alias protected trees; flat files on structural ancestors stay readonly.
      // In particular, profile hives and live temporary files cannot be granted without sharing violations.
      if (!entry.isDirectory() || entry.isSymbolicLink()) continue;
      addWritable(child);
    }
  };
  if (roots.length) {
    for (const root of roots) {
      if (path.parse(root).root.toLowerCase() !== root.toLowerCase()) throw new Error('Ambient roots must be volume roots');
      if (denied.some(tree => inside(tree, root))) throw new Error('Denied volume cannot host sandbox runtime');
      if (r.writable) {
        for (const entry of fs.readdirSync(root, {withFileTypes: true})) {
          if (entry.isDirectory() && !entry.isSymbolicLink()) addWritable(path.join(root, entry.name));
        }
      }
    }
    // Root-only readonly grants permit volume metadata without granting writable drive roots.
    // Runtime trees receive no writable ancestor, so the root readonly grant remains effective.
    return {readwritePaths: unique(readwrite), readonlyPaths: roots, deniedPaths: denied};
  }
  for (const grant of [...(r.writable ? [r.root] : []), ...r.readwritePaths]) {
    const real = fs.realpathSync(grant);
    if (path.parse(real).root.toLowerCase() === real.toLowerCase()) throw new Error('Whole drive writable grants require ambientRoots');
    if (protectedTrees.some(tree => inside(tree, real) || inside(real, tree))) throw new Error('Writable grant overlaps runtime/control or readonly files');
    readwrite.push(real);
  }
  for (const grant of readonly) {
    if (path.parse(grant).root.toLowerCase() === grant.toLowerCase()) throw new Error('Whole drive readonly grants require ambientRoots');
  }
  return {readwritePaths: unique(readwrite), readonlyPaths: unique(readonly), deniedPaths: denied};
}

export function assertWritable(filesystem, target) {
  const real = realTarget(target);
  if (!filesystem.readwritePaths.some(grant => inside(grant, real))
    || filesystem.deniedPaths.some(grant => inside(grant, real))
    || filesystem.readonlyPaths.some(grant => path.parse(grant).root !== grant && inside(grant, real))) {
    throw new Error('File operation is outside writable grants or targets a protected path');
  }
}
