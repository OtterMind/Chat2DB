import React from 'react';
import { IframeType, ServiceStatus } from '@/constants/common';
import { APP_URL_CONFIG_OVERSEAS } from '@/constants/appConfig';
import { DEFAULT_MAIN_PAGE_ACTIVE_TAB } from '@/utils/mainPageNavigation';
import { isDesktop } from '@/utils/env';
export interface CommonState {
  /**
   * Service status
   */
  serviceStatus: ServiceStatus;
  /**
   *  APP title bar right component
   */
  appTitleBarRightComponent: React.ReactNode | null;
  /**
   * Main page active tab
   */
  mainPageActiveTab: string;
  /**
   * Focused content
   */
  focusedContent: any[][] | any[] | string | null;
  /**
   * SystemErrorMessage APi
   */
  systemErrorMessageApi: any;
  /**
   * Displayed Setting page
   */
  settingPageActiveTab: string | false;
  // APP_URL_CONFIG
  appUrlConfig: {
    WEBSITE_URL: string;
    DOWNLOAD_URL: string;
    CHAT2DB_APP_URL: string;
    DOCS_URL: string;
    CHANGE_LOG_URL: string;
    SERVICE_AGREEMENT: string;
    PRIVACY_POLICY: string;
    MEMBER_AGREEMENT: string;
    CURRENCY_SYMBOL: string;
  };
  // Unified confirmation box
  unifiedConfirmationModalInfo: {
    title: string;
    content?: React.ReactNode;
    onOk: (inputConfirmText?: string) => Promise<void>;
    onCancel?: () => void;
    needDoubleConfirmText?: string;
    needInputConfirmText?: string;
    inputConfirmLabel?: React.ReactNode;
    inputConfirmPlaceholder?: string;
    inputConfirmMismatchTip?: string;
    width?: number | string;
    headerIconCode?: string;
  } | null;
  isEmbedIframe: IframeType | null;
}

export const initialCommonState: CommonState = {
  appTitleBarRightComponent: null,
  mainPageActiveTab: DEFAULT_MAIN_PAGE_ACTIVE_TAB,
  focusedContent: null,
  serviceStatus: isDesktop ? ServiceStatus.PENDING : ServiceStatus.SUCCESS,
  systemErrorMessageApi: null,
  settingPageActiveTab: false,
  appUrlConfig: APP_URL_CONFIG_OVERSEAS,
  unifiedConfirmationModalInfo: null,
  isEmbedIframe: null,
};
