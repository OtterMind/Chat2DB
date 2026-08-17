export interface DesktopBridgeEnvironment {
  isWebEnv: boolean;
  isDesktopEnv: boolean;
  isOfflineEnv: boolean;
  isCommunityEnv: boolean;
  isDesktop: boolean;
}

export function shouldProbeDesktopBridge(environment: DesktopBridgeEnvironment) {
  return (
    !environment.isWebEnv &&
    (environment.isDesktopEnv ||
      environment.isOfflineEnv ||
      environment.isCommunityEnv ||
      environment.isDesktop)
  );
}
