import assert from 'node:assert/strict';
import { shouldProbeDesktopBridge, type DesktopBridgeEnvironment } from './desktopBridge';

const environment = (overrides: Partial<DesktopBridgeEnvironment>): DesktopBridgeEnvironment => ({
  isWebEnv: false,
  isDesktopEnv: false,
  isOfflineEnv: false,
  isCommunityEnv: false,
  isDesktop: false,
  ...overrides,
});

assert.equal(shouldProbeDesktopBridge(environment({ isDesktopEnv: true })), true, 'Pro probes the bridge');
assert.equal(shouldProbeDesktopBridge(environment({ isOfflineEnv: true })), true, 'Local probes the bridge');
assert.equal(shouldProbeDesktopBridge(environment({ isCommunityEnv: true })), true, 'Community probes the bridge');
assert.equal(shouldProbeDesktopBridge(environment({ isDesktop: true })), true, 'an injected bridge is probed');
assert.equal(shouldProbeDesktopBridge(environment({ isWebEnv: true })), false, 'Web never probes the bridge');

console.log('Desktop bridge environment tests passed');
