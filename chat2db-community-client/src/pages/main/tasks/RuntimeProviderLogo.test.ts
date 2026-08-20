import assert from 'node:assert/strict';

import type { AgentRuntimeOption } from '@/service/agent';

import {
  agentRuntimeAvatar,
  agentAvatarSource,
  avatarRuntimeProvider,
  CHAT2DB_AGENT_AVATAR,
  isDefaultAgentAvatar,
  LEGACY_CHAT2DB_AGENT_AVATAR,
  runtimeProviderAvatar,
} from './RuntimeProviderLogo';

const options: AgentRuntimeOption[] = [
  {
    profileId: 'local-claude',
    profileName: 'Local Claude Code',
    provider: 'CLAUDE_CODE',
    executable: '/usr/local/bin/claude',
    defaultProfile: true,
    installed: true,
    online: true,
  },
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

assert.equal(CHAT2DB_AGENT_AVATAR, './logo-transparent.webp');
assert.equal(agentRuntimeAvatar('EMBEDDED_SPRING_AI', undefined, options), CHAT2DB_AGENT_AVATAR);
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'local-claude', options), runtimeProviderAvatar('CLAUDE_CODE'));
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'local-codex', options), runtimeProviderAvatar('CODEX'));
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'local-dsh', options), runtimeProviderAvatar('DSH'));
assert.equal(agentRuntimeAvatar('EXTERNAL_AGENT', 'missing', options), undefined);

assert.equal(avatarRuntimeProvider(runtimeProviderAvatar('HERMES')), 'HERMES');
assert.equal(avatarRuntimeProvider(runtimeProviderAvatar('OPENCODE')), 'OPENCODE');
assert.equal(avatarRuntimeProvider(runtimeProviderAvatar('PI')), 'PI');
assert.equal(avatarRuntimeProvider('data:image/webp;base64,user-upload'), undefined);
assert.equal(agentAvatarSource(LEGACY_CHAT2DB_AGENT_AVATAR), CHAT2DB_AGENT_AVATAR);
assert.equal(agentAvatarSource('data:image/webp;base64,user-upload'), 'data:image/webp;base64,user-upload');
assert.equal(isDefaultAgentAvatar(CHAT2DB_AGENT_AVATAR), true);
assert.equal(isDefaultAgentAvatar(LEGACY_CHAT2DB_AGENT_AVATAR), true);
assert.equal(isDefaultAgentAvatar(runtimeProviderAvatar('DSH')), true);
assert.equal(isDefaultAgentAvatar('data:image/webp;base64,user-upload'), false);

console.log('Runtime provider avatar tests passed.');
