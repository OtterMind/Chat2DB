import { clientRuntime } from '@client-runtime';
import type { ClientIdentity } from '@/client-context/types';
import { devtools, persist, type PersistOptions } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';

interface SessionState {
  curUser: ClientIdentity | null;
  networkAbandoned: boolean;
}

interface SessionAction {
  setCurUser: (identity: Partial<ClientIdentity>) => void;
  updateUser: (identity: Partial<ClientIdentity>) => Promise<void>;
  queryCurUser: () => Promise<ClientIdentity>;
  clearUserStore: () => void;
  isCurrentUser: (id: number) => boolean;
  isCurrentUserOrAdmin: (id: number) => boolean;
}

export type SessionStore = SessionState & SessionAction;

const initialState: SessionState = {
  curUser: clientRuntime.fixedIdentity ?? null,
  networkAbandoned: false,
};

type SessionPersist = Pick<SessionStore, 'curUser'>;
const persistOptions: PersistOptions<SessionStore, SessionPersist> = {
  name: clientRuntime.userStoreName,
  partialize: (state) => ({ curUser: state.curUser }),
};

export const useUserStore = createWithEqualityFn<SessionStore>()(
  persist(
    devtools(
      (set, get) => ({
        ...initialState,
        setCurUser: (identity) => set({ curUser: { ...(get().curUser ?? clientRuntime.fixedIdentity!), ...identity } }),
        updateUser: async (identity) => get().setCurUser(identity),
        queryCurUser: async () => {
          const identity = clientRuntime.fixedIdentity;
          if (!identity) {
            throw new Error('The Community runtime requires a local identity.');
          }
          set({ curUser: identity, networkAbandoned: false });
          return identity;
        },
        clearUserStore: () => set(initialState),
        isCurrentUser: (id) => get().curUser?.id === id,
        isCurrentUserOrAdmin: (id) => get().curUser?.id === id || clientRuntime.usesFixedIdentity,
      }),
      { name: clientRuntime.userStoreName },
    ),
    persistOptions,
  ),
  shallow,
);

export const clearUserStore = () => useUserStore.setState(initialState);
