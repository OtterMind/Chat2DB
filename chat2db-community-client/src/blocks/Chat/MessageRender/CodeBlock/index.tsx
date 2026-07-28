import { FC, useState, useCallback, memo } from 'react';
import { CodeHighlighter, CopyButton, IconButton, IconfontSvg } from '@chat2db/ui';
import { useStyles } from './style';
import i18n from '@/i18n';
import SQLPreview from '@/components/SQLPreview';
import { Flex, Popconfirm } from 'antd';
import { IDatabaseBaseInfo, IManageResultData } from '@/typings';
import { processResultDataList } from '@/utils/database';
import useSqlExecutor from '@/hooks/useSqlExecutor';
import SearchResult from '@/blocks/SearchResult';
import { useWorkspaceStore } from '@/store/workspace';
import SQLServer from '@/service/sql';
import { DatabaseTypeCode } from '@/constants';

export interface CodeBlockProps {
  [key: string]: any;
  databaseInfo: IDatabaseBaseInfo;
}

const buttonSize = 'sm';

const CodeBlock: FC<CodeBlockProps> = memo((props) => {
  if (!props.children) return;

  const { databaseInfo } = props;
  const {
    children,
    className,
  }: {
    children?: string | string[];
    className?: string;
  } = props.children?.props || {};
  if (!children) return;

  const codeContent = Array.isArray(children) ? (children[0] as string) : children;
  const lang = className?.replace('language-', '') as string;
  if (lang !== 'sql') {
    return <CodeHighlighter code={codeContent} language={lang} />;
  }

  const { styles } = useStyles();
  const [resultDataList, setResultDataList] = useState<IManageResultData[] | null>();
  const { executeSQL, canExecuteSQL, executing } = useSqlExecutor();
  const isResultVisible = true;
  const [openConfirm, setOpenConfirm] = useState(false);

  const handleExecuteSQL = useCallback(async () => {
    if (!canExecuteSQL()) {
      return;
    }
    setResultDataList(null);

    const executeSqlParams = {
      ...(databaseInfo || {}),
      pageNo: 1,
      pageSize: 1000,
      sql: codeContent,
    };

    const res = await executeSQL(executeSqlParams);
    const _resultDataList = processResultDataList(res, executeSqlParams);

    // if (boundInfo.databaseType) {
    //   // Refresh tree, only supports relational databases
    //   handleRefreshTreeByExecuteSQL(_resultDataList, boundInfo.databaseType);
    // }

    setResultDataList(_resultDataList);
    setOpenConfirm(false);
  }, [canExecuteSQL, codeContent, databaseInfo, executeSQL]);

  // Mock function to check if confirmation is needed
  const checkNeedConfirm = async (sql: string) => {
    try {
      const res = await SQLServer.checkIsSelectSQL({
        sql,
        dbType: databaseInfo?.databaseType || DatabaseTypeCode.MYSQL,
      });
      // Mock API call - you can replace this with real API call later
      return !res;
    } catch {
      return true;
    }
  };

  const handleExecuteClick = async () => {
    const _needConfirm = await checkNeedConfirm(codeContent);
    setOpenConfirm(_needConfirm);
    if (!_needConfirm) {
      handleExecuteSQL();
    }
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <Flex gap={4} align="center">
          <IconfontSvg code={'icon-chat-database'} size={16} />
          <span>{databaseInfo?.databaseName ?? databaseInfo?.schemaName}</span>
        </Flex>
        <Flex justify="flex-end" gap="4px">
          <Popconfirm
            title={i18n('ai.codeBlock.run.title')}
            description={i18n('ai.codeBlock.run.desction')}
            placement="top"
            onConfirm={handleExecuteSQL}
            open={openConfirm}
            onCancel={() => setOpenConfirm(false)}
          >
            <IconButton
              code="icon-play"
              size={buttonSize}
              title={i18n('common.button.execute')}
              onClick={handleExecuteClick}
              disabled={!databaseInfo?.dataSourceId || executing}
            />
          </Popconfirm>
          <IconButton
            code="icon-ding"
            size={buttonSize}
            onClick={() => {
              const activeConsoleId = useWorkspaceStore.getState().activeConsoleId;
              if (activeConsoleId) {
                useWorkspaceStore.getState().appendConsole({
                  id: activeConsoleId,
                  content: codeContent,
                  type: 'end',
                });
              }
            }}
          />
          <CopyButton code="icon-copy" copiedCode="icon-duigou" size={buttonSize} copyContent={codeContent} />
        </Flex>
      </div>
      <div className={styles.code}>
        <SQLPreview
          className={styles.codeHighlighter}
          sql={codeContent}
          language={lang}
          source="chat-message-sql-code-block"
          copyable={false}
          wrap={false}
        />
      </div>
      {resultDataList && (
        <div className={styles.resultWrapper}>
          {/* <div className={styles.resultFold} onClick={() => setIsResultVisible(!isResultVisible)}>
            <IconfontSvg size={12} code={isResultVisible ? 'icon-chevron-up' : 'icon-chevron-bottom'} />
          </div> */}
          {isResultVisible && (
            <div className={styles.result}>
              <SearchResult resultDataList={resultDataList} />
            </div>
          )}
        </div>
      )}
    </div>
  );
});

export default CodeBlock;
