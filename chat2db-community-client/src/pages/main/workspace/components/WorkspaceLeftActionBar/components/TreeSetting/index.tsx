import { memo, useMemo } from 'react';
import { useStyles } from './style';
import { Dropdown } from 'antd';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { EllipsisVertical } from 'lucide-react';
import i18n from '@/i18n';
import { useTreeStore } from '@/store/tree';
import { exportConnections } from './exportConnections';
import { WORKSPACE_TREE_TOOLBAR_SECONDARY_BUTTON_SIZE } from '../../constants';

export default memo(() => {
  const { styles } = useStyles();

  const { userConfigTree, changeUserConfigTree } = useTreeStore((s) => {
    return {
      userConfigTree: s.userConfigTree,
      changeUserConfigTree: s.changeUserConfigTree,
    };
  });
  const schemaSync = useTreeStore((s) => s.schemaSync);

  const dropdownMenu = useMemo(() => {
    const followActiveWorkspaceTab = userConfigTree.followActiveWorkspaceTab !== false;
    const sortDatabaseObjects = userConfigTree.sortDatabaseObjects === true;
    return [
      {
        key: 'schema-sync',
        icon: <IconfontSvg code="icon-sync-structure" size="lg" />,
        label: i18n('workspace.syncStructure.title'),
        onClick: schemaSync,
      },
      {
        key: 'setting',
        icon: <IconfontSvg code="icon-setting" size="lg" />,
        label: i18n('workspace.menu.tree.setting'),
        children: [
          {
            label: (
              <div
                className={styles.labelTitleBox}
                onClick={() => {
                  changeUserConfigTree('sortDatabaseObjects', !sortDatabaseObjects);
                }}
              >
                {sortDatabaseObjects ? (
                  <IconfontSvg className={styles.labelSelect} code="icon-duigou" />
                ) : (
                  <div className={styles.iconPlaceholder} />
                )}
                <div className={styles.labelTitle}>{i18n('workspace.menu.sortDatabaseObjects')}</div>
              </div>
            ),
            key: 'setting-sort-database-objects',
          },
          {
            label: (
              <div
                className={styles.labelTitleBox}
                onClick={() => {
                  changeUserConfigTree('showComment', !userConfigTree.showComment);
                }}
              >
                {userConfigTree.showComment ? (
                  <IconfontSvg className={styles.labelSelect} code="icon-duigou" />
                ) : (
                  <div className={styles.iconPlaceholder} />
                )}
                <div className={styles.labelTitle}>{i18n('workspace.menu.showColumnComment')}</div>
              </div>
            ),
            key: 'setting-show-comment',
          },
          {
            label: (
              <div
                className={styles.labelTitleBox}
                onClick={() => {
                  changeUserConfigTree('followActiveWorkspaceTab', !followActiveWorkspaceTab);
                }}
              >
                {followActiveWorkspaceTab ? (
                  <IconfontSvg className={styles.labelSelect} code="icon-duigou" />
                ) : (
                  <div className={styles.iconPlaceholder} />
                )}
                <div className={styles.labelTitle}>{i18n('workspace.menu.followActiveWorkspaceTab')}</div>
              </div>
            ),
            key: 'setting-follow-active-workspace-tab',
          },
        ],
      },
      {
        key: 'export-connections',
        icon: <IconfontSvg code="icon-download" size="lg" />,
        label: i18n('workspace.menu.exportConnections'),
        onClick: () => {
          exportConnections({ datasourceIds: null });
        },
      },
    ];
  }, [
    changeUserConfigTree,
    schemaSync,
    userConfigTree.followActiveWorkspaceTab,
    userConfigTree.showComment,
    userConfigTree.sortDatabaseObjects,
  ]);

  return (
    <Dropdown
      destroyPopupOnHide
      overlayClassName={styles.datasourceOverlay}
      menu={{ items: dropdownMenu }}
      trigger={['click']}
    >
      {/* <Tooltip title={i18n('workspace.tips.createDatabase')} mouseEnterDelay={0.6}> */}
      <IconButton
        className={styles.trigger}
        size={WORKSPACE_TREE_TOOLBAR_SECONDARY_BUTTON_SIZE}
        key="tree-setting"
        icon={EllipsisVertical}
      />
      {/* </Tooltip> */}
    </Dropdown>
  );
});
