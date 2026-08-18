import { memo, useEffect, useMemo, useState } from 'react';
import { useStyles } from './style';
import { IconButton } from '@chat2db/ui';
import { Button, Tooltip } from 'antd';
import { DatabaseBackup, LocateFixed, RotateCw, type LucideIcon } from 'lucide-react';
import AddDatasourceBar from './components/AddDatasourceBar';
import TreeSetting from './components/TreeSetting';
import { useTreeStore } from '@/store/tree';
import { useOrgStore } from '@/store/organization';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import createRequest from '@/service/base';
import {
  STORAGE_MIGRATION_STATUS_EVENT,
  needsStorageMigration,
  type StorageMigrationStatus,
} from './storageMigrationPrompt';
import {
  WORKSPACE_TREE_TOOLBAR_BUTTON_SIZE,
  WORKSPACE_TREE_TOOLBAR_SECONDARY_BUTTON_SIZE,
} from './constants';

interface ActionButton {
  key: string;
  icon: LucideIcon;
  label: string;
  onClick: () => void;
  isHidden?: boolean;
}

interface WorkspaceLeftActionBarProps {
  active?: boolean;
  onLocateActiveTab?: () => void;
  locateActiveTabDisabled?: boolean;
}

const loadStorageMigrationStatus = createRequest<void, StorageMigrationStatus>('/api/system/storage-migration', {
  errorLevel: false,
});

const WorkspaceLeftActionBar = memo<WorkspaceLeftActionBarProps>(
  ({ active = true, onLocateActiveTab, locateActiveTabDisabled = false }) => {
    const { refreshTreeData } = useTreeStore((s) => ({
      refreshTreeData: s.refreshTreeData,
    }));

    const { isEmbedIframe, setSettingPageActiveTab } = useGlobalStore((s) => ({
      isEmbedIframe: s.isEmbedIframe,
      setSettingPageActiveTab: s.setSettingPageActiveTab,
    }));

    const { styles } = useStyles();
    const showStorageMigration = !isEmbedIframe && runtimeEditionConfig.settingMenuProfile !== 'community';
    const [migrationPending, setMigrationPending] = useState(false);

    const { isAdmin } = useOrgStore((s) => {
      return {
        isAdmin: s.isAdmin,
      };
    });

    const buttonList = useMemo<ActionButton[]>(() => {
      return [
        {
          key: 'refresh',
          icon: RotateCw,
          label: i18n('common.button.refresh'),
          onClick: refreshTreeData,
        },
      ];
    }, [refreshTreeData]);

    useEffect(() => {
      if (!active || !showStorageMigration) {
        setMigrationPending(false);
        return;
      }
      let disposed = false;
      void loadStorageMigrationStatus()
        .then((status) => {
          if (!disposed) {
            setMigrationPending(needsStorageMigration(status));
          }
        })
        .catch(() => {
          if (!disposed) {
            setMigrationPending(false);
          }
        });
      return () => {
        disposed = true;
      };
    }, [active, showStorageMigration]);

    useEffect(() => {
      if (!showStorageMigration) {
        return;
      }
      const handleStatus = (event: Event) => {
        const status = (event as CustomEvent<StorageMigrationStatus>).detail;
        if (status) {
          setMigrationPending(needsStorageMigration(status));
        }
      };
      window.addEventListener(STORAGE_MIGRATION_STATUS_EVENT, handleStatus);
      return () => window.removeEventListener(STORAGE_MIGRATION_STATUS_EVENT, handleStatus);
    }, [showStorageMigration]);

    const showAddDatasourceBar = useMemo(() => {
      return isAdmin && !isEmbedIframe;
    }, [isAdmin, isEmbedIframe]);

    const showTreeSetting = useMemo(() => {
      return !isEmbedIframe;
    }, [isEmbedIframe]);

    return (
      <div className={styles.workspaceLeftActionBar}>
        {showAddDatasourceBar && <AddDatasourceBar />}
        {buttonList.map((item) => {
          if (item.isHidden) {
            return null;
          }
          return (
            <Tooltip title={item.label} mouseEnterDelay={1} key={item.key}>
              <IconButton size={WORKSPACE_TREE_TOOLBAR_BUTTON_SIZE} onClick={item.onClick} icon={item.icon} />
            </Tooltip>
          );
        })}
        {showStorageMigration && migrationPending ? (
          <Button
            className={styles.storageMigrationButton}
            danger
            icon={<DatabaseBackup aria-hidden="true" size={14} />}
            onClick={() => setSettingPageActiveTab('storageMigration')}
            size="small"
            type="text"
          >
            {i18n('workspace.action.storageMigrationPending')}
          </Button>
        ) : null}
        <div className={styles.rightActions}>
          {onLocateActiveTab && (
            <Tooltip title={i18n('workspace.tips.locateActiveTab')} mouseEnterDelay={1}>
              <span>
                <IconButton
                  className={styles.secondaryAction}
                  size={WORKSPACE_TREE_TOOLBAR_SECONDARY_BUTTON_SIZE}
                  icon={LocateFixed}
                  disabled={locateActiveTabDisabled}
                  onClick={onLocateActiveTab}
                />
              </span>
            </Tooltip>
          )}
          {showTreeSetting && <TreeSetting />}
        </div>
      </div>
    );
  },
);

export default WorkspaceLeftActionBar;
