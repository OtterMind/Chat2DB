import { memo, useMemo } from 'react';
import { useStyles } from './style';
import { IconButton } from '@chat2db/ui';
import { Tooltip } from 'antd';
import { LocateFixed, RotateCw, type LucideIcon } from 'lucide-react';
import AddDatasourceBar from './components/AddDatasourceBar';
import TreeSetting from './components/TreeSetting';
import { useTreeStore } from '@/store/tree';
import { useOrgStore } from '@/store/workspaceContext';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
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

const WorkspaceLeftActionBar = memo<WorkspaceLeftActionBarProps>(
  ({ onLocateActiveTab, locateActiveTabDisabled = false }) => {
    const { refreshTreeData } = useTreeStore((s) => ({
      refreshTreeData: s.refreshTreeData,
    }));

    const { isEmbedIframe } = useGlobalStore((s) => ({
      isEmbedIframe: s.isEmbedIframe,
    }));

    const { styles } = useStyles();

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
