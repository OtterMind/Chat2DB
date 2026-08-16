import { memo, useEffect, useMemo, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { useStyles } from './style';
import PageTitle from '@/components/PageTitle';
import i18n from '@/i18n';
import Description from '@/components/Description';
import { Button, Flex, Input, Form, Popconfirm, Dropdown, Select } from 'antd';
import AntdTable from '@/components/AntdTable';
import { v4 as uuidv4 } from 'uuid';
import { IconfontSvg, staticMessage, Modal } from '@chat2db/ui';
import knowledgeManagementServices from '@/service/knowledgeManagement';
import { KnowledgeManagementPromptType } from '@/constants/knowledgeManagement';
import { downloadFile } from '@/utils/file';
import UploadLocalFile from '@/components/UploadLocalFile';
import { useTreeStore } from '@/store/tree';
import { databaseMap } from '@/constants';
import { isDesktop } from '@/utils/env';
import { useOrgStore } from '@/store/organization';
import { useUserStore } from '@/store/user';
import { useGlobalStore } from '@/store/global';
import jcefApi from '@/jcef';

const { Search } = Input;

interface IProps {
  className?: string;
  promptType: any;
}

const initPagination = {
  searchKey: '',
  current: 1,
  pageSize: 10,
  total: 0,
};

const configs = {
  [KnowledgeManagementPromptType.KNOWLEDGE_TERM]: {
    title: i18n('knowledgeManagement.nav.terminology'),
    description: i18n('knowledgeManagement.terminology.description'),
    promptName: i18n('knowledgeManagement.label.knowledgeName'),
    promptContent: i18n('knowledgeManagement.label.knowledgeContent'),
    dataIndex: 'promptName',
    tips: i18n('knowledgeManagement.terminology.tips'),
    batchImportApi: knowledgeManagementServices.batchImportKnowledgeTerm,
  },
  [KnowledgeManagementPromptType.BUSINESS_LOGIC]: {
    title: i18n('knowledgeManagement.nav.businessLogic'),
    description: i18n('knowledgeManagement.businessLogic.description'),
    promptName: i18n('knowledgeManagement.label.businessLogicName'),
    promptContent: i18n('knowledgeManagement.label.businessLogicContent'),
    dataIndex: 'promptName',
    tips: i18n('knowledgeManagement.businessLogic.tips'),
    batchImportApi: knowledgeManagementServices.batchImportBusinessLogic,
  },
  [KnowledgeManagementPromptType.SQL_TEMPLATE]: {
    title: i18n('knowledgeManagement.nav.caseOptimization'),
    description: i18n('knowledgeManagement.caseOptimization.description'),
    promptName: i18n('knowledgeManagement.label.caseOptimizationName'),
    promptContent: i18n('knowledgeManagement.label.caseOptimizationContent'),
    dataIndex: 'promptName',
    tips: i18n('knowledgeManagement.caseOptimization.tips'),
    batchImportApi: knowledgeManagementServices.batchImportSqlTemplate,
  },
};

export default memo<IProps>((props) => {
  const { className, promptType } = props;
  const { styles, cx } = useStyles();
  const [form] = Form.useForm();
  const [editingRowId, setEditingRowId] = useState<{ id: string; isDraft?: boolean } | null>(null);
  const [dataSource, setDataSource] = useState<any[]>([]);
  const [pagination, setPagination] = useState(initPagination);
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const config = configs[promptType];
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importFile, setImportFile] = useState<any>(null);
  const [batchImportLoading, setBatchImportLoading] = useState(false);
  const { dataSourceList, getDataSourceList } = useTreeStore((s) => {
    return {
      dataSourceList: s.dataSourceList,
      getDataSourceList: s.getDataSourceList,
    };
  });

  const { isPersonal } = useOrgStore((s) => {
    return {
      isPersonal: s.isPersonal,
    };
  });

  const { isCurrentUserOrAdmin } = useUserStore((s) => {
    return {
      isCurrentUserOrAdmin: s.isCurrentUserOrAdmin,
    };
  });

  const deleteModal = useGlobalStore((s) => s.deleteModal);

  useEffect(() => {
    if (!editingRowId) {
      form.resetFields();
    }
  }, [editingRowId]);

  useEffect(() => {
    getList();
  }, [pagination.searchKey, pagination.current, pagination.pageSize]);

  const getList = () => {
    setLoading(true);
    setEditingRowId(null);
    knowledgeManagementServices
      .getList({
        promptType,
        pageNo: pagination.current,
        pageSize: pagination.pageSize,
        searchKey: pagination.searchKey,
      })
      .then((res) => {
        if (res.data.length === 0 && res.total !== 0 && pagination.current > 1) {
          setPagination({
            ...pagination,
            current: pagination.current - 1,
            total: res.total,
          });
          return;
        }
        setDataSource(res?.data ?? []);
        setPagination({
          ...pagination,
          total: res.total,
        });
      })
      .finally(() => {
        setLoading(false);
      });
  };

  const dataSourceListOptions = useMemo(() => {
    return dataSourceList?.map((item) => {
      let icon = '';
      if (item.extraParams.databaseType) {
        icon = databaseMap[item.extraParams.databaseType]?.icon;
      }
      return {
        label: (
          <div className={styles.dataSourceLabel}>
            <IconfontSvg code={icon} />
            {item.originalTitle}
          </div>
        ),
        title: item.originalTitle,
        value: item.id,
      };
    });
  }, [dataSourceList]);

  const columns = [
    {
      title: config.promptName,
      dataIndex: 'promptName',
      width: '150px',
      render: (value: string, record: any) => {
        if (editingRowId?.id === record.id) {
          return (
            <Form.Item name="promptName" className={styles.formItem}>
              <Input value={value} className={styles.input} />
            </Form.Item>
          );
        }
        return <div className={styles.cellPreview}>{value}</div>;
      },
    },
    {
      title: config.promptContent,
      dataIndex: 'promptContent',
      render: (value: string, record: any) => {
        if (editingRowId?.id === record.id) {
          return (
            <Form.Item name="promptContent" className={styles.formItem}>
              <Input value={value} className={styles.input} />
            </Form.Item>
          );
        }
        return <div className={styles.cellPreview}>{value}</div>;
      },
    },
    {
      title: i18n('knowledgeManagement.label.boundDataSource'),
      dataIndex: 'dataSourceIds',
      width: '200px',
      render: (value: any[], record: any) => {
        if (editingRowId?.id === record.id) {
          return (
            <Form.Item name="dataSourceIds" className={styles.formItem}>
              <Select
                onDropdownVisibleChange={(open) => {
                  if (open) {
                    getDataSourceList();
                  }
                }}
                optionFilterProp="title"
                mode="multiple"
                value={value}
                options={dataSourceListOptions}
                className={styles.selectInput}
              />
            </Form.Item>
          );
        }
        return (
          <div className={cx(styles.cellPreview, styles.dataSourceLabelListView)}>
            {record.dataSourceInfos?.map((item) => {
              const icon = databaseMap[item.dataSourceType]?.icon;
              return (
                <div className={styles.dataSourceLabelView} key={item.dataSourceId}>
                  <IconfontSvg code={icon} />
                  {item.dataSourceAlias}
                </div>
              );
            })}
          </div>
        );
      },
    },
    {
      title: i18n('common.text.creator'),
      dataIndex: 'createUserName',
      hidden: isPersonal,
      width: '110px',
      render: (value: string) => {
        return <div className={styles.cellPreview}>{value}</div>;
      },
    },
    {
      title: i18n('common.text.action'),
      dataIndex: 'action',
      width: '120px',
      fixed: 'right',
      render: (value: string, record: any) => (
        <Flex>
          {editingRowId?.id === record.id ? (
            <>
              <div className={styles.actionButton} onClick={() => handleSave(record)}>
                {i18n('common.button.save')}
              </div>
              <div className={styles.actionButton} onClick={() => handleCancel()}>
                {i18n('common.button.cancel')}
              </div>
            </>
          ) : (
            <>
              {isCurrentUserOrAdmin(record.createUserId) && (
                <>
                  <div className={styles.actionButton} onClick={() => handleEdit(record)}>
                    {i18n('common.button.edit')}
                  </div>
                  <Popconfirm
                    title={i18n('common.text.deleteConfirmTitle')}
                    icon={false}
                    onConfirm={() => handleDelete(record)}
                  >
                    <div className={cx(styles.actionButton, styles.deleteButton)}>{i18n('common.button.delete')}</div>
                  </Popconfirm>
                </>
              )}
            </>
          )}
        </Flex>
      ),
    },
  ].filter((item) => !item.hidden);

  const handleSave = (record: any) => {
    const values = form.getFieldsValue();
    if (!values.promptName || !values.promptContent) {
      staticMessage.warning(i18n('knowledgeManagement.tips.incomplete'));
      return;
    }
    const api = !editingRowId?.isDraft ? knowledgeManagementServices.update : knowledgeManagementServices.save;
    const params = {
      promptType,
      promptId: record.id,
      promptName: values.promptName,
      promptContent: values.promptContent,
      dataSourceIds: values.dataSourceIds,
    };
    api(params).then(() => {
      getList();
      setEditingRowId(null);
    });
  };

  const handleCancel = () => {
    if (editingRowId?.isDraft) {
      const newDataSource = dataSource.filter((item) => item.id !== editingRowId.id);
      setDataSource(newDataSource);
    }
    setEditingRowId(null);
  };

  const handleEdit = (record: any) => {
    form.setFieldsValue({
      promptName: record.promptName,
      promptContent: record.promptContent,
      dataSourceIds: record.dataSourceInfos?.map((item) => item.dataSourceId),
    });
    setEditingRowId({ id: record.id });
  };

  const handleDelete = (record: any) => {
    knowledgeManagementServices.remove({ promptId: record.id }).then(() => {
      getList();
    });
  };

  const handleAdd = () => {
    if (editingRowId) {
      staticMessage.warning(i18n('knowledgeManagement.tips.save'));
      return;
    }
    const id = uuidv4();
    let newDataSource = [{ id, promptName: '', promptContent: '' }, ...dataSource];
      // Keep newDataSource at no more than 10 items.
    newDataSource = newDataSource.slice(0, 10);
    setDataSource(newDataSource);
    setEditingRowId({ id, isDraft: true });
  };

  const newDataSource = useMemo(() => {
    return dataSource.map((item) => ({
      ...item,
      key: item.id,
    }));
  }, [dataSource]);

  // Delete in batches.
  const handleBatchDelete = () => {
    if (!selectedRowKeys.length) {
      staticMessage.warning(i18n('knowledgeManagement.tips.select'));
      return;
    }
    deleteModal?.confirm({
      title: i18n('common.text.deleteConfirmTitle'),
      content: i18n('knowledgeManagement.label.batchDeleteConfirm', selectedRowKeys.length),
      icon: null,
      onOk: () => {
        knowledgeManagementServices
          .batchRemove({
            promptIds: selectedRowKeys,
            promptType,
          })
          .then((res) => {
            staticMessage.success(i18n('knowledgeManagement.tips.deleteSuccess', res));
            getList();
            setSelectedRowKeys([]);
          });
      },
    });
  };

  // Export in batches.
  const handleBatchExport = async () => {
    if (!selectedRowKeys.length) {
      staticMessage.warning(i18n('knowledgeManagement.tips.select'));
      return;
    }
    if (isDesktop) {
      const exportPath = await jcefApi?.selectDirectory();
      if (!exportPath) return;
      knowledgeManagementServices
        .batchExport({
          exportPath,
          promptIds: selectedRowKeys,
          promptType,
        })
        .then((res) => {
          jcefApi?.revealInExplorer(res);
        });
    } else {
      downloadFile('/api/ai/prompt/rag/export_excel', {
        promptIds: selectedRowKeys,
        promptType,
      });
    }
  };

  // Import in batches.
  const handleBatchImport = () => {
    setImportModalOpen(true);
  };

  // Export all items in batches.
  const handleBatchExportAll = async () => {
    if (isDesktop) {
      const exportPath = await jcefApi?.selectDirectory();
      knowledgeManagementServices
        .batchExport({
          exportPath,
          promptType,
        })
        .then((res) => {
          jcefApi?.revealInExplorer(res);
        });
    } else {
      downloadFile('/api/ai/prompt/rag/export_excel', {
        promptType,
      });
    }
  };

  // Download the template.
  const downloadTemplate = () => {
    if (isDesktop) {
      knowledgeManagementServices
        .downloadTemplate({
          promptType,
        })
        .then((res) => {
          jcefApi?.revealInExplorer(res);
        });
    } else {
      downloadFile('/api/ai/prompt/rag/download_excel', {
        promptType,
      });
    }
  };

  // Import in batches.
  const handleImport = () => {
    if (!importFile) {
      return;
    }

    setBatchImportLoading(true);

    config
      .batchImportApi({
        file: importFile,
      })
      .then((res) => {
        cancelModal();
        getList();
        staticMessage.success(i18n('knowledgeManagement.tips.importSuccess', res));
      })
      .finally(() => {
        setBatchImportLoading(false);
      });
  };

  const handleFileUrlListChange = (fileUrlList: any[]) => {
    if (fileUrlList.length === 0) {
      return;
    }
    setImportFile(isDesktop ? [fileUrlList[0].filePath] : fileUrlList[0].file);
  };

  const cancelModal = () => {
    setImportModalOpen(false);
    setImportFile(null);
  };

  return (
    <div className={cx(styles.container, className)}>
      <PageTitle title={config.title} />
      <Description>{config.description}</Description>
      <div className={styles.tips}>
        <IconfontSvg code="icon-exclamation-circle" />
        <div className={styles.tipsContent}>{config.tips}</div>
      </div>
      <div className={styles.header}>
        <div className={styles.searchInput}>
          <Search
            placeholder={i18n('common.text.search')}
            onSearch={(value) => setPagination({ ...pagination, current: 1, searchKey: value })}
          />
        </div>
        <div className={styles.searchButton}>
          <Dropdown
            trigger={['click']}
            overlayClassName={styles.dropdown}
            menu={{
              items: [
                { label: i18n('knowledgeManagement.label.batchExportAll'), key: 'batchExportAll' },
                { label: i18n('knowledgeManagement.label.batchExport'), key: 'batchExport' },
                { label: i18n('knowledgeManagement.label.batchImport'), key: 'batchImport' },
                { label: i18n('knowledgeManagement.label.batchDelete'), key: 'batchDelete' },
              ],
              onClick: ({ key }) => {
                switch (key) {
                  case 'batchExportAll':
                    handleBatchExportAll();
                    break;
                  case 'batchExport':
                    handleBatchExport();
                    break;
                  case 'batchImport':
                    handleBatchImport();
                    break;
                  case 'batchDelete':
                    handleBatchDelete();
                    break;
                  default:
                    break;
                }
              },
            }}
          >
            <Button>
              {i18n('knowledgeManagement.label.batchOperation')}
              <ChevronDown size={14} />
            </Button>
          </Dropdown>
          <Button type="primary" onClick={handleAdd}>
            {i18n('common.button.addNew')}
          </Button>
        </div>
      </div>
      <Form form={form} component={false}>
        <AntdTable
          rowSelection={{
            selectedRowKeys,
            onSelect: (record, selected) => {
              const _selectedRowKeys = new Set(selectedRowKeys);
              if (selected) {
                _selectedRowKeys.add(record.id);
              } else {
                _selectedRowKeys.delete(record.id);
              }
              setSelectedRowKeys(Array.from(_selectedRowKeys));
            },
            onSelectAll: (selected, selectedRows, changeRows) => {
              const _selectedRowKeys = new Set(selectedRowKeys);
              if (selected) {
                changeRows.forEach((item) => {
              // item may be undefined when changing pagination selections.
                  if (!item?.id) {
                    return;
                  }
                  _selectedRowKeys.add(item.id);
                });
              } else {
                changeRows.forEach((item) => {
                  if (!item?.id) {
                    return;
                  }
                  _selectedRowKeys.delete(item.id);
                });
              }
              setSelectedRowKeys(Array.from(_selectedRowKeys));
            },
            getCheckboxProps: (record) => {
              return {
                disabled: editingRowId?.id === record.id || !isCurrentUserOrAdmin(record.createUserId),
              };
            },
          }}
          size="small"
          pagination={pagination}
          bordered
          className={styles.table}
          columns={columns as any}
          dataSource={newDataSource}
          loading={loading}
          onChange={(_pagination) => setPagination({ ...pagination, current: _pagination.current ?? 1 })}
        />
      </Form>
      <Modal
        title={i18n('knowledgeManagement.label.batchImport')}
        headerBorder
        open={importModalOpen}
        onCancel={cancelModal}
        onOk={handleImport}
        destroyOnClose
        confirmLoading={batchImportLoading}
      >
        <div className={styles.importModalContent}>
          <UploadLocalFile fileSize={5} accept=".xlsx,.xls" fileUrlListChange={handleFileUrlListChange} />
          <a className={styles.downloadTemplateButton} onClick={downloadTemplate}>
            {i18n('knowledgeManagement.label.downloadTemplate')}
          </a>
        </div>
      </Modal>
    </div>
  );
});
