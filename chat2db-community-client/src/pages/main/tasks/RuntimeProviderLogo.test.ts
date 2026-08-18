import assert from 'node:assert/strict';

import type { AgentRuntimeOption } from '@/service/agent';

import {
  agentRuntimeAvatar,
  avatarRuntimeProvider,
  CHAT2DB_AGENT_AVATAR,
  isDefaultAgentAvatar,
  runtimeProviderAvatar,
} from './RuntimeProviderLogo';

const options: AgentRuntimeOption[] = [
  {
    profileId: 'local-codex',
    profileName: 'Local Codex',
    provider: 'CODEX',
    executable: '/usr/local/bin/codex',
    defaultProfile: true,
    installed: true,
    online: true,
  },
  {
    profileId: 'local-dsh',
    profileName: 'Local DSH',
    provider: 'DSH',
    executable: '/usr/local/bin/dsh',
    defaultProfile: false,
    installed: true,
    online: true,
  },
];

assert.equal(agentRuntimeAvatar('EMBEDDED_SPRING_AI', undefined, options), CHAT2DB_AGENT_AVATAR);
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'local-codex', options), runtimeProviderAvatar('CODEX'));
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'local-dsh', options), runtimeProviderAvatar('DSH'));
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'missing', options), undefined);

assert.equal(avatarRuntimeProvider(runtimeProviderAvatar('HERMES')), 'HERMES');
assert.equal(avatarRuntimeProvider('data:image/webp;base64,user-upload'), undefined);
assert.equal(isDefaultAgentAvatar(CHAT2DB_AGENT_AVATAR), true);
assert.equal(isDefaultAgentAvatar(runtimeProviderAvatar('DSH')), true);
assert.equal(isDefaultAgentAvatar('data:image/webp;base64,user-upload'), false);

console.log('Runtime provider avatar tests passed.');
