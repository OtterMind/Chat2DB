import type { ClientIdentity, ClientWorkspaceContext } from '@/client-context/types';

export const COMMUNITY_USER_ID = -1;
export const COMMUNITY_ORGANIZATION_ID = -1;
export const COMMUNITY_DISPLAY_NAME = 'Community Local User';

export const COMMUNITY_WORKSPACE_CONTEXT: ClientWorkspaceContext = {
  id: COMMUNITY_ORGANIZATION_ID,
  name: 'Community Local Workspace',
  kind: 'local',
  ownerId: COMMUNITY_USER_ID,
  createTime: 0,
  roleCodes: ['OWNER', 'ADMIN'],
  permissions: [],
  vip: true,
};

export const COMMUNITY_IDENTITY: ClientIdentity = {
  id: COMMUNITY_USER_ID,
  displayName: COMMUNITY_DISPLAY_NAME,
  avatar: '',
  currentWorkspace: COMMUNITY_WORKSPACE_CONTEXT,
  email: 'community-local@chat2db.local',
  vip: true,
  activated: true,
};
