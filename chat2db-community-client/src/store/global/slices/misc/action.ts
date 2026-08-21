import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';
import { MiscState } from './initialState';

export interface MiscAction {
  setConfetti: (triggerConfetti: MiscState['triggerConfetti']) => void;
  setDeleteModal: (deleteModal: MiscState['deleteModal']) => void;
  setWorkspaceAiIntroDismissed: (dismissed: MiscState['workspaceAiIntroDismissed']) => void;
}

export const createMiscAction: StateCreator<GlobalStore, [['zustand/devtools', never]], [], MiscAction> = (set) => ({
  setConfetti: (triggerConfetti: MiscState['triggerConfetti']) => {
    set({
      triggerConfetti,
    });
  },
  setDeleteModal: (deleteModal: MiscState['deleteModal']) => {
    set({
      deleteModal,
    });
  },
  setWorkspaceAiIntroDismissed: (workspaceAiIntroDismissed) => {
    set({ workspaceAiIntroDismissed });
  },
});
