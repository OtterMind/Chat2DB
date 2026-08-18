import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { del, get, set } from 'idb-keyval';
import { devtools } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { resolveIndexedDbStorageKey } from './storageKey';

interface IndexDBState {
  indexDB: any;
}

const initialState: IndexDBState = {
  indexDB: null,
};

export interface IndexDBAction {
  setValue: (key: string, value: any) => Promise<void>;
  getValue: (key: string) => Promise<any>;
  deleteValue: (key: string) => Promise<void>;
}

export type IndexDBStore = IndexDBState & IndexDBAction;

export const createIndexDBAction: StateCreator<
  IndexDBStore,
  [['zustand/devtools', never]],
  [],
  IndexDBAction
> = () => ({
  setValue: (key, value) => {
    return set(resolveIndexedDbStorageKey(key, runtimeEditionConfig.indexedDbKeyPrefix), value);
  },
  getValue: (key) => {
    return get(resolveIndexedDbStorageKey(key, runtimeEditionConfig.indexedDbKeyPrefix));
  },
  deleteValue: (key) => {
    return del(resolveIndexedDbStorageKey(key, runtimeEditionConfig.indexedDbKeyPrefix));
  },
});

const createStore: StateCreator<IndexDBStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createIndexDBAction(...parameters),
});

export const useIndexDBStore = createWithEqualityFn<IndexDBStore>()(
  // persist(
  devtools(createStore, {
    name: 'Chat2DB_indexDB_Store',
  }),
  // ),
  shallow,
);

// Clean store
export const clearIndexDBStore = () => {
  useIndexDBStore.setState(initialState);
};
