import assert from 'node:assert/strict';
import {
  SQLX_INSTALL_DOCS_URL,
  SQLX_INSTALL_POWERSHELL_URL,
  SQLX_INSTALL_SCRIPT_URL,
  SQLX_NPX_INSTALL_COMMAND,
  SQLX_SKILL_COMMANDS,
  SQLX_WINDOWS_ZIP_URL,
  manualInstallCommand,
  manualInstallOptions,
  pathHintCommand,
} from './sqlxCommands';

const unix = manualInstallCommand('mac');
assert.equal(unix, `curl -fsSL ${SQLX_INSTALL_SCRIPT_URL} | sh`);
assert.equal(
  manualInstallCommand('linux'),
  unix,
  'macOS and Linux share the official shell installer command',
);

const windows = manualInstallCommand('windows');
assert.ok(windows.includes(SQLX_INSTALL_POWERSHELL_URL), 'Windows uses the official PowerShell installer');
assert.match(windows, /powershell -NoProfile -ExecutionPolicy Bypass -File/);
assert.notEqual(windows, unix, 'the page only shows the command for the current platform');
assert.ok(!windows.split('\n').some((line) => !line.trim()), 'the PowerShell command has no blank lines');

assert.match(pathHintCommand('mac'), /export PATH="\$HOME\/\.local\/bin:\$PATH"/);
assert.match(pathHintCommand('windows'), /LOCALAPPDATA/);
assert.equal(SQLX_INSTALL_DOCS_URL, 'https://github.com/OtterMind/sqlx#install-the-cli');

assert.equal(SQLX_NPX_INSTALL_COMMAND, 'npx -y @ottermind/sqlx@latest');
const unixOptions = manualInstallOptions('linux');
assert.deepEqual(
  unixOptions.map((option) => option.label),
  ['macOS / Linux', 'Node.js'],
  'the page offers the platform installer and the npm installer',
);
assert.equal(unixOptions[0].command, unix);
assert.equal(unixOptions[1].command, SQLX_NPX_INSTALL_COMMAND);
const windowsOptions = manualInstallOptions('windows');
assert.equal(windowsOptions[0].label, 'Windows');
assert.equal(windowsOptions[0].command, windows);
assert.equal(
  windowsOptions[1].command,
  SQLX_NPX_INSTALL_COMMAND,
  'the npm installer is offered on every platform',
);
assert.deepEqual(
  windowsOptions.map((option) => option.label),
  ['Windows', 'Node.js', 'Windows (zip)'],
  'Windows also offers the archive for machines whose policy blocks the installer script',
);
assert.equal(windowsOptions[2].command, SQLX_WINDOWS_ZIP_URL);
assert.equal(unixOptions.length, 2, 'the archive row is Windows only');

assert.deepEqual(
  SQLX_SKILL_COMMANDS.map((option) => option.label),
  ['Claude Code', 'Codex', 'dsh', 'pi'],
  'the page lists one Skill command per supported agent',
);
assert.deepEqual(
  SQLX_SKILL_COMMANDS.map((option) => option.command),
  [
    'sqlx skill install --target claude',
    'sqlx skill install --target codex',
    'sqlx skill install --target dsh',
    'sqlx skill install --target pi',
  ],
  'Skill commands stay verbatim so they can be pasted into a terminal',
);

console.log('SQLX manual installation command tests passed');
