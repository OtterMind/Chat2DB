import { produce } from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { WorkspaceStore } from '../../store';
import { ConfigState, nextPanelLeftLayout } from './initialState';

export interface ConfigAction {
  togglePanelRight: (show?: boolean) => void;
  togglePanelLeft: () => void;
  setPanelLeftWidth: (width: number) => void;
  setPanelRightWidth: (width: number) => void;
}

export const createConfigAction: StateCreator<WorkspaceStore, [['zustand/devtools', never]], [], ConfigAction> = (
  set,
) => ({
  togglePanelRight: (show) => {
    set(
      produce((state: ConfigState) => {
        state.layout.panelRight = typeof show === 'boolean' ? show : !state.layout.panelRight;
      }),
    );
  },
  togglePanelLeft: () => {
    set(
      produce((state: ConfigState) => {
        const next = nextPanelLeftLayout(state.layout);
        state.layout.panelLeft = next.panelLeft;
        state.layout.panelLeftWidth = next.panelLeftWidth;
        state.layout.lastPanelLeftWidth = next.lastPanelLeftWidth;
      }),
    );
  },
  setPanelLeftWidth: (width: number) => {
    set(
      produce((state: ConfigState) => {
        state.layout.panelLeftWidth = width;
        state.layout.panelLeft = width > 0;
        if (width > 0) {
          state.layout.lastPanelLeftWidth = width;
        }
      }),
    );
  },
  setPanelRightWidth: (width: number) => {
    set(
      produce((state: ConfigState) => {
        state.layout.panelRightWidth = width;
      }),
    );
  },
});
