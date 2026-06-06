import { useWorkspaceStore } from './index';
export interface IConfigStore {
  layout: {
    panelLeft: boolean;
    panelLeftWidth: number;
    panelRight: boolean;
    panelRightWidth: number;
  };
}

export const initConfigStore: IConfigStore = {
  layout: {
    panelLeft: true,
    panelRight: true,
    panelLeftWidth: 220,
    panelRightWidth: 300,
  },
};

export const togglePanelRight = () => {
  return useWorkspaceStore.setState((state) => ({
    layout: {
      ...state.layout,
      panelRight: !state.layout.panelRight,
    },
  }));
};

export const togglePanelLeft = () => {
  return useWorkspaceStore.setState((state) => ({
    layout: {
      ...state.layout,
      panelLeft: !state.layout.panelLeft,
    },
  }));
};

export const setPanelLeft = (panelLeft: boolean) => {
  return useWorkspaceStore.setState((state) => ({
    layout: {
      ...state.layout,
      panelLeft,
    },
  }));
};

export const setPanelLeftWidth = (width: number) => {
  return useWorkspaceStore.setState((state) => ({
    layout: {
      ...state.layout,
      panelLeftWidth: width,
    },
  }));
};

export const setPanelRightWidth = (width: number) => {
  return useWorkspaceStore.setState((state) => ({
    layout: {
      ...state.layout,
      panelRightWidth: width,
    },
  }));
};
