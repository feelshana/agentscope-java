import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { isBuiltin } from 'node:module';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

const testsDirectory = dirname(fileURLToPath(import.meta.url));
const frontendDirectory = resolve(testsDirectory, '..');
const files = readdirSync(testsDirectory).filter(name => name.endsWith('.test.mjs')).sort();
if (files.length === 0) throw new Error('No frontend test files found');

// Use the existing application compiler, including Vite's import.meta.env handling.
// Neither the application config nor private .env files are loaded for these tests.
const temporaryRoot = mkdtempSync(join(tmpdir(), 'dataagent-frontend-tests-'));
try {
  for (const [name, base] of [['root', '/'], ['prefixed', '/dataagent/']]) {
    const outDir = join(temporaryRoot, name);
    console.log(`\nFrontend regression: base=${base}, files=${files.length}`);
    await build({
      configFile: false,
      envFile: false,
      root: frontendDirectory,
      base,
      mode: 'test',
      publicDir: false,
      logLevel: 'error',
      cacheDir: join(temporaryRoot, 'cache'),
      build: {
        ssr: true,
        target: 'node20',
        outDir,
        emptyOutDir: false,
        copyPublicDir: false,
        minify: false,
        rollupOptions: {
          input: Object.fromEntries(files.map(file => [file.slice(0, -4), join(testsDirectory, file)])),
          external: isBuiltin,
          output: { entryFileNames: '[name].mjs', chunkFileNames: 'chunks/[name]-[hash].mjs' },
        },
      },
    });
    const result = spawnSync(process.execPath, ['--test', ...files.map(file => join(outDir, file))], {
      stdio: 'inherit',
      env: { ...process.env, DATAAGENT_TEST_BASE_PATH: base },
    });
    if (result.error) throw result.error;
    if (result.status !== 0) {
      process.exitCode = result.status ?? 1;
      break;
    }
  }
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  // Only remove the unique temporary directory created by this invocation.
  if (dirname(temporaryRoot) !== resolve(tmpdir())
      || !basename(temporaryRoot).startsWith('dataagent-frontend-tests-')) {
    throw new Error('Unexpected frontend test directory; refusing cleanup');
  }
  rmSync(temporaryRoot, { recursive: true, force: true });
}
