import { produce } from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { WorkspaceStore } from '../../store';
import { ConfigState, initConfigState } from './initialState';

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
        const show = state.layout.panelLeftWidth === 0;
        state.layout.panelLeft = show;
        state.layout.panelLeftWidth = show ? initConfigState.layout.panelLeftWidth : 0;
      }),
    );
  },
  setPanelLeftWidth: (width: number) => {
    set(
      produce((state: ConfigState) => {
        state.layout.panelLeftWidth = width;
        state.layout.panelLeft = width > 0;
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
