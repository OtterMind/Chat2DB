import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Collapse, Empty, List, Space, Spin, Tabs, Tag, Typography } from 'antd';
import { Copy, RefreshCw } from 'lucide-react';

import i18n from '@/i18n';
import sqlService, { type IInnodbDeadlockTransaction, type IInnodbStatusResponse } from '@/service/sql';
import { copyToClipboard } from '@/utils';
import { staticMessage } from '@chat2db/ui';
import {
  applyInnodbStatusFailure,
  applyInnodbStatusSuccess,
  beginInnodbStatusRefresh,
  getInnodbStatusCopyText,
  initialInnodbStatusViewState,
} from './state';
import { useStyles } from './style';

interface InnodbStatusPanelProps {
  data?: {
    dataSourceId?: number;
    databaseName?: string;
    dataSourceName?: string;
    objectName?: string;
  };
}

const InnodbStatusPanel = ({ data }: InnodbStatusPanelProps) => {
  const { styles } = useStyles();
  const [state, setState] = useState(initialInnodbStatusViewState);
  const dataSourceId = data?.dataSourceId;

  const loadStatus = useCallback(() => {
    if (!dataSourceId) {
      setState((currentState) =>
        applyInnodbStatusFailure(currentState, i18n('workspace.innodbStatus.selectDataSource')),
      );
      return;
    }
    setState(beginInnodbStatusRefresh);
    sqlService
      .getInnodbStatus({
        dataSourceId,
        databaseName: data?.databaseName,
      })
      .then((result) => {
        setState((currentState) => applyInnodbStatusSuccess(currentState, result, new Date().toISOString()));
      })
      .catch((error) => {
        setState((currentState) => applyInnodbStatusFailure(currentState, error));
      });
  }, [data?.databaseName, dataSourceId]);

  useEffect(() => {
    setState(initialInnodbStatusViewState);
    loadStatus();
  }, [loadStatus]);

  const copyRawText = () => {
    if (copyToClipboard(getInnodbStatusCopyText(state.result))) {
      staticMessage.success(i18n('common.button.copySuccessfully'));
    }
  };

  const items = useMemo(
    () => [
      {
        key: 'summary',
        label: i18n('workspace.innodbStatus.latestDeadlock'),
        children: <DeadlockSummary result={state.result} />,
      },
      {
        key: 'sections',
        label: i18n('workspace.innodbStatus.sections'),
        children: <SectionList result={state.result} />,
      },
      {
        key: 'raw',
        label: i18n('workspace.innodbStatus.rawOutput'),
        children: (
          <pre className={`${styles.sectionText} ${styles.rawText}`}>{state.result?.rawText || ''}</pre>
        ),
      },
    ],
    [state.result, styles.rawText, styles.sectionText],
  );

  return (
    <div className={styles.root}>
      <div className={styles.header}>
        <div className={styles.headerTitle}>
          <div className={styles.title}>{i18n('workspace.innodbStatus.title')}</div>
          <div className={styles.subtitle}>
            {data?.objectName || data?.dataSourceName || i18n('workspace.text.pleaseSelectDataSource')}
            {state.lastSuccessAt ? ` · ${i18n('workspace.innodbStatus.lastSuccessAt')} ${state.lastSuccessAt}` : ''}
          </div>
        </div>
        <Space size={6}>
          <Button
            size="small"
            icon={<Copy size={14} />}
            disabled={!state.result?.rawText}
            onClick={copyRawText}
          >
            {i18n('common.button.copy')}
          </Button>
          <Button size="small" icon={<RefreshCw size={14} />} loading={state.loading} onClick={loadStatus}>
            {i18n('common.button.refresh')}
          </Button>
        </Space>
      </div>
      <div className={styles.body}>
        <Spin spinning={state.loading && !state.result}>
          <div className={styles.stack}>
            {state.error && (
              <Alert
                type="warning"
                showIcon
                message={i18n('workspace.innodbStatus.refreshFailed')}
                description={
                  state.lastSuccessAt
                    ? `${state.error} ${i18n('workspace.innodbStatus.retainedPreviousResult')}`
                    : state.error
                }
              />
            )}
            {state.result?.messages?.length ? <ParserMessages result={state.result} /> : null}
            {state.result ? (
              <Tabs className={styles.tabs} size="small" items={items} />
            ) : (
              <Empty description={i18n('workspace.innodbStatus.empty')} />
            )}
          </div>
        </Spin>
      </div>
    </div>
  );
};

const ParserMessages = ({ result }: { result: IInnodbStatusResponse }) => (
  <Alert
    type="info"
    showIcon
    message={i18n('workspace.innodbStatus.parserMessages')}
    description={
      <ul>
        {result.messages.map((message) => (
          <li key={`${message.code}-${message.sectionTitle || ''}-${message.line || ''}`}>
            {message.code}: {message.message}
          </li>
        ))}
      </ul>
    }
  />
);

const DeadlockSummary = ({ result }: { result: IInnodbStatusResponse | null }) => {
  const { styles } = useStyles();
  const deadlock = result?.latestDeadlock;
  if (!deadlock) {
    return <Empty description={i18n('workspace.innodbStatus.empty')} />;
  }
  if (!deadlock.found) {
    return <Empty description={deadlock.message || i18n('workspace.innodbStatus.noDeadlock')} />;
  }
  return (
    <div className={styles.stack}>
      <Space wrap>
        {deadlock.time && <Tag>{deadlock.time}</Tag>}
        {deadlock.victimTransaction && (
          <Tag color="red">
            {i18n('workspace.innodbStatus.victim')} {deadlock.victimTransaction}
          </Tag>
        )}
      </Space>
      {deadlock.transactions.map((transaction) => (
        <TransactionSummary key={transaction.marker} transaction={transaction} />
      ))}
    </div>
  );
};

const TransactionSummary = ({ transaction }: { transaction: IInnodbDeadlockTransaction }) => {
  const { styles } = useStyles();
  return (
    <div className={styles.transaction}>
      <Space wrap>
        <Typography.Text strong>
          {i18n('workspace.innodbStatus.transaction')} {transaction.marker}
        </Typography.Text>
        {transaction.transactionId && <Tag>{transaction.transactionId}</Tag>}
        {transaction.mysqlThreadId && (
          <Tag>
            {i18n('workspace.innodbStatus.mysqlThread')} {transaction.mysqlThreadId}
          </Tag>
        )}
        {transaction.victim && <Tag color="red">{i18n('workspace.innodbStatus.victim')}</Tag>}
      </Space>
      {transaction.sql && <pre className={styles.sql}>{transaction.sql}</pre>}
      <LockList title={i18n('workspace.innodbStatus.heldLocks')} locks={transaction.heldLocks} />
      <LockList title={i18n('workspace.innodbStatus.waitedLocks')} locks={transaction.waitedLocks} />
    </div>
  );
};

const LockList = ({ title, locks }: { title: string; locks: string[] }) => {
  const { styles } = useStyles();
  if (!locks.length) {
    return null;
  }
  return (
    <div>
      <Typography.Text type="secondary">{title}</Typography.Text>
      <ul className={styles.lockList}>
        {locks.map((lock, index) => (
          <li key={`${index}-${lock}`}>{lock}</li>
        ))}
      </ul>
    </div>
  );
};

const SectionList = ({ result }: { result: IInnodbStatusResponse | null }) => {
  const { styles } = useStyles();
  if (!result?.sections?.length) {
    return <Empty description={i18n('workspace.innodbStatus.noSections')} />;
  }
  return (
    <List
      size="small"
      dataSource={result.sections}
      renderItem={(section) => (
        <List.Item>
          <Collapse
            ghost
            style={{ width: '100%' }}
            items={[
              {
                key: section.normalizedTitle,
                label: (
                  <Space wrap>
                    <Typography.Text strong>{section.title}</Typography.Text>
                    <Tag>{`${section.startLine}-${section.endLine}`}</Tag>
                    {!section.recognized && <Tag color="orange">{i18n('workspace.innodbStatus.unknown')}</Tag>}
                  </Space>
                ),
                children: <pre className={styles.sectionText}>{section.text}</pre>,
              },
            ]}
          />
        </List.Item>
      )}
    />
  );
};

export default InnodbStatusPanel;
