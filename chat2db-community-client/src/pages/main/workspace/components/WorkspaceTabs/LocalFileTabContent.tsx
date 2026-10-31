import { useState, type ReactNode } from 'react';
import { useDeepCompareEffect } from 'ahooks';
import { Button, Result, Spin } from 'antd';
import i18n from '@/i18n';
import { useWorkspaceStore } from '@/store/workspace';
import { getRestoredLocalFileReadRequest } from '@/store/workspace/utils/localFileWorkspaceTab';
import type { IWorkspaceTab } from '@/typings';

export default function LocalFileTabContent({ tab, children }: { tab: IWorkspaceTab; children: ReactNode }) {
  const [readFailed, setReadFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const request = getRestoredLocalFileReadRequest(tab);

  useDeepCompareEffect(() => {
    if (!request) {
      return;
    }
    let disposed = false;
    setReadFailed(false);
    void useWorkspaceStore.getState().readFile(request.filePath, request.fileExtension, request.context)
      .catch(() => {
        if (!disposed) {
          setReadFailed(true);
        }
      });
    return () => {
      disposed = true;
    };
  }, [request, attempt]);

  if (!request) {
    return <>{children}</>;
  }

  return readFailed ? (
    <Result
      status="error"
      title={i18n('common.text.failure')}
      extra={<Button onClick={() => setAttempt((value) => value + 1)}>{i18n('common.button.retry')}</Button>}
    />
  ) : (
    <div style={{ height: '100%', display: 'grid', placeItems: 'center' }}>
      <Spin />
    </div>
  );
}
