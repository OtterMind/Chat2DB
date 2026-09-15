import createJcefApi from './base';
import {
  IUpdateDetail,
  IUpdatePreferences,
  IUpdateRecoveryStatus,
  McpRestartResult,
  McpStatus,
} from '@/typings/settings';
import { LangType } from '@/constants/settings';
import type { LocalFileReadResult } from '@/utils/localFileEncoding';
import { ThemeAppearance } from '@chat2db/ui';

const jcefApi = {
  // Open web page
  openWebPage: (url: string) => {
    return createJcefApi('open-web-page', { url });
  },
  // Frontend ready
  handleJavaMessageIsReady: () => {
    return createJcefApi('handle-java-message-is-ready');
  },
  // Reveal a file in the system file manager
  revealInExplorer: (path: string) => {
    return createJcefApi('reveal-in-explorer', { path });
  },
  // Get file URL
  selectDirectory: () => {
    return createJcefApi('select-directory');
  },
  // Select SQL file directory
  selectSqlDirectory: () => {
    return createJcefApi('select-sql-directory');
  },
  // Open the recorded SQL file directory
  openSqlDirectory: (params: { path: string }) => {
    return createJcefApi('open-sql-directory', params);
  },
  // Get SQL file directory subnodes
  getSqlDirectoryChildren: (params: { rootToken: string; relativePath: string }) => {
    return createJcefApi('get-sql-directory-children', params);
  },
  // Read a binary preview file from an opened directory
  readSqlDirectoryPreview: (params: { rootToken: string; relativePath: string }) => {
    return createJcefApi<{ url: string; mimeType: string; size: number; etag: string }>(
      'read-sql-directory-preview',
      params,
    );
  },
  // Create a new SQL file directory subnode
  createSqlDirectoryChild: (params: {
    rootToken: string;
    parentRelativePath: string;
    name: string;
    type: 'file' | 'directory';
  }) => {
    return createJcefApi('create-sql-directory-child', params);
  },
  // Save the SQL file to the opened SQL directory
  saveSqlDirectoryFile: (params: { rootToken: string; parentRelativePath: string; name: string; content: string }) => {
    return createJcefApi('save-sql-directory-file', params);
  },
  // Rename SQL file directory subnode
  renameSqlDirectoryChild: (params: { rootToken: string; relativePath: string; name: string }) => {
    return createJcefApi('rename-sql-directory-child', params);
  },
  // Delete SQL file directory subnodes
  deleteSqlDirectoryChild: (params: { rootToken: string; relativePath: string }) => {
    return createJcefApi('delete-sql-directory-child', params);
  },
  // Open the SQL file directory in the terminal
  openSqlDirectoryTerminal: (params: { rootToken: string; relativePath: string }) => {
    return createJcefApi('open-sql-directory-terminal', params);
  },
  createSqlDirectoryTerminal: (params: {
    rootToken: string;
    relativePath: string;
    columns: number;
    rows: number;
    shellId?: string;
  }) => {
    return createJcefApi<{ sessionId: string; cwd: string; shell: string; shellId: string }>(
      'create-sql-directory-terminal',
      params,
    );
  },
  createTerminal: (params: { columns: number; rows: number; shellId?: string }) => {
    return createJcefApi<{ sessionId: string; cwd: string; shell: string; shellId: string }>('create-terminal', params);
  },
  duplicateTerminal: (params: { sessionId: string; columns: number; rows: number }) => {
    return createJcefApi<{ sessionId: string; cwd: string; shell: string; shellId: string }>(
      'duplicate-terminal',
      params,
    );
  },
  getTerminalCapabilities: () => {
    return createJcefApi<{
      os: 'mac' | 'windows' | 'linux';
      shells: Array<{ id: string; label: string; available: boolean }>;
    }>('get-terminal-capabilities');
  },
  writeTerminal: (params: { sessionId: string; data: string }) => {
    return createJcefApi('write-terminal', params);
  },
  attachTerminal: (params: { sessionId: string; consumerId: string }) => {
    return createJcefApi('attach-terminal', params);
  },
  detachTerminal: (params: { sessionId: string; consumerId: string }) => {
    return createJcefApi('detach-terminal', params);
  },
  acknowledgeTerminalOutput: (params: { sessionId: string; sequence: number }) => {
    return createJcefApi('ack-terminal-output', params);
  },
  resizeTerminal: (params: { sessionId: string; columns: number; rows: number }) => {
    return createJcefApi('resize-terminal', params);
  },
  getTerminalStatus: (params: { sessionId: string }) => {
    return createJcefApi<{ alive: boolean; busy: boolean }>('get-terminal-status', params);
  },
  getTerminalStatuses: (params: { sessionIds: string[] }) => {
    return createJcefApi<Record<string, { alive: boolean; busy: boolean }>>('get-terminal-statuses', params);
  },
  killTerminal: (params: { sessionId: string }) => {
    return createJcefApi('kill-terminal', params);
  },
  killTerminals: (params: { sessionIds: string[] }) => {
    return createJcefApi('kill-terminals', params);
  },
  // Select file
  selectFile: (params: { fileTypeList: string[]; fileSize?: number; multiple?: boolean }) => {
    return createJcefApi('select-file', params);
  },
  // Select and read TLS material without returning a local path
  selectTlsFileContent: (params: { fileTypeList: string[]; mode: 'text' | 'base64'; fileSize?: number }) => {
    return createJcefApi<{ fileName: string; content: string; size: number } | null>('select-tls-file-content', params);
  },
  // maximize
  maximizeWindow: () => {
    return createJcefApi('maximize-window');
  },
  // minimize
  minimizeWindow: () => {
    return createJcefApi('minimize-window');
  },
  // Double-click the AppBar
  handleDoubleClickAppBar: () => {
    return createJcefApi<boolean>('double-click-app-bar');
  },
  // close window
  closeWindow: () => {
    return createJcefApi('close-window');
  },
  acknowledgeApplicationExit: (data: { operationId: string }) => {
    return createJcefApi<boolean>('acknowledge-application-exit', data);
  },
  confirmCloseWindow: (data: { operationId: string }) => {
    return createJcefApi<boolean>('confirm-close-window', data);
  },
  cancelApplicationExit: (data: { operationId: string }) => {
    return createJcefApi<boolean>('cancel-application-exit', data);
  },
  // Is it maximizing
  isWindowMaximized: () => {
    return createJcefApi<boolean>('is-window-maximized');
  },
  // Is the macOS window in native full screen mode?
  isWindowFullScreen: () => {
    return createJcefApi<boolean>('is-window-full-screen');
  },
  // Check for updates
  appCheckUpdate: () => {
    return createJcefApi<IUpdateDetail>('app-check-update');
  },
  // Start downloading hot updates
  triggerDownload: () => {
    return createJcefApi<boolean>('trigger-download');
  },
  // Start hot update installation
  triggerInstallation: () => {
    return createJcefApi<boolean>('trigger-installation');
  },
  updatePreferences: (data?: { receiveBeta: boolean }) => {
    return createJcefApi<IUpdatePreferences>('update-preferences', data);
  },
  getUpdateRecoveryStatus: () => {
    return createJcefApi<IUpdateRecoveryStatus>('update-recovery-status');
  },
  openUpdateRecoveryLog: () => {
    return createJcefApi<boolean>('open-update-recovery-log');
  },
  // Restart app
  restartApp: (data?: { operationId?: string }) => {
    return createJcefApi<McpRestartResult>('restart-app', data);
  },
  // Set zoom
  webFrameSetZoom: (data: { action: 'zoomIn' | 'zoomOut' | 'zoomReset' }) => {
    return createJcefApi('web-frame-set-zoom', data);
  },
  setWorkspaceResizeCursor: (cursor: 'ns-resize' | 'ew-resize' | 'default', sequence: number) => {
    return createJcefApi('set-workspace-resize-cursor', { cursor, sequence });
  },
  // Open log
  openLog: () => {
    return createJcefApi('open-log');
  },
  // Open developer tools
  openDevTools: () => {
    return createJcefApi('open-dev-tools');
  },
  // Get mac address
  getMacAddress: () => {
    return createJcefApi('get-mac-address');
  },
  // save file
  saveFile: (data: { fileName: string; fileContent: string; fileType: string }) => {
    return createJcefApi<{ path: string; size: number } | null>('save-file', data);
  },
  // Change file content
  updateFileContent: (data: { filePath: string; fileContent: string; charset?: string; bom?: boolean }) => {
    return createJcefApi<boolean>('update-file-content', data);
  },
  // Open local file
  readFile: (path: string, charset?: string) => {
    return createJcefApi<LocalFileReadResult>('read-file', { path, charsets: charset });
  },
  // The front-end setting information is synchronized with the back-end
  updateSettings: (data: { appearance: ThemeAppearance; language: LangType }) => {
    return createJcefApi('update-settings', data);
  },
  // Get clipboard information
  readClipboard: () => {
    return createJcefApi<string>('read-clipboard');
  },
  // Get MCP token
  getMcpToken: () => {
    return createJcefApi<string>('get-mcp-token');
  },
  // Reset MCP token
  resetMcpToken: () => {
    return createJcefApi<string>('reset-mcp-token');
  },
  getMcpStatus: (data: { operationId: string }) => {
    return createJcefApi<McpStatus>('get-mcp-status', data);
  },
  setMcpEnabled: (data: { operationId: string; enabled: boolean }) => {
    return createJcefApi<McpStatus>('set-mcp-enabled', data);
  },
};

export default jcefApi;
