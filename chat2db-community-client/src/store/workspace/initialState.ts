import { CommonState, initCommonState } from './slices/common/initialState';
import { ConfigState, initConfigState } from './slices/config/initialState';
import { ConsoleState, initConsoleState } from './slices/console/initialState';
import { ModalState, initModalState } from './slices/modal/initialState';

export type WorkspaceState = CommonState & ConfigState & ConsoleState & ModalState;

export const initialState: WorkspaceState = {
  ...initCommonState,
  ...initConfigState,
  ...initConsoleState,
  ...initModalState,
};
