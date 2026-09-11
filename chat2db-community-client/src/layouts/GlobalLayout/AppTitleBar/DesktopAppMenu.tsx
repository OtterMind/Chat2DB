import { IconButton } from '@chat2db/ui';
import { Dropdown, type MenuProps } from 'antd';
import { AlignJustify } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react';

import ProductLogo from '@/components/Logo';
import { COMMUNITY_MAIN_ACTION_BUTTON_SIZE } from '@/constants/mainLayout';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { useGlobalStore } from '@/store/global';
import { refreshPage } from '@/utils';
import { openWebPage } from '@/utils/url';

import { useStyles } from './style';

const DesktopAppMenu = () => {
  const { styles } = useStyles();
  const appUrlConfig = useGlobalStore((state) => state.appUrlConfig);
  const menuRef = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [openMenu, setOpenMenu] = useState<'view' | 'help' | null>(null);

  const closeMenu = useCallback(() => {
    setExpanded(false);
    setOpenMenu(null);
  }, []);

  const runAndClose = useCallback(
    (action: () => unknown) => () => {
      closeMenu();
      action();
    },
    [closeMenu],
  );

  const viewItems = useMemo<MenuProps['items']>(
    () => [
      {
        key: 'refresh',
        label: i18n('common.text.refreshPage'),
        onClick: runAndClose(refreshPage),
      },
    ],
    [runAndClose],
  );

  const helpItems = useMemo<MenuProps['items']>(
    () => [
      {
        key: 'open-log',
        label: i18n('common.menu.openLog'),
        onClick: runAndClose(() => jcefApi.openLog()),
      },
      { type: 'divider' },
      {
        key: 'visit-website',
        label: i18n('common.menu.visitWebsite'),
        onClick: runAndClose(() => openWebPage(appUrlConfig.WEBSITE_URL)),
      },
      {
        key: 'view-docs',
        label: i18n('common.menu.viewDocs'),
        onClick: runAndClose(() => openWebPage(appUrlConfig.DOCS_URL)),
      },
      {
        key: 'view-changelog',
        label: i18n('common.menu.viewChangelog'),
        onClick: runAndClose(() => openWebPage(appUrlConfig.CHANGE_LOG_URL)),
      },
    ],
    [appUrlConfig.CHANGE_LOG_URL, appUrlConfig.DOCS_URL, appUrlConfig.WEBSITE_URL, runAndClose],
  );

  const stopWindowGesture = (event: MouseEvent<HTMLDivElement>) => {
    event.stopPropagation();
  };

  useEffect(() => {
    if (!expanded) {
      return;
    }

    const handleDocumentMouseDown = (event: globalThis.MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Node) || menuRef.current?.contains(target)) {
        return;
      }
      if (target instanceof Element && target.closest('.ant-dropdown')) {
        return;
      }
      closeMenu();
    };

    document.addEventListener('mousedown', handleDocumentMouseDown);
    return () => document.removeEventListener('mousedown', handleDocumentMouseDown);
  }, [closeMenu, expanded]);

  const toggleExpanded = () => {
    setExpanded((current) => {
      if (current) {
        setOpenMenu(null);
      }
      return !current;
    });
  };

  const switchOpenMenu = (menu: 'view' | 'help') => {
    if (openMenu) {
      setOpenMenu(menu);
    }
  };

  return (
    <div ref={menuRef} className={styles.desktopMenuContent} onDoubleClick={stopWindowGesture}>
      <span className={styles.desktopMenuLogoSlot}>
        <ProductLogo className={styles.desktopMenuLogo} size={24} />
      </span>
      {expanded ? (
        <div className={styles.desktopMenuBar}>
          <Dropdown
            destroyPopupOnHide
            menu={{ items: viewItems }}
            open={openMenu === 'view'}
            placement="bottomLeft"
            trigger={['click']}
            onOpenChange={(open) => setOpenMenu(open ? 'view' : null)}
          >
            <button
              type="button"
              className={styles.desktopMenuItem}
              onMouseEnter={() => switchOpenMenu('view')}
            >
              {i18n('common.menu.view')}
            </button>
          </Dropdown>
          <Dropdown
            destroyPopupOnHide
            menu={{ items: helpItems }}
            open={openMenu === 'help'}
            placement="bottomLeft"
            trigger={['click']}
            onOpenChange={(open) => setOpenMenu(open ? 'help' : null)}
          >
            <button
              type="button"
              className={styles.desktopMenuItem}
              onMouseEnter={() => switchOpenMenu('help')}
            >
              {i18n('common.menu.help')}
            </button>
          </Dropdown>
        </div>
      ) : (
        <IconButton
          type="primary"
          size={COMMUNITY_MAIN_ACTION_BUTTON_SIZE}
          title={i18n('common.menu.main')}
          icon={AlignJustify}
          tooltipPlacement="bottom"
          onClick={toggleExpanded}
        />
      )}
    </div>
  );
};

export default DesktopAppMenu;
