import React, { memo, useState, useEffect, useMemo, useRef } from 'react';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';
import { useStyles } from './style';
import { Radio, Space, Form, Button, Tabs, InputNumber, Checkbox } from 'antd';
import ModalFooterButton from '@/components/Modal/ModalFooterButton';
import i18n from '@/i18n';
import chatServices from '@/service/chat';
import NewBaseTable from '@/components/NewBaseTable';
import { handleExcelData } from '@/utils/aiChat/excelDataToTable';
import Spin from '@/components/Spin';
import { generateNumberSequence } from '@/utils';
import { cloneDeep } from 'lodash';

interface IProps {
  className?: string;
  fileUrl: {
    fileName?: string;
    filePath: string;
  };
  submitCallBack: (data: any) => void;
  closeCallBack: () => void;
}

interface ExcelData {
  sheetNo: string;
  columns: any;
  dataSource: any;
  sheetName: string;
  tableName: string;
  headerNumScope: number[];
  tableType: 'horizontal' | 'vertical';
  del: boolean;
  extend: string;
}

export default memo<IProps>((props) => {
  const { className, fileUrl, submitCallBack, closeCallBack } = props;
  const { styles, cx } = useStyles();
  const excelPreviewRef = React.useRef<HTMLDivElement>(null);
  const [sheetList, setSheetList] = useState<ExcelData[] | null>(null);
  const [form] = Form.useForm();
  const [activeSheet, setActiveSheet] = useState<ExcelData | null>(null);
  const [filePath, setFilePath] = useState<string>('');

  const requestGenerationRef = useRef(0);

  useEffect(() => {
    if (!activeSheet) return;
    form.setFieldsValue({
      tableType: activeSheet?.tableType,
      extend: activeSheet?.extend || 'false',
      retain: !activeSheet?.del,
    });
  }, [activeSheet]);

  useEffect(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    chatServices.excelCheck(fileUrl).then((res) => {
      if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
      const _excelData = handleExcelData(res.sheetList);
      setFilePath(res.filePath);
      setActiveSheet(_excelData[0]);
      setSheetList(_excelData);
    });
    return () => {
      invalidateLatestRequest(requestGenerationRef);
    };
  }, []);

  const { columns, dataSource, highlightRows, highlightColumns } = useMemo(() => {
    if (!activeSheet) {
      return {
        columns: [],
        dataSource: undefined,
        highlightColumns: [],
        highlightRows: [],
      };
    }

    setSheetList((prev) => {
      if (prev) {
        return prev.map((item) => {
          if (item.sheetNo === activeSheet?.sheetNo) {
            return activeSheet;
          }
          return item;
        });
      }
      return prev;
    });

    let nextHighlightColumns: any = [];

    if (activeSheet.tableType === 'vertical') {
      nextHighlightColumns = generateNumberSequence(...activeSheet.headerNumScope);
    }

    let nextHighlightRows: any = [];
    if (activeSheet.tableType === 'horizontal') {
      nextHighlightRows = generateNumberSequence(...activeSheet.headerNumScope, -1);
    }

    return {
      columns: activeSheet?.columns || [],
      dataSource: activeSheet?.dataSource,
      highlightColumns: nextHighlightColumns,
      highlightRows: nextHighlightRows,
    };
  }, [activeSheet]);

  const handleScopeChange = (value, index) => {
    setActiveSheet((prev) => {
      const headerNumScope = prev?.headerNumScope || [];
      headerNumScope[index] = value;
      return {
        ...prev,
        headerNumScope,
      } as any;
    });
  };

  const handleValuesChange = (changedValues, allValues) => {
    const { tableType, extend, retain } = allValues;

    setActiveSheet((prev) => {
      return {
        ...prev,
        del: !retain,
        tableType,
        extend,
      } as any;
    });

    let flag = false;

      // Apply to all subsequent sheets.
    if (extend === 'true') {
      setSheetList((prev) => {
        if (prev) {
          return prev.map((item) => {
            if (flag) {
              return {
                ...item,
                tableType,
                del: !retain,
                headerNumScope: activeSheet?.headerNumScope || [],
              };
            } else {
              if (item.sheetNo === activeSheet?.sheetNo) {
                flag = true;
              }
              return {
                ...item,
              };
            }
          });
        }
        return prev;
      });
    }
  };

  const { hasNext, nextSheetName } = useMemo(() => {
    if (!sheetList) {
      return {
        hasNext: false,
        hasLast: false,
      };
    }

    const index = sheetList.findIndex((item) => item.sheetNo === activeSheet?.sheetNo) || 0;

    return {
      hasNext: index < sheetList.length - 1,
      hasLast: index > 0,
      nextSheetName: sheetList[index + 1]?.sheetName,
    };
  }, [sheetList, activeSheet]);

  const handleSubmit = () => {
    const cloneDeepSheetList = cloneDeep(sheetList);

    const transitionNum = (num: number | null) => {
      return num === null ? 1 : num;
    };

    const _sheetList = cloneDeepSheetList?.map((item: any) => {
      const _headerNumScope = {
        headerStartRowNum: 0,
        headerEndRowNum: 0,
        headerStartColNum: 0,
        headerEndColNum: 0,
      };

      item.headerNumScope = [transitionNum(item.headerNumScope[0]), transitionNum(item.headerNumScope[1])];

      if (item.tableType === 'horizontal') {
        _headerNumScope.headerStartRowNum = item.headerNumScope[0];
        _headerNumScope.headerEndRowNum = item.headerNumScope[1];
      } else {
        _headerNumScope.headerStartColNum = item.headerNumScope[0];
        _headerNumScope.headerEndColNum = item.headerNumScope[1];
      }

      delete item.columns;
      delete item.dataSource;
      delete item.headerNumScope;

      return {
        ...item,
        ..._headerNumScope,
      };
    });
    const data = {
      filePath,
      sheetList: _sheetList,
    };

    submitCallBack(data);
    return data;
  };

  const handleLastOrNext = (type: 'last' | 'next') => {
    if (!sheetList) {
      return;
    }

    const index = sheetList.findIndex((item) => item.sheetNo === activeSheet?.sheetNo) || 0;

    const flagIndex = type === 'last' ? index - 1 : index + 1;
    setActiveSheet(sheetList[flagIndex]);
  };

  return (
    <div className={cx(styles.excelFiltersTableHeaders, className)}>
      <div className={styles.excelPreview} ref={excelPreviewRef}>
        <div className={styles.excelPreviewContent}>
          <Spin isLoading={dataSource === null}>
            <NewBaseTable
              dataSource={dataSource}
              highlightColumns={highlightColumns}
              highlightRows={highlightRows}
              columns={columns}
            />
          </Spin>
        </div>
        {sheetList?.length && sheetList.length > 1 && (
          <div className={styles.sheetSwitch}>
            <Tabs
              activeKey={activeSheet?.tableName}
              onChange={(key) => {
                const sheet = sheetList?.find((item) => item.tableName === key);
                setActiveSheet(sheet || null);
              }}
              items={sheetList?.map((item) => {
                return {
                  label: item.sheetName,
                  key: item.tableName,
                };
              })}
            />
          </div>
        )}
      </div>
      <div className={styles.actionBar}>
        <div className={styles.actionBarContent}>
          <div className={styles.functionDescription}>
            <div className="title">{i18n('chat.functionDescription.title')}</div>
            <div className="description">{i18n('chat.functionDescription.description')}</div>
          </div>
          <Form form={form} layout="vertical" onValuesChange={handleValuesChange} autoComplete="off">
            <Form.Item label={i18n('chat.tableType.title', activeSheet?.sheetName || '')} name="tableType">
              <Radio.Group>
                <Space direction="horizontal">
                  <Radio value="horizontal">{i18n('chat.tableType.horizontal')}</Radio>
                  <Radio value="vertical">{i18n('chat.tableType.vertical')}</Radio>
                </Space>
              </Radio.Group>
            </Form.Item>
            <Form.Item label={i18n('chat.headerScope.title', activeSheet?.sheetName || '')}>
              {activeSheet?.tableType === 'horizontal' ? i18n('chat.headerScope.row') : i18n('chat.headerScope.column')}{' '}
              <InputNumber
                value={activeSheet?.headerNumScope[0] || ''}
                onChange={(e) => {
                  handleScopeChange(e, 0);
                }}
                min={1}
                size="small"
                style={{ width: '60px' }}
              />{' '}
              {activeSheet?.tableType === 'horizontal'
                ? i18n('chat.headerScope.to')
                : i18n('chat.headerScope.column.to')}{' '}
              <InputNumber
                value={activeSheet?.headerNumScope[1] || ''}
                onChange={(e) => {
                  handleScopeChange(e, 1);
                }}
                size="small"
                min={1}
                style={{ width: '60px' }}
              />
            </Form.Item>
            <Form.Item name="retain" valuePropName="checked">
              <Checkbox>{i18n('chat.headerScope.retain')}</Checkbox>
            </Form.Item>
            {sheetList?.length && sheetList.length > 1 && (
              <Form.Item label={i18n('chat.headerScope.allSheet', activeSheet?.sheetName || '')} name="extend">
                <Radio.Group>
                  <Space direction="horizontal">
                    <Radio value={'true'}>{i18n('chat.headerScope.allSheet.yes')}</Radio>
                    <Radio value={'false'}>{i18n('chat.headerScope.allSheet.no')}</Radio>
                  </Space>
                </Radio.Group>
              </Form.Item>
            )}
          </Form>
          <div className={styles.sheetSwitchLastNext}>
            {/* <div>
                {hasLast && (
                  <div
                    className="last"
                    onClick={() => {
                      handleLastOrNext('last');
                    }}
                  >
                    {i18n('chat.sheetSwitchLastNext.last')}
                  </div>
                )}
              </div>
              <div>
              </div> */}
            {hasNext && (
              <div
                className="next"
                onClick={() => {
                  handleLastOrNext('next');
                }}
              >
                → {i18n('chat.sheetSwitchLastNext.next', nextSheetName)}
              </div>
            )}
          </div>
        </div>
        <ModalFooterButton
          footerRight={
            <>
              <Button onClick={closeCallBack}>{i18n('common.button.cancel')}</Button>
              <Button type="primary" onClick={handleSubmit}>
                {i18n('common.button.confirm')}
              </Button>
            </>
          }
        />
      </div>
    </div>
  );
});
