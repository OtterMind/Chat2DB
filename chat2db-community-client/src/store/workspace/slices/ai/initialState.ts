export interface defaultDataCollectionItem {
  [key: number]: number;
}

export interface AIState {
  // Store the default AI dataset value.
  defaultDataCollectionList: {
    dashboard: defaultDataCollectionItem;
    console: defaultDataCollectionItem;
    chat: defaultDataCollectionItem;
  };
  createAiDataCollectionTipsCount: number;
}

export const initAIState: AIState = {
  defaultDataCollectionList: {
    dashboard: {},
    console: {},
    chat: {},
  },
  createAiDataCollectionTipsCount: 0,
};
