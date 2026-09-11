import 'umi/typings';
import { IVersionResponse } from '@/typings';
import { Platform } from '@/constants/os';

declare module 'monaco-editor/esm/vs/basic-languages/sql/sql';
declare module 'monaco-editor/esm/vs/language/typescript/ts.worker.js';
declare module 'monaco-editor/esm/vs/editor/editor.worker.js';
declare namespace NodeJS {
  interface ProcessEnv {
    readonly NODE_ENV: 'development' | 'production';
    readonly UMI_ENV: string;
    readonly __ENV: string;
  }
}

declare global {
  interface Window {
    _Lang: string;
    _APP_PORT: string;
    _BUILD_TIME: string;
    _AppThemePack: { [key in string]: string };
    _appGatewayParams: IVersionResponse; // Gateway version response
    _notificationApi: any;
    _indexedDB: any;
    _appTitleBarHeight: number;
    _PRINT_LOGS: boolean; // Whether to print logs

    // JCEF bridge declarations
    /**
     * JCEF: Call Java methods from JavaScript.
     * @param query Object containing request data and callbacks.
     * @returns queryId (numeric type) for possible cancellation.
     */
    javaQuery: (query: {
      request: any; // Typically a JSON string, possibly compressed/Base64 encoded
      persistent?: boolean; // Check whether the query is persistent (CefMessageRouter related)
      onSuccess: (response: string) => void; // The string returned by Java after successful processing (usually JSON)
      onFailure: (errorCode: number, errorMessage: string) => void; // Failure callback of the JCEF communication bridge itself
    }) => number; // Returns a queryId

    /**
     * JCEF: Cancel a query previously initiated via javaQuery.
     * @param queryId The ID returned by javaQuery.
     */
    javaCancelQuery?: (queryId: number) => void;

    handleJavaMessage: (data: string) => void;
  }

  interface Navigator {
    app_language?: string;
    os_type: Platform;
  }

  // Global build constants
  const __APP_VERSION__: string; // Application version
  const __BUILD_TIME__: string; // Build time
  const __ENV__: string; // environment variables
  const __RUNTIME_ENV__: string; // runtime environment
  const __APP_NAME__: string; // Application name
  const __APP_CAPITAL_NAME__: string; // Application package name
  const __APP_DISPLAY_NAME__: string; // User-facing application name
  const __APP_PROTOCOL_SCHEME__: string; // Desktop URL protocol
  const __STORAGE_KEY_PREFIX__: string; // Local storage namespace selected by the build profile
  const __STORAGE_VERSION_KEY__: string; // Local storage schema-version key selected by the build profile
  const __GATEWAY_URL__: string; // Gateway URL
  const __PRINT_LOGS__: boolean; // Whether to print log
  const __WEBAPP__: boolean; // Is it a web application?
}

declare module '*.sql' {
  const content: string;
  export default content;
}
