import assert from 'node:assert';
import { initConfigState, nextPanelLeftLayout } from './initialState';

const collapsedAfterCollapse = nextPanelLeftLayout({
  ...initConfigState.layout,
  panelLeftWidth: 420,
});

assert.equal(collapsedAfterCollapse.panelLeft, false, 'collapsing hides the left panel');
assert.equal(collapsedAfterCollapse.panelLeftWidth, 0, 'collapsing zeroes the live width');
assert.equal(
  collapsedAfterCollapse.lastPanelLeftWidth,
  420,
  'collapsing must remember the custom width instead of destroying it',
);

const restoredAfterExpand = nextPanelLeftLayout({
  ...initConfigState.layout,
  panelLeft: false,
  panelLeftWidth: 0,
  lastPanelLeftWidth: 420,
});

assert.equal(restoredAfterExpand.panelLeft, true, 'expanding shows the left panel');
assert.equal(
  restoredAfterExpand.panelLeftWidth,
  420,
  'expanding must restore the remembered custom width, not the 260px default',
);

const defaultWhenNothingRemembered = nextPanelLeftLayout({
  ...initConfigState.layout,
  panelLeft: false,
  panelLeftWidth: 0,
  lastPanelLeftWidth: 0,
});

assert.equal(
  defaultWhenNothingRemembered.panelLeftWidth,
  initConfigState.layout.panelLeftWidth,
  'expanding with nothing remembered falls back to the default width',
);

console.log('panel left layout tests passed');
