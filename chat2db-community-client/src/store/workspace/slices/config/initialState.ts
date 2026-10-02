export interface ConfigState {
  layout: {
    panelLeft: boolean;
    panelLeftWidth: number;
    panelRight: boolean;
    panelRightWidth: number;
    /** Last non-zero left panel width, so expanding restores the user's width. */
    lastPanelLeftWidth: number;
  };
}

export const initConfigState: ConfigState = {
  layout: {
    panelLeft: true,
    panelRight: true,
    panelLeftWidth: 260,
    panelRightWidth: 300,
    lastPanelLeftWidth: 260,
  },
};

export interface PanelLeftToggleLayout {
  panelLeft: boolean;
  panelLeftWidth: number;
  lastPanelLeftWidth: number;
}

/**
 * Collapsing must not destroy the user's custom width, and expanding must
 * restore it instead of resetting to the default 260px.
 */
export function nextPanelLeftLayout(layout: ConfigState['layout']): PanelLeftToggleLayout {
  if (layout.panelLeftWidth > 0) {
    return {
      panelLeft: false,
      panelLeftWidth: 0,
      lastPanelLeftWidth: layout.panelLeftWidth,
    };
  }
  const remembered =
    layout.lastPanelLeftWidth > 0 ? layout.lastPanelLeftWidth : initConfigState.layout.panelLeftWidth;
  return {
    panelLeft: true,
    panelLeftWidth: remembered,
    lastPanelLeftWidth: remembered,
  };
}
