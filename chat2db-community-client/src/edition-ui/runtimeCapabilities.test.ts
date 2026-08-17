import assert from 'node:assert/strict';
import { canShareDashboard, resolveRuntimeEditionCapabilities } from './runtimeCapabilities';

const commercialConfig = {
  aiDataCollection: true,
  dashboardShare: true,
  dashboardHostedAiGenerate: true,
};

const online = resolveRuntimeEditionCapabilities(commercialConfig, false);
assert.deepEqual(online, {
  aiDataCollection: true,
  dashboardShare: true,
  dashboardHostedAiGenerate: true,
});

const offlineCertificate = resolveRuntimeEditionCapabilities(commercialConfig, true);
assert.deepEqual(offlineCertificate, {
  aiDataCollection: false,
  dashboardShare: false,
  dashboardHostedAiGenerate: true,
});

const community = resolveRuntimeEditionCapabilities(
  {
    aiDataCollection: false,
    dashboardShare: false,
    dashboardHostedAiGenerate: false,
  },
  false,
);
assert.deepEqual(community, {
  aiDataCollection: false,
  dashboardShare: false,
  dashboardHostedAiGenerate: false,
});

assert.equal(canShareDashboard(online, 'TEAM'), true);
assert.equal(canShareDashboard(online, 'ENTERPRISE'), true);
assert.equal(canShareDashboard(online, 'PERSONAL'), false);
assert.equal(canShareDashboard(online), false);
assert.equal(canShareDashboard(offlineCertificate, 'TEAM'), false);

console.log('Runtime capability tests passed');
