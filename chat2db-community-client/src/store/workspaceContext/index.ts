import { clientRuntime } from '@client-runtime';
import type { ClientWorkspaceContext } from '@/client-context/types';
import { devtools, persist, type PersistOptions } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';

interface WorkspaceContextState {
  curOrg: ClientWorkspaceContext | null;
  orgList: ClientWorkspaceContext[];
  openCreateOrJoinOrgDialog: boolean;
  isPersonal: boolean;
  isOwner: boolean;
  isAdmin: boolean;
  applyProps: unknown;
  orgNav: string;
}

interface WorkspaceContextAction {
  curIsPersonalOrg: () => boolean;
  queryOrgList: () => Promise<void>;
  setOpenCreateOrJoinOrgDialog: (open: boolean) => void;
  setCurOrg: (workspace?: ClientWorkspaceContext) => void;
  setApplyProps: (props: unknown) => void;
  setOrgNav: (nav: string) => void;
  clearOrgStore: () => void;
}

export type WorkspaceContextStore = WorkspaceContextState & WorkspaceContextAction;

const localWorkspace = clientRuntime.fixedWorkspaceContext ?? null;
const initialState: WorkspaceContextState = {
  curOrg: localWorkspace,
  orgList: localWorkspace ? [localWorkspace] : [],
  openCreateOrJoinOrgDialog: false,
  isPersonal: true,
  isOwner: true,
  isAdmin: true,
  applyProps: null,
  orgNav: '',
};

type WorkspaceContextPersist = Pick<WorkspaceContextStore, 'curOrg' | 'orgList'>;
const persistOptions: PersistOptions<WorkspaceContextStore, WorkspaceContextPersist> = {
  name: clientRuntime.orgStoreName,
  partialize: (state) => ({ curOrg: state.curOrg, orgList: state.orgList }),
};

export const useOrgStore = createWithEqualityFn<WorkspaceContextStore>()(
  persist(
    devtools(
      (set) => ({
        ...initialState,
        curIsPersonalOrg: () => true,
        queryOrgList: async () => set(initialState),
        setOpenCreateOrJoinOrgDialog: (open) => set({ openCreateOrJoinOrgDialog: open }),
        setCurOrg: (workspace) => set({ curOrg: workspace ?? localWorkspace }),
        setApplyProps: (applyProps) => set({ applyProps }),
        setOrgNav: (orgNav) => set({ orgNav }),
        clearOrgStore: () => set(initialState),
      }),
      { name: clientRuntime.orgStoreName },
    ),
    persistOptions,
  ),
  shallow,
);

export const clearOrgStore = () => useOrgStore.setState(initialState);
