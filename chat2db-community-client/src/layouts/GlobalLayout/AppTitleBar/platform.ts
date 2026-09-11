import { Platform } from '@/constants/os';

export function resolveTitleBarPlatform(runtimePlatform: Platform | undefined, userAgent: string) {
  // JCEF injects os_type. Browsers need a platform fallback to keep the same shell geometry.
  const useBrowserPlatform = !runtimePlatform;
  return {
    isMac: runtimePlatform === Platform.Mac || (useBrowserPlatform && /Mac/.test(userAgent)),
    isWindows: runtimePlatform === Platform.Windows || (useBrowserPlatform && /Windows/.test(userAgent)),
  };
}

export function shouldUseWindowsDesktopChrome(isWindows: boolean, isDesktop: boolean) {
  return isWindows && isDesktop;
}
