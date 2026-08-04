import { memo, useMemo, useRef, useState } from 'react';
import { Modal } from 'antd';
import { isNumber } from 'lodash';
import * as VTable from '@visactor/vtable';

import i18n from '@/i18n';
import { QuestionType } from '@/constants/chat';
import { LangType } from '@/constants/settings';
import CanvasTable, { ICustomOptions, CanvasTableRef } from '@/blocks/CanvasTable';
import { useAIStore } from '@/store/ai';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import SQLPreview from '@/components/SQLPreview';
import { useStyles } from './style';

const VGroup = VTable.VGroup;
const VText = VTable.VText;

interface IProps {
  className?: string;
  active?: boolean;
  data: any[];
  customOptions?: ICustomOptions;
}

export default memo<IProps>((props) => {
  const { active = true, className, data, customOptions } = props;
  const { styles, cx, theme } = useStyles();
  const tableRef = useRef<CanvasTableRef>(null);
  const setCurrentWorkspaceExtend = useWorkspaceStore((s) => s.setCurrentWorkspaceExtend);
  const currentLang = useGlobalStore((s) => s.baseSetting.language);
  const customFontSize = useGlobalStore((s) => s.baseSetting.customFontSize);
  const [errorDetailInfo, setErrorDetailInfo] = useState<{
    sql: string;
    errorMessage: string;
  } | null>(null);

  const renderSuccess = (rowData) => {
    if (isNumber(rowData.updateCount)) {
      return `${i18n('common.text.successful')}: ${i18n('common.text.affectedRows', rowData.updateCount)}`;
    }
    return i18n('common.text.successful');
  };

  const maxPreviewChars = useMemo(() => {
    switch (currentLang) {
      case LangType.ZH_CN:
        return 56;
      case LangType.JA_JP:
        return 72;
      case LangType.KO_KR:
        return 72;
      case LangType.ES_ES:
      case LangType.EN_US:
        return 92;
      default:
        return 72;
    }
  }, [currentLang]);

  const actionWidth = useMemo(() => {
    switch (currentLang) {
      case LangType.ZH_CN:
        return 170;
      case LangType.JA_JP:
        return 220;
      case LangType.KO_KR:
        return 220;
      case LangType.ES_ES:
      case LangType.EN_US:
        return 240;
      default:
        return 210;
    }
  }, [currentLang]);

  const renderMessage = (rowData) => {
    return rowData.success ? renderSuccess(rowData) : `${i18n('common.text.failure')}: ${rowData.message || ''}`;
  };

  const getPreviewMessage = (rowData) => {
    const fullMessage = renderMessage(rowData);
    if (fullMessage.length <= maxPreviewChars) {
      return fullMessage;
    }
    return `${fullMessage.slice(0, maxPreviewChars)}...`;
  };

  const buildDiagnosticPrompt = (rowData) => {
    return i18n('ai.sqlDebug.prefill', rowData.originalSql || '', rowData.message || '');
  };

  const handleAIDiagnose = (row: number) => {
    const rowData = data[row - 1];
    const executeSqlParams = rowData.executeSqlParams || {};
    const page = useGlobalStore.getState().mainPageActiveTab as 'workspace' | 'dashboard' | 'chat' | 'stream';

    setCurrentWorkspaceExtend(null);
    useAIStore.getState().setCascaderData(page, {
      dataSourceId: executeSqlParams.dataSourceId,
      databaseName: executeSqlParams.databaseName,
      schemaName: executeSqlParams.schemaName,
    });
    useAIStore.getState().setShowPanel(true);
    window.setTimeout(() => {
      window.dispatchEvent(
        new CustomEvent('stream:prefillMessage', {
          detail: {
            input: buildDiagnosticPrompt(rowData),
            questionType: QuestionType.SQL_DEBUG,
          },
        }),
      );
    }, 100);
  };

  const handleOpenErrorDetail = (row: number) => {
    const rowData = data[row - 1];
    setErrorDetailInfo({
      sql: rowData.originalSql || '',
      errorMessage: rowData.message || '',
    });
  };

  const columns = useMemo(() => {
    return [
      {
        title: 'SQL',
        field: 'originalSql',
        width: 'auto',
        editor: 'custom-input-editor',
        fontSize: customFontSize,
      },
      {
        title: i18n('common.title.message'),
        field: 'message',
        customLayout: (args) => {
          const { table, row, col, rect } = args;
          const { height, width } = rect || table.getCellRect(col, row);
          const rowData = args.table.records[args.row - 1];
          const currentActionWidth = rowData.success ? 0 : actionWidth;
          const messageWidth = width - currentActionWidth;
          const container = (
            <VGroup
              attribute={{
                width,
                height,
                display: 'flex',
                flexDirection: 'row',
                flexWrap: 'nowrap',
                justifyContent: 'space-between',
              }}
            >
              <VGroup
                attribute={{
                  width: messageWidth,
                  height,
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'center',
                }}
              >
                <VText
                  attribute={{
                    maxLineWidth: messageWidth - 12,
                    disableAutoClipedPoptip: true,
                    fill: rowData.success ? theme.colorSuccessText : theme.colorErrorText,
                    fontSize: customFontSize,
                    text: getPreviewMessage(rowData),
                    textAlign: 'left',
                    textBaseline: 'middle',
                    boundsPadding: [0, 8, 0, 8],
                  }}
                />
              </VGroup>
              {!rowData.success && (
                <VGroup
                  attribute={{
                    width: currentActionWidth,
                    height,
                    display: 'flex',
                    flexDirection: 'row',
                    justifyContent: 'flex-end',
                    alignItems: 'center',
                  }}
                >
                  <VText
                    attribute={{
                      text: i18n('common.text.aiDiagnose'),
                      fontSize: customFontSize,
                      textAlign: 'left',
                      textBaseline: 'middle',
                      boundsPadding: [0, 12, 0, 0],
                      fill: theme.colorPrimary,
                      cursor: 'pointer',
                    }}
                    onClick={() => {
                      handleAIDiagnose(row);
                    }}
                  />
                  <VText
                    attribute={{
                      text: i18n('common.notification.detail'),
                      fontSize: customFontSize,
                      textAlign: 'left',
                      textBaseline: 'middle',
                      boundsPadding: [0, 12, 0, 16],
                      fill: theme.colorTextSecondary,
                      cursor: 'pointer',
                    }}
                    onClick={() => {
                      handleOpenErrorDetail(row);
                    }}
                  />
                </VGroup>
              )}
            </VGroup>
          );

          return {
            rootContainer: container,
            renderDefault: false,
            expectedHeight: 30,
          };
        },
        editor: 'custom-input-editor',
        width: 'auto',
        minWidth: '30%',
      },
      {
        title: i18n('common.text.time'),
        field: 'duration',
        width: 'auto',
        minWidth: 80,
        fontSize: customFontSize,
      },
    ];
  }, [
    actionWidth,
    customFontSize,
    maxPreviewChars,
    theme.colorErrorText,
    theme.colorPrimary,
    theme.colorSuccessText,
    theme.colorTextSecondary,
  ]);

  const records = useMemo(() => {
    return data.map((item) => {
      return {
        originalSql: item.originalSql,
        success: item.success,
        duration: `${item.duration || 0}ms`,
        message: renderMessage(item),
        updateCount: item.updateCount,
        executeSqlParams: item.executeSqlParams,
      };
    });
  }, [data]);

  return (
    <>
      <div className={cx(styles.abstract, className)}>
        <CanvasTable
          active={active}
          ref={tableRef}
          records={records}
          columns={columns}
          customOptions={customOptions}
          tooltip
          options={{
            widthMode: 'adaptive',
            frozenColCount: 0,
          }}
        />
      </div>
      <Modal
        open={!!errorDetailInfo}
        title={i18n('common.title.errorMessage')}
        footer={null}
        width={820}
        onCancel={() => setErrorDetailInfo(null)}
      >
        <div className={styles.detailSection}>
          <div className={styles.detailTitle}>SQL</div>
          <SQLPreview
            className={styles.detailBlock}
            sql={errorDetailInfo?.sql || '-'}
            source="execution-error-detail"
            foldable={false}
          />
        </div>
        <div className={styles.detailSection}>
          <div className={styles.detailTitle}>{i18n('common.text.errorMessage')}</div>
          <pre className={styles.detailBlock}>{errorDetailInfo?.errorMessage || '-'}</pre>
        </div>
      </Modal>
    </>
  );
});
