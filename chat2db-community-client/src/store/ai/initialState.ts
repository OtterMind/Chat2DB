import { PanelState, initPanelState } from './slices/panel/initialState';
import { ModelState, initModelState } from './slices/model/initialState';

export type AIState = PanelState & ModelState;

export const initialState: AIState = {
  ...initPanelState,
  ...initModelState,
};
