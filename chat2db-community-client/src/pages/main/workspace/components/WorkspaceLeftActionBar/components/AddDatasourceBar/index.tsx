import i18n from '@/i18n';
import { IconButton, IconfontSvg } from '@chat2db/ui';
import { ConfigProvider, Dropdown, Input, Modal, Tooltip } from 'antd';
import { Search } from 'lucide-react';
import { memo, useEffect, useMemo, useState } from 'react';
import { useStyles } from './style';

import ConnectionEdit, { submitType } from '@/components/ConnectionEdit';
import { applyConnectionIdentityColorUpdate } from '@/components/ConnectionEdit/identityColorUpdate';
import FileUploadModal, { importConfigList } from '@/components/ImportConnection';
import { ImportConnectionType } from '@/constants/database';
import connectionService from '@/service/connection';

// ----- constants/typings -----
import { databaseMap, databaseTypeList } from '@/constants';
import { getDynamicDatabaseVersion, subscribeDynamicDatabases } from '@/utils/dynamicDatabaseRegistry';

// ----- store -----
import { useTreeStore } from '@/store/tree';
import { WORKSPACE_TREE_TOOLBAR_BUTTON_SIZE } from '../../constants';

const ADD_DATASOURCE_BUTTON_SIZE = {
  ...WORKSPACE_TREE_TOOLBAR_BUTTON_SIZE,
  iconSize: 20,
} as const;

interface IProps {}

export default memo<IProps>(() => {
  const {
    styles,
    theme: { appearance },
  } = useStyles();
  const [importType, setImportType] = useState<string>();
  const [databaseSearchKeyword, setDatabaseSearchKeyword] = useState('');
  // Dynamic databases register asynchronously after mount; re-render the menu
  // when they land so the memoized list is not a stale snapshot.
  const [dynamicDatabaseVersion, setDynamicDatabaseVersion] = useState(getDynamicDatabaseVersion());
  useEffect(() => subscribeDynamicDatabases(() => setDynamicDatabaseVersion(getDynamicDatabaseVersion())), []);

  const {
    createGroup,
    isModalVisible,
    setIsModalVisible,
    connectionDetail,
    setConnectionDetail,
    refreshTreeData,
    refreshDataSourceAfterMutation,
  } = useTreeStore((state) => ({
    createGroup: state.createGroup,
    connectionDetail: state.connectionDetail,
    setConnectionDetail: state.setConnectionDetail,
    isModalVisible: state.isModalVisible,
    setIsModalVisible: state.setIsModalVisible,
    currentTreeNode: state.currentTreeNode,
    refreshTreeData: state.refreshTreeData,
    refreshDataSourceAfterMutation: state.refreshDataSourceAfterMutation,
  }));

  const databaseTypeListMenu = useMemo(() => {
    const normalizedSearchKeyword = databaseSearchKeyword.trim().toLowerCase();
    const databaseTypeItems = databaseTypeList
      .filter((t) => {
        if (!normalizedSearchKeyword) {
          return true;
        }
        return `${t.name} ${t.code}`.toLowerCase().includes(normalizedSearchKeyword);
      })
      .map((t) => {
        return {
          key: t.code,
          label: t.name,
          icon: <IconfontSvg code={t.icon} existDark={t.iconExistDark} appearance={appearance} size="lg" />,
          onClick: () => {
            setConnectionDetail({
              type: t.code,
            } as any);
            setTimeout(() => {
              setIsModalVisible(true);
            }, 0);
          },
        };
      });

    const databaseSearchItem = {
      key: 'database-search',
      label: (
        <div
          className={styles.datasourceSearchItem}
          onClick={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
          onKeyDown={(event) => event.stopPropagation()}
        >
          <Input
            allowClear
            autoFocus
            size="small"
            placeholder={i18n('common.text.searchPlaceholder')}
            prefix={<Search aria-hidden size={14} />}
            value={databaseSearchKeyword}
            onChange={(event) => {
              setDatabaseSearchKeyword(event.target.value);
            }}
          />
        </div>
      ),
    };

    const importTypeItems = importConfigList.map((t) => {
      return {
        key: t.type,
        label: t.title,
        onClick: () => {
          if (t.type === ImportConnectionType.CHAT2DB_COMMUNITY) {
            connectionService.importCommunitDataSource().then(() => {
              refreshTreeData();
            });
            return;
          }
          setImportType(t.type);
        },
      };
    });

    return {
      items: [
        {
          key: 'create-namespace',
          label: i18n('workspace.menu.newGroup'),
          icon: <IconfontSvg code="icon-folder" size="lg" />,
          onClick: () => {
            createGroup();
          },
        },
        {
          key: 'create-datasource',
          label: i18n('workspace.menu.newDataSource'),
          icon: <IconfontSvg code="icon-newdatabase" size="lg" />,
          children: [
            databaseSearchItem,
            {
              key: 'database-search-divider',
              type: 'divider' as const,
            },
            ...(databaseTypeItems.length
              ? databaseTypeItems
              : [
                  {
                    key: 'database-empty',
                    disabled: true,
                    label: <div className={styles.datasourceSearchEmpty}>{i18n('common.text.noSearchResult')}</div>,
                  },
                ]),
          ],
        },
        {
          key: 'import',
          label: i18n('workspace.menu.importConnection'),
          icon: <IconfontSvg code="icon-upload" size="lg" />,
          children: importTypeItems,
        },
      ],
    };
  }, [
    appearance,
    createGroup,
    databaseSearchKeyword,
    dynamicDatabaseVersion,
    refreshTreeData,
    setConnectionDetail,
    setIsModalVisible,
    styles.datasourceSearchEmpty,
    styles.datasourceSearchItem,
  ]);

  const handleCancelModal = () => {
    setIsModalVisible(false);
    setTimeout(() => {
      setConnectionDetail(null);
    }, 0);
  };

  const onSubmit = (dataSource, type: submitType) => {
    if (type === submitType.UPDATE) {
      return connectionService
        .update({
          ...dataSource,
        })
        .then(async (res) => {
          const updatedDataSource = await applyConnectionIdentityColorUpdate(
            res,
            connectionDetail?.identityColor,
            dataSource.identityColor,
            connectionService.updateIdentityColor,
          );
          setIsModalVisible(false);
          if (updatedDataSource?.id) {
            await refreshDataSourceAfterMutation(updatedDataSource.id);
          }
          return updatedDataSource;
        });
    } else {
      return connectionService
        .save({
          ...dataSource,
          spaceId: connectionDetail?.spaceId,
        })
        .then(async (res: any) => {
          setIsModalVisible(false);
          if (res?.id) {
            await refreshDataSourceAfterMutation(res.id);
          }
        });
    }
  };

  const renderModalContent = () => {
    return (
      <ConnectionEdit
        closeCreateConnection={handleCancelModal}
        connectionData={connectionDetail as any}
        submit={onSubmit}
      />
    );
  };

  const renderTitle = () => {
    if (!connectionDetail) {
      return null;
    }
    return (
      <div className={styles.title}>
        <IconfontSvg
          className={styles.titleIcon}
          existDark={databaseMap[connectionDetail.type]?.iconExistDark}
          appearance={appearance}
          size={36}
          code={databaseMap[connectionDetail.type]?.icon}
        />
        <div>{databaseMap[connectionDetail.type]?.name}</div>
      </div>
    );
  };

  return (
    <>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <Dropdown
          destroyPopupOnHide
          overlayClassName={styles.datasourceOverlay}
          menu={databaseTypeListMenu}
          trigger={['click']}
          onOpenChange={(open) => {
            if (!open) {
              setDatabaseSearchKeyword('');
            }
          }}
        >
          <Tooltip title={i18n('workspace.tips.createDatabase')} mouseEnterDelay={0.6}>
            <IconButton
              className={styles.addDatasourceButton}
              size={ADD_DATASOURCE_BUTTON_SIZE}
              key="create-datasource"
              code="icon-add-subscript"
            />
          </Tooltip>
        </Dropdown>
      </ConfigProvider>
      <Modal
        width="90%"
        style={{ maxWidth: '1200px', minWidth: '800px' }}
        title={renderTitle()}
        footer={null}
        open={isModalVisible}
        onCancel={handleCancelModal}
        centered
        maskClosable={false}
        destroyOnClose
      >
        {renderModalContent()}
      </Modal>
      <FileUploadModal
        open={!!importType}
        type={importType}
        onClose={() => {
          setImportType(undefined);
        }}
        onConfirm={() => {
          setImportType(undefined);
          refreshTreeData();
        }}
      />
    </>
  );
});
