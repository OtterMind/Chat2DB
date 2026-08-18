import { GuideDialogStatus } from '@/components/GuideDialog/type';
import { SubscriptionType } from '@/constants/subscriptionType';
import { ErrorCode } from '@/constants';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import oauthServices from '@/service/enterprise/oauth';
import userServices from '@/service/enterprise/user';
import { IUserVO, Subscription } from '@/typings/enterprise/user';
import { isOfflineEnv } from '@/utils/env';
import { isEmbedIframePage } from '@/utils/iframe';
import dayjs from 'dayjs';
import { PersistOptions, devtools, persist } from 'zustand/middleware';
import { shallow } from 'zustand/shallow';
import { createWithEqualityFn } from 'zustand/traditional';
import { StateCreator } from 'zustand/vanilla';
import { useGlobalStore } from '../global';
import { useOrgStore } from '../organization';

interface UserState {
  curUser?: IUserVO | null;
  subscriptionList?: Subscription[];
  subscriptModalStatus?: boolean;
  subscriptType: SubscriptionType;
  pricingModalStatus?: boolean | ErrorCode; // may be an error code
  //Whether it is offline activation means that you cannot use any functions online
  networkAbandoned: boolean;
}

const initialState: UserState = {
  curUser: null,
  subscriptionList: [],
  subscriptModalStatus: false,
  subscriptType: SubscriptionType.PersonalUpdate,
  pricingModalStatus: false,
  networkAbandoned: false,
};

export interface UserAction {
  setCurUser: (curUser: Partial<IUserVO>) => void;
  updateUser: (curUser: Partial<IUserVO>) => Promise<void>;
  queryCurUser: () => Promise<IUserVO>;
  setSubscriptModalStatus: (status: boolean) => void;
  setPricingModalStatus: (status: boolean | ErrorCode) => void;
  setSubscriptType: (type: SubscriptionType) => void;
  clearUserStore: () => void;
  isCurrentUser: (id: number) => boolean;
  isCurrentUserOrAdmin: (id: number) => boolean;
}

export type UserStore = UserState & UserAction;

export const createUserAction: StateCreator<UserStore, [['zustand/devtools', never]], [], UserAction> = (set, get) => ({
  setCurUser: (data) => {
    const curUser = get().curUser || runtimeEditionConfig.fixedUser;
    set({
      curUser: curUser ? { ...curUser, ...data } : (data as IUserVO),
    });
  },
  updateUser: (data) => {
    if (runtimeEditionConfig.usesFixedIdentity) {
      get().setCurUser(data);
      return Promise.resolve();
    }

    return userServices
      .updateUser({
        ...data,
      })
      .then(() => {
        get().setCurUser(data);
      });
  },
  queryCurUser: async () => {
    if (runtimeEditionConfig.usesFixedIdentity && runtimeEditionConfig.fixedUser) {
      const { setCurOrg } = useOrgStore.getState();
      setCurOrg(runtimeEditionConfig.fixedUser.currentOrganization);
      set({
        curUser: runtimeEditionConfig.fixedUser,
        networkAbandoned: false,
      });
      return Promise.resolve(runtimeEditionConfig.fixedUser);
    }

    const res = await oauthServices.getUserInfo();
    set({
      curUser: res,
      networkAbandoned: Boolean(res?.networkAbandoned),
    });
    if (isOfflineEnv) {
      const { setCurOrg } = useOrgStore.getState();
      setCurOrg();
      if (!res) {
        // Offline environment, first time use
        useGlobalStore.getState().setOpenGuideDialog(true);
        useGlobalStore.getState().setGuideDialogStatus(GuideDialogStatus.OfflineTrial);
      } else if (!res.activated) {
        const trialDays = dayjs().diff(dayjs(res.trialStartTime), 'day');
        if (trialDays > 14) {
          // Handle cases where the trial period exceeds 14 days
          useGlobalStore.getState().setOpenGuideDialog(true);
          useGlobalStore.getState().setGuideDialogStatus(GuideDialogStatus.OfflineTrialExpired);
        }
      }
      return Promise.resolve(res);
    }

    // Query user subscription list
    const { querySubscriptionList, queryOrgList } = useOrgStore.getState();
    await querySubscriptionList();

    let orgList = useOrgStore.getState().orgList;

    // Set current user organization
    const { setCurOrg } = useOrgStore.getState();

    // Temporary writing method to prevent orgList from returning without request
    if (!orgList?.length) {
      await queryOrgList();
      orgList = useOrgStore.getState().orgList;
    }

    // The currentOrganization returned by the backend may be missing/not in the orgList:
    // 1. Give priority to matching organizations
    // 2. Otherwise, use the first item so curOrg remains defined and the sidebar stays consistent.
    // 3. When orgList is empty, the original curOrg remains unchanged.
    const matchedOrg = orgList?.find((item) => item.id === res?.currentOrganization?.id);
    const fallbackOrg = matchedOrg ?? orgList?.[0];
    if (fallbackOrg) {
      setCurOrg(fallbackOrg);
    }

    return Promise.resolve(res);
  },
  setSubscriptModalStatus: (status) => {
    set({ subscriptModalStatus: status });
  },
  setPricingModalStatus: (status) => {
    if (status && isEmbedIframePage()) {
      set({ pricingModalStatus: false });
      return;
    }

    set({ pricingModalStatus: status });
  },
  setSubscriptType: (type) => {
    set({ subscriptType: type });
  },
  clearUserStore: () => {
    set(initialState);
  },
  isCurrentUser: (id: number) => {
    return get().curUser?.id === id;
  },
  isCurrentUserOrAdmin: (id: number) => {
    return get().curUser?.id === id || useOrgStore.getState().isAdmin || runtimeEditionConfig.usesFixedIdentity;
  },
});

const createStore: StateCreator<UserStore, [['zustand/devtools', never]]> = (...parameters) => ({
  ...initialState,
  ...createUserAction(...parameters),
});

type GlobalPersist = Pick<UserStore, 'curUser'>;

// local-storage Options
const persistOptions: PersistOptions<UserStore, GlobalPersist> = {
  name: runtimeEditionConfig.userStoreName,
  partialize: (state) => ({
    curUser: state.curUser,
  }),
};

export const useUserStore = createWithEqualityFn<UserStore>()(
  persist(
    devtools(createStore, {
      name: runtimeEditionConfig.userStoreName,
    }),
    persistOptions,
  ),
  shallow,
);

// Clean store
export const clearUserStore = () => {
  useUserStore.setState(initialState);
};
