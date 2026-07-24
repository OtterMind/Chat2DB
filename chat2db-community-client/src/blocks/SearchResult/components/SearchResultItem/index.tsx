import { memo, Fragment } from 'react';
import { Button } from 'antd';
import Iconfont from '@/components/Iconfont';
import StatusBar from '../StatusBar';
import ResultSet from '../ResultSet';
import i18n from '@/i18n';
import { useStyles } from './style';
import { IManageResultData } from '@/typings';
import { useAIStore } from '@/store/ai';
import { useGlobalStore } from '@/store/global';
import { useWorkspaceStore } from '@/store/workspace';
import { QuestionType } from '@/constants/chat';

interface IProps {
  resultData: IManageResultData;
  active: boolean;
  viewTable?: boolean;
}

export default memo<IProps>(
  (props) => {
    const { active, resultData, viewTable } = props;
    const { styles } = useStyles();
    const setCurrentWorkspaceExtend = useWorkspaceStore((s) => s.setCurrentWorkspaceExtend);

    const handleAIDiagnose = () => {
      const executeSqlParams = resultData.executeSqlParams || ({} as NonNullable<IManageResultData['executeSqlParams']>);
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
              input: i18n('ai.sqlDebug.prefill', resultData.originalSql || '', resultData.message || ''),
              questionType: QuestionType.SQL_DEBUG,
            },
          }),
        );
      }, 100);
    };

    function renderSuccessResult() {
      const needTable = resultData?.headerList?.length > 1;

      return (
        <div className={styles.successResult}>
          {needTable ? (
            <ResultSet active={active} viewTable={viewTable} resultData={resultData} />
          ) : (
            <div className={styles.updateCountBox}>
              <div className={styles.updateCount}>{i18n('common.text.affectedRows', resultData.updateCount)}</div>
              <StatusBar resultData={resultData} />
            </div>
          )}
        </div>
      );
    }

    function renderErrorResult() {
      return (
        <div className={styles.errorResult}>
          <Iconfont className={styles.errorIcon} code={''} />
          <div className={styles.errorMessage}>{resultData.message}</div>
          <Button type="primary" onClick={handleAIDiagnose}>
            {i18n('common.text.aiDiagnose')}
          </Button>
        </div>
      );
    }

    return (
      <Fragment key={resultData.uuid}>{resultData.success ? renderSuccessResult() : renderErrorResult()}</Fragment>
    );
  },
  (prevProps, nextProps) =>
    prevProps.active === nextProps.active &&
    prevProps.resultData === nextProps.resultData &&
    prevProps.viewTable === nextProps.viewTable,
);
