import { clearDashboardStore } from './dashboard/store';
import { clearCommonStore } from './common/index';
import { clearConnectionStore } from './connection/index';
import { clearGlobalStore } from './global/store';
import { clearOrgStore } from './workspaceContext/index';
import { clearUserStore } from './session/index';
import { clearWorkspaceStore } from './workspace/index';
import { clearTreeStore } from './tree/index';

// Clean Store
export const clearStore = () => {
  clearDashboardStore();
  clearCommonStore();
  clearConnectionStore();
  clearGlobalStore();
  clearOrgStore();
  clearUserStore();
  clearWorkspaceStore();
  clearTreeStore();
};
