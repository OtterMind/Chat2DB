import { IconButton } from '@chat2db/ui';
import { Dropdown, type MenuProps } from 'antd';
import { AlignJustify } from 'lucide-react';
import { useMemo, type MouseEvent } from 'react';

import { COMMUNITY_TITLE_BAR_BUTTON_SIZE } from '@/constants/mainLayout';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { refreshPage } from '@/utils';
import { openWebPage } from '@/utils/url';

import { useStyles } from './style';

const CommunityAppMenu = () => {
  const { styles } = useStyles();
  const appUrlConfig = useGlobalStore((state) => state.appUrlConfig);

  const items = useMemo<MenuProps['items']>(
    () => [
      {
        key: 'view',
        label: i18n('common.menu.view'),
        children: [
          {
            key: 'refresh',
            label: i18n('common.text.refreshPage'),
            onClick: refreshPage,
          },
        ],
      },
      {
        key: 'help',
        label: i18n('common.menu.help'),
        children: [
          {
            key: 'open-log',
            label: i18n('common.menu.openLog'),
            onClick: () => jcefApi.openLog(),
          },
          { type: 'divider' },
          {
            key: 'visit-website',
            label: i18n('common.menu.visitWebsite'),
            onClick: () => openWebPage(appUrlConfig.WEBSITE_URL),
          },
          {
            key: 'view-docs',
            label: i18n('common.menu.viewDocs'),
            onClick: () => openWebPage(appUrlConfig.DOCS_URL),
          },
          {
            key: 'view-changelog',
            label: i18n('common.menu.viewChangelog'),
            onClick: () => openWebPage(appUrlConfig.CHANGE_LOG_URL),
          },
        ],
      },
    ],
    [appUrlConfig.CHANGE_LOG_URL, appUrlConfig.DOCS_URL, appUrlConfig.WEBSITE_URL],
  );

  const stopWindowGesture = (event: MouseEvent<HTMLDivElement>) => {
    event.stopPropagation();
  };

  return (
    <Dropdown destroyPopupOnHide menu={{ items }} placement="bottomLeft" trigger={['click']}>
      <div className={styles.communityMenuTrigger} onClick={stopWindowGesture} onDoubleClick={stopWindowGesture}>
        <IconButton
          type="primary"
          size={COMMUNITY_TITLE_BAR_BUTTON_SIZE}
          title={i18n('common.menu.main')}
          icon={AlignJustify}
          tooltipPlacement="bottom"
        />
      </div>
    </Dropdown>
  );
};

export default CommunityAppMenu;
