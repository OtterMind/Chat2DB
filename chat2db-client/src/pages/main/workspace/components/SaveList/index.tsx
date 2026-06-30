import React, { useState, useEffect, useRef } from 'react';
import i18n from '@/i18n';
import { Input, Dropdown, Modal, Form } from 'antd';
import Iconfont from '@/components/Iconfont';
import LoadingContent from '@/components/Loading/LoadingContent';
import historyServer from '@/service/history';
import { ConsoleOpenedStatus, workspaceTabConfig } from '@/constants';
import { IConsole, ITreeNode } from '@/typings';
import styles from './index.less';
import { approximateList } from '@/utils';
import { addWorkspaceTab, getSavedConsoleList } from '@/pages/main/workspace/store/console';
import { useWorkspaceStore } from '@/pages/main/workspace/store';
import MenuLabel from '@/components/MenuLabel';

const SaveList = () => {
  const [searching, setSearching] = useState<boolean>(false);
  const inputRef = useRef<any>();
  const [searchedList, setSearchedList] = useState<ITreeNode[] | undefined>();
  const leftModuleTitleRef = useRef<any>(null);
  const saveBoxListRef = useRef<any>(null);
  const consoleList = useWorkspaceStore((state) => state.savedConsoleList);
  const [editData, setEditData] = useState<any>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    getSavedConsoleList();
  }, []);

  useEffect(() => {
    if (searching) {
      inputRef.current!.focus({
        cursor: 'start',
      });
    }
  }, [searching]);

  function openSearch() {
    setSearching(true);
  }

  function onBlur() {
    if (!inputRef.current.input.value) {
      setSearching(false);
      setSearchedList(undefined);
    }
  }

  function onChange(value: string) {
    if (consoleList) {
      setSearchedList(approximateList(consoleList as any, value));
    }
  }

  function openConsole(item: IConsole) {
    const params: any = {
      id: item.id,
      tabOpened: ConsoleOpenedStatus.IS_OPEN,
    };
    historyServer.updateSavedConsole(params).then(() => {
      addWorkspaceTab({
        id: item.id,
        type: item.operationType,
        title: item.name,
        uniqueData: {
          dataSourceId: item.dataSourceId,
          dataSourceName: item.dataSourceName,
          databaseType: item.type,
          databaseName: item.databaseName,
          schemaName: item.schemaName,
          status: item.status,
          ddl: item.ddl,
          connectable: item.connectable,
        },
      });
    });
  }

  function deleteSaved(data: IConsole) {
    const params: any = {
      id: data.id,
    };
    historyServer.deleteSavedConsole(params).then(() => {
      getSavedConsoleList();
    });
  }

  const editSaved = (data: IConsole) => {
    setEditData(data);
  };

  return (
    <>
      <div className={styles.saveModule}>
        <div ref={leftModuleTitleRef} className={styles.leftModuleTitle}>
          {searching ? (
            <div className={styles.leftModuleTitleSearch}>
              <Input
                ref={inputRef}
                size="small"
                placeholder={i18n('common.text.search')}
                prefix={<Iconfont code="&#xe600;" />}
                onBlur={onBlur}
                onChange={(e) => onChange(e.target.value)}
                allowClear
              />
            </div>
          ) : (
            <div className={styles.leftModuleTitleText}>
              <div className={styles.modelName}>{i18n('workspace.title.savedConsole')}</div>
              <div className={styles.iconBox}>
                {/* <div className={styles.refreshIcon} onClick={() => refreshTableList()}>
                  <Iconfont code="&#xec08;" />
                </div> */}
                <div className={styles.searchIcon} onClick={() => openSearch()}>
                  <Iconfont code="&#xe600;" />
                </div>
              </div>
            </div>
          )}
        </div>
        <div ref={saveBoxListRef} className={styles.saveBoxList}>
          <LoadingContent className={styles.loadingContent} data={consoleList} handleEmpty>
            {(searchedList || consoleList)?.map((t) => {
              return (
                <Dropdown
                  key={t.id}
                  trigger={['contextMenu']}
                  menu={{
                    items: [
                      {
                        key: 'open',
                        label: <MenuLabel icon="&#xec83;" label={i18n('common.button.open')} />,
                        onClick: () => {
                          openConsole(t);
                        },
                      },
                      {
                        key: 'edit',
                        label: <MenuLabel icon="&#xe602;" label={i18n('common.text.rename')} />,
                        onClick: () => {
                          editSaved(t);
                        },
                      },
                      {
                        key: 'delete',
                        label: <MenuLabel icon="&#xe6a7;" label={i18n('common.button.delete')} />,
                        onClick: () => {
                          deleteSaved(t);
                        },
                      },
                    ],
                  }}
                >
                  <div
                    onDoubleClick={() => {
                      openConsole(t);
                    }}
                    className={styles.saveItem}
                  >
                    <div className={styles.saveItemText}>
                      <div className={styles.iconBox}>
                        <Iconfont code={workspaceTabConfig[t.operationType]?.icon} />
                      </div>
                      <div className={styles.itemName} dangerouslySetInnerHTML={{ __html: t.name }} />
                    </div>
                  </div>
                </Dropdown>
              );
            })}
          </LoadingContent>
        </div>
      </div>
      <Modal
        title={i18n('common.text.rename')}
        open={!!editData}
        onOk={() => {
          form.validateFields().then((values) => {
            const params: any = {
              id: editData.id,
              name: values.name,
            };
            historyServer.updateSavedConsole(params).then(() => {
              getSavedConsoleList();
              setEditData(null);
              form.resetFields();
            });
          });
        }}
        onCancel={() => {
          setEditData(null);
          form.resetFields();
        }}
      >
        <Form form={form} initialValues={{ name: editData?.name }}>
          <Form.Item name="name" rules={[{ required: true, message: 'Please enter name' }]}>
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default SaveList;
