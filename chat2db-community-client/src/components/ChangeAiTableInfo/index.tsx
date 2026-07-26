import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { useStyles } from './style';
import i18n from '@/i18n';
import { ToolbarBtn, LoadingGracile, staticMessage } from '@chat2db/ui';
import { Form, Input, Spin, Tooltip } from 'antd';
import aiDataCollectionService from '@/service/aiDataCollection';
import sqlService from '@/service/sql';
import { ColumnAlias } from '@/typings/workspace';
import EditableAntdTable from '@/blocks/EditableAntdTable';
import { EditableCellType } from '@/blocks/EditableAntdTable/components/InputEditableCell';
import { cloneDeep } from 'lodash';
import useSyncState from '@/hooks/useSyncState';
import { DataCollectionElementType } from '@/constants/aiDataCollection';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

interface IProps {
  className?: string;
  uniqueData: {
    id: number;
    dataSourceId: number;
    dataSourceName: string;
    databaseType: string;
    databaseName?: string;
    schemaName?: string;
    tableName: string;
    dataCollectionElementType: DataCollectionElementType;
  };
}

export default memo<IProps>((props) => {
  const { className, uniqueData } = props;
  const { id, tableName, dataSourceId, databaseName, schemaName, dataCollectionElementType } = uniqueData;
  const { styles, cx } = useStyles();
  const [columnAlias, setColumnAlias] = useState<ColumnAlias[] | null>(null);
  const [basicInfo, setBasicInfo] = useState<{
    dataSourceId: number;
    databaseName?: string;
    schemaName?: string;
    tableComment: {
      tableName: string;
      tableComment: string;
    };
  } | null>(null);
  const [form] = Form.useForm();
  const [submitLoading] = useState(false);
  const [dataSource, setDataSource, getDataSource] = useSyncState<any[]>([]);

  const requestGenerationRef = useRef(0);

  useEffect(() => {
    getTableComment();
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const getTableComment = () => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    setColumnAlias(null);
    setBasicInfo(null);
    form.resetFields();
    const params: any = id
      ? { id }
      : {
          dataSourceId,
          databaseName,
          schemaName,
          tableName,
          type: dataCollectionElementType,
        };
    aiDataCollectionService.getTableComment(params).then((tableCommentRes) => {
      let aiRes = tableCommentRes;
      if (!aiRes) {
        aiRes = {
          dataSourceId,
          databaseName,
          schemaName,
          tableName,
          tableCommentExt: {
            tableName: tableName,
            tableComment: '',
            tableNameAlias: '',
            tableCommentAlias: '',
            columnAlias: [],
          },
        };
      }
      const getDetailsApi =
        dataCollectionElementType === DataCollectionElementType.VIEW
          ? sqlService.getViewDetails
          : sqlService.getTableDetails;
      getDetailsApi({
        dataSourceId: aiRes.dataSourceId,
        databaseName: aiRes.databaseName,
        schemaName: aiRes.schemaName,
        tableName,
        refresh: true,
      }).then((sqlRes) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        const findColumnAlias = (columnName: string) => {
          return aiRes.tableCommentExt?.columnAlias?.find((item) => item.columnName === columnName) || {};
        };
        const _columnAlias =
          sqlRes?.columnList.map((item) => {
            const columnAliasObj: any = findColumnAlias(item.name || '');
            return {
              columnName: item.name || '',
              columnComment: item.comment || '',
              columnCommentAlias: columnAliasObj.columnCommentAlias,
              columnExampleData: columnAliasObj.columnExampleData,
              columnEnumMap: columnAliasObj.columnEnumMap,
              foreignTableName: columnAliasObj.foreignTableName,
              foreignColumnName: columnAliasObj.foreignColumnName,
              functionExamples: columnAliasObj.functionExamples,
              deletedFlag: columnAliasObj.deletedFlag,
            };
          }) || [];
        form.setFieldsValue({
          tableNameAlias: aiRes.tableCommentExt?.tableNameAlias,
          tableCommentAlias: aiRes.tableCommentExt?.tableCommentAlias,
        });
        setColumnAlias(_columnAlias);
        setBasicInfo({
          dataSourceId: aiRes.dataSourceId,
          databaseName: aiRes.databaseName,
          schemaName: aiRes.schemaName,
          tableComment: {
            tableName: aiRes.tableCommentExt?.tableName || tableName,
            tableComment: aiRes.tableCommentExt?.tableComment,
          },
        });
      });
    });
  };

  const saveTableComment = () => {
    // setSubmitLoading(true);
    const params = {
      dataSourceId: basicInfo!.dataSourceId,
      databaseName: basicInfo!.databaseName,
      schemaName: basicInfo!.schemaName,
      tableName: basicInfo!.tableComment?.tableName,
      tableCommentExt: {
        tableName: basicInfo!.tableComment?.tableName,
        tableComment: basicInfo!.tableComment?.tableComment,
        tableNameAlias: form.getFieldValue('tableNameAlias'),
        tableCommentAlias: form.getFieldValue('tableCommentAlias'),
        columnAlias: dataSource || [],
      },
      type: dataCollectionElementType,
    };

    aiDataCollectionService.saveTableComment(params).then(() => {
      staticMessage.success({
        content: i18n('common.message.modifySuccessfully'),
      });
    });

    // .finally(() => {
    //   setSubmitLoading(false);
    // });
  };

  useEffect(() => {
    const _dataSource =
      columnAlias?.map((item, index) => {
        return {
          custom_data_id: index,
          columnName: item.columnName,
          columnComment: item.columnComment,
          columnCommentAlias: item.columnCommentAlias,
          columnExampleData: item.columnExampleData,
          columnEnumMap: item.columnEnumMap,
          foreignTableName: item.foreignTableName,
          foreignColumnName: item.foreignColumnName,
          functionExamples: item.functionExamples,
          deletedFlag: item.deletedFlag,
        };
      }) || [];
    setDataSource(_dataSource);
  }, [columnAlias]);

  // const hasSubmit = useMemo(() => {
  //   return tableDetail?.tableNameAlias || tableDetail?.tableCommentAlias;
  // }, [tableDetail]);

  // Table column configuration.
  const defaultColumns = useMemo(() => {
    return [
      {
        title: i18n('workspace.menu.columnName'),
        dataIndex: 'columnName',
        width: '200px',
        render: (text: string, record: any) => {
          const tooltipTitle = record.columnName + (record.columnComment ? ` (${record.columnComment})` : '');
          return (
            <Tooltip mouseEnterDelay={1} title={tooltipTitle}>
              {tooltipTitle}
            </Tooltip>
          );
        },
      },
      {
        title: i18n('workspace.menu.columnCommentAlias'),
        dataIndex: 'columnCommentAlias',
        width: 'auto',
        editable: EditableCellType.INPUT,
        render: (text: string, record: any) => {
          const tooltipTitle = record.columnCommentAlias;
          return (
            <Tooltip mouseEnterDelay={1} title={tooltipTitle}>
              {tooltipTitle}
            </Tooltip>
          );
        },
      },
      {
        title: i18n('workspace.menu.columnExample'),
        dataIndex: 'columnExampleData',
        width: 'auto',
        editable: EditableCellType.INPUT,
        render: (text: string, record: any) => {
          const tooltipTitle = record.columnExampleData;
          return (
            <Tooltip mouseEnterDelay={1} title={tooltipTitle}>
              {tooltipTitle}
            </Tooltip>
          );
        },
      },
      // {
      //   title: i18n('workspace.menu.foreignKeyTable'),
      //   dataIndex: 'foreignTableName',
      //   width: '160px',
      //   editable: EditableCellType.INPUT,
      // },
      // {
      //   title: i18n('workspace.menu.foreignKeyColumn'),
      //   dataIndex: 'foreignColumnName',
      //   width: '160px',
      //   editable: EditableCellType.INPUT,
      // },
      {
        title: i18n('workspace.menu.exampleFunction'),
        dataIndex: 'functionExamples',
        width: 'auto',
        editable: EditableCellType.INPUT,
        render: (text: string, record: any) => {
          const tooltipTitle = record.functionExamples;
          return (
            <Tooltip mouseEnterDelay={1} title={tooltipTitle}>
              {tooltipTitle}
            </Tooltip>
          );
        },
      },
      {
        title: i18n('workspace.menu.deletedFlag'),
        dataIndex: 'deletedFlag',
        editable: EditableCellType.CHECKBOX,
        width: '60px',
      },
    ];
  }, []);

  const handleChange = (rowData: any) => {
    const _dataSource = cloneDeep(getDataSource());
    for (let i = 0; i < _dataSource.length; i++) {
      if (_dataSource[i].custom_data_id === rowData.custom_data_id) {
        _dataSource[i] = rowData;
        break;
      }
    }
    setDataSource(_dataSource);
  };

  return (
    <div className={cx(styles.changeAiTableInfo, className)}>
      <div className={styles.actionBar}>
        <ToolbarBtn
          className={styles.toolbarBtn}
          onClick={getTableComment}
          prefixIcon="icon-refresh"
          text={i18n('common.button.refresh')}
        />
        <ToolbarBtn
          className={styles.toolbarBtn}
          // disabled={!hasSubmit || submitLoading}
          onClick={saveTableComment}
          prefixIcon={submitLoading ? <LoadingGracile /> : 'icon-submit'}
          text={i18n('editTableData.tips.submit')}
        />
      </div>
      {columnAlias === null ? (
        <div className={styles.spinBox}>
          <Spin />
        </div>
      ) : (
        <div className={styles.tableInfo}>
          <div className={styles.tableName}>
            <Form className={styles.form} form={form} layout="vertical" autoComplete="off">
              {/* <Form.Item name="tableNameAlias" label={i18n('workspace.menu.aiTableName')}>
                <Input placeholder={basicInfo?.tableComment?.tableName || ''} />
              </Form.Item> */}
              <Form.Item name="tableCommentAlias" label={i18n('workspace.menu.aiTableComment')}>
                <Input placeholder={basicInfo?.tableComment?.tableComment || ''} />
              </Form.Item>
            </Form>
          </div>

          <EditableAntdTable
            onChange={handleChange}
            className={styles.columnInfoTable}
            columns={defaultColumns}
            dataSource={dataSource}
            dataCollectionElementType={dataCollectionElementType}
          />
        </div>
      )}
    </div>
  );
});
