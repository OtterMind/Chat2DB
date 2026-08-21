import { HookAPI } from 'antd/es/modal/useModal';
export interface MiscState {
  /** Trigger fireworks */
  triggerConfetti: boolean;
  deleteModal: HookAPI | null;
  /** Workspace empty status AI introduction is closed */
  workspaceAiIntroDismissed: boolean;
}

export const initialMiscState: MiscState = {
  triggerConfetti: false,
  deleteModal: null,
  workspaceAiIntroDismissed: false,
};
