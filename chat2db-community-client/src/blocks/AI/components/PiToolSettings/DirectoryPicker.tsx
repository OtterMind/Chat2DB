import { useEffect, useState } from 'react';
import { Button, Modal, Spin } from 'antd';
import { Folder, ArrowUp } from 'lucide-react';
import agentService, { AgentDirectoryListing } from '@/service/agent';
import i18n from '@/i18n';
import { agentErrorText } from '../../agentEvents';
import { useStyles } from './style';

export default function DirectoryPicker({ initialPath, onSelect, onCancel }: {
  initialPath: string;
  onSelect: (path: string) => void;
  onCancel: () => void;
}) {
  const { styles } = useStyles();
  const [path, setPath] = useState(initialPath);
  const [listing, setListing] = useState<AgentDirectoryListing>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void agentService.listDirectories({ path }, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setListing(result);
    })
.catch((failure) => {
      if (!controller.signal.aborted) setError(agentErrorText(failure) || i18n('setting.agent.enableFailed'));
    })
.finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [path]);

  return <Modal open title={i18n('setting.agent.workingDirectory.choose')} onCancel={onCancel}
    okText={i18n('common.button.confirm')} cancelText={i18n('common.button.cancel')}
    okButtonProps={{ disabled: loading || !!error || !listing }}
    onOk={() => { if (listing && !loading && !error) onSelect(listing.path); }}
         >
    <div className={styles.browserPath}>
      <Button size="small" icon={<ArrowUp size={14} />} disabled={loading || !listing?.parent}
        aria-label={i18n('setting.agent.workingDirectory.parent')}
        onClick={() => { if (listing?.parent) setPath(listing.parent); }}
      />
      <span>{listing?.path || path}</span>
    </div>
    {error && <div role="alert">{error}</div>}
    <Spin spinning={loading}>
      <div className={styles.directories}>
        {listing?.directories.map((directory) => <button key={directory.path} type="button"
          disabled={loading} onClick={() => setPath(directory.path)} className={styles.directoryEntry}
                                                 ><Folder size={16} /><span>{directory.name}</span></button>)}
      </div>
    </Spin>
  </Modal>;
}
