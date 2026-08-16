import { AIState, initAIState } from './slices/ai/initialState';
import { CommonState, initCommonState } from './slices/common/initialState';
import { ChatDetailState, initChatDetailState } from './slices/chatDetails/initialState';

export type ChatState = CommonState & AIState & ChatDetailState;

export const initialState: ChatState = {
  ...initCommonState,
  ...initAIState,
  ...initChatDetailState,
};
