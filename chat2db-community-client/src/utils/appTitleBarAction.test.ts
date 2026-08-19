import assert from 'node:assert/strict';

import {
  APP_TITLE_BAR_ACTION_EVENT,
  APP_TITLE_BAR_ACTIONS,
  AppTitleBarActionEventDetail,
  isAppTitleBarAction,
  requestAppTitleBarAction,
} from './appTitleBarAction';

assert.deepEqual(APP_TITLE_BAR_ACTIONS, ['stream', 'workspace', 'dashboard', 'settings']);
assert.equal(isAppTitleBarAction('workspace'), true);
assert.equal(isAppTitleBarAction('setting'), false);

const handledTarget = new EventTarget();
let receivedAction: string | undefined;
handledTarget.addEventListener(APP_TITLE_BAR_ACTION_EVENT, (event) => {
  receivedAction = (event as CustomEvent<AppTitleBarActionEventDetail>).detail.action;
  event.preventDefault();
});

assert.equal(requestAppTitleBarAction('settings', handledTarget), true);
assert.equal(receivedAction, 'settings');
assert.equal(requestAppTitleBarAction('stream', new EventTarget()), false);

console.log('App title bar action tests passed.');
