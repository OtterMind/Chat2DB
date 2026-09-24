import type { SqlxPlatform } from '@/typings/settings';

export const SQLX_INSTALL_SCRIPT_URL = 'https://raw.githubusercontent.com/OtterMind/sqlx/main/scripts/install.sh';
export const SQLX_INSTALL_POWERSHELL_URL = 'https://raw.githubusercontent.com/OtterMind/sqlx/main/scripts/install.ps1';
export const SQLX_INSTALL_DOCS_URL = 'https://github.com/OtterMind/sqlx#install-the-cli';
/** The npm installer package; it downloads the same release archive and needs Node.js 22 or newer. */
export const SQLX_NPX_INSTALL_COMMAND = 'npx -y @ottermind/sqlx@latest';

export interface SqlxManualInstallOption {
  /** Shown verbatim: a product or platform name, never translated. */
  label: string;
  command: string;
}

/** Platform reported by the desktop runtime, or a browser guess while previewing the page. */
export function detectPlatform(): SqlxPlatform {
  if (typeof navigator === 'undefined') {
    return 'linux';
  }
  if (/Win/i.test(navigator.userAgent)) {
    return 'windows';
  }
  if (/Mac|iPod|iPhone|iPad/i.test(navigator.userAgent)) {
    return 'mac';
  }
  return 'linux';
}

/** The official installer command for the current platform, shown verbatim and never translated. */
export function manualInstallCommand(platform: SqlxPlatform): string {
  if (platform === 'windows') {
    return [
      `$installer = Join-Path $env:TEMP 'sqlx-install.ps1'`,
      `Invoke-WebRequest '${SQLX_INSTALL_POWERSHELL_URL}' -OutFile $installer`,
      `powershell -NoProfile -ExecutionPolicy Bypass -File $installer`,
    ].join('\n');
  }
  return `curl -fsSL ${SQLX_INSTALL_SCRIPT_URL} | sh`;
}

/** Latest release archive, for a Windows machine whose policy blocks the installer script. */
export const SQLX_WINDOWS_ZIP_URL =
  'https://github.com/OtterMind/sqlx/releases/latest/download/sqlx-windows-x64.zip';

/**
 * The installation commands the page offers: the platform script first, then the npm installer.
 * <p>
 * Windows adds the plain archive, because some managed machines refuse to run a PowerShell script
 * even with `-ExecutionPolicy Bypass` when the policy comes from the organisation.
 */
export function manualInstallOptions(platform: SqlxPlatform): SqlxManualInstallOption[] {
  return [
    {
      label: platform === 'windows' ? 'Windows' : 'macOS / Linux',
      command: manualInstallCommand(platform),
    },
    { label: 'Node.js', command: SQLX_NPX_INSTALL_COMMAND },
    ...(platform === 'windows' ? [{ label: 'Windows (zip)', command: SQLX_WINDOWS_ZIP_URL }] : []),
  ];
}

/** Skill installation for the agents SQLX supports; the CLI owns the target directories. */
export const SQLX_SKILL_COMMANDS: SqlxManualInstallOption[] = [
  { label: 'Claude Code', command: 'sqlx skill install --target claude' },
  { label: 'Codex', command: 'sqlx skill install --target codex' },
  { label: 'dsh', command: 'sqlx skill install --target dsh' },
  { label: 'pi', command: 'sqlx skill install --target pi' },
];

/** Command that puts the install directory on PATH when the desktop runtime cannot find `sqlx`. */
export function pathHintCommand(platform: SqlxPlatform): string {
  if (platform === 'windows') {
    return `$env:Path = "$env:LOCALAPPDATA\\Programs\\SQLX;$env:Path"`;
  }
  return `export PATH="$HOME/.local/bin:$PATH"`;
}
