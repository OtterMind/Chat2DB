import React, { useEffect, useId, useState } from 'react';
import { Button, Input, Popover, Spin, Tag } from 'antd';
import { Settings2 } from 'lucide-react';
import agentService, { AgentToolState } from '@/service/agent';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import feedback from '@/utils/feedback';
import jcefApi from '@/jcef';
import { isJcefApiAvailable } from '@/jcef/base';
import DirectoryPicker from './DirectoryPicker';
import { agentErrorText } from '../../agentEvents';
import { toolDescription } from './model';
import { useStyles } from './style';

export default function PiToolSettings() {
  const { styles } = useStyles();
  const directoryInputId = useId();
  useGlobalStore((state) => state.baseSetting.language);
  const [picking, setPicking] = useState(false);
  const [open, setOpen] = useState(false);
  const [tools, setTools] = useState<AgentToolState[]>([]);
  const [directory, setDirectory] = useState('');
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState(false);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setLoading(true);
    setLoadError('');
    void Promise.all([
      agentService.listTools(undefined, { signal: controller.signal }),
      agentService.getWorkspaceSettings(undefined, { signal: controller.signal }),
    ]).then(([catalog, settings]) => {
      if (controller.signal.aborted) return;
      setTools(catalog);
      setDirectory(settings.workingDirectory);
      setDraft(settings.workingDirectory);
    })
      .catch((error) => {
      if (!controller.signal.aborted) setLoadError(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    })
      .finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [open]);

  const saveDirectory = async (event: React.FormEvent) => {
    event.preventDefault();
    if (pending || picking || draft === directory) return;
    setPending(true);
    try {
      const settings = await agentService.saveWorkspaceSettings({ workingDirectory: draft });
      setDirectory(settings.workingDirectory);
      setDraft(settings.workingDirectory);
      feedback.success(i18n('common.message.modifySuccessfully'));
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPending(false);
    }
  };

  const chooseDirectory = async () => {
    if (pending || picking) return;
    setPicking(true);
    if (!isJcefApiAvailable()) return;
    try {
      const selected = await jcefApi.selectDirectory();
      if (selected) setDraft(selected);
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPicking(false);
    }
  };

  return <>
    {picking && !isJcefApiAvailable() && <DirectoryPicker initialPath={draft}
      onCancel={() => setPicking(false)}
      onSelect={(path) => { setDraft(path); setPicking(false); }}
                                         />}
    <Popover trigger="click" placement="topRight" open={open && !picking}
      align={{ offset: [0, 8], overflow: { adjustX: true, adjustY: true, shiftY: true } }}
      onOpenChange={(value) => { if (!pending && !picking) setOpen(value); }}
      content={
        <div className={styles.panel} onKeyDown={(event) => {
          if (event.key === 'Escape' && !pending && !picking) { event.stopPropagation(); setOpen(false); }
        }}
        >
          <div className={styles.title}>{i18n('setting.agent.tools.title')}</div>
          {loading ? <Spin size="small" /> : loadError ? <span role="alert">{loadError}</span> : <>
            <form className={styles.directory} onSubmit={saveDirectory}>
              <label htmlFor={directoryInputId}>{i18n('setting.agent.workingDirectory')}</label>
              <Input id={directoryInputId} value={draft} allowClear disabled={pending || picking}
                placeholder={i18n('setting.agent.workingDirectory.default')}
                onChange={(event) => setDraft(event.target.value)}
              />
              <Button size="small" disabled={pending || picking} onClick={() => void chooseDirectory()}>
                {i18n('setting.agent.workingDirectory.choose')}
              </Button>
              <div className={styles.hint}>{i18n('setting.agent.workingDirectory.hint')}</div>
              <div className={styles.actions}>
                <Button size="small" disabled={pending || picking || draft === directory}
                  onClick={() => setDraft(directory)}
                >{i18n('common.button.cancel')}</Button>
                <Button size="small" type="primary" htmlType="submit" loading={pending}
                  disabled={picking || draft === directory}
                >{i18n('common.button.save')}</Button>
              </div>
            </form>
            <div className={styles.tools}>
              {tools.filter((tool) => tool.category === 'BUILTIN').map((tool) =>
                  <div className={styles.row} key={tool.name}>
                    <div className={styles.rowHeader}>
                      <code>{tool.name}</code>
                      <Tag color={tool.status === 'ENABLED' ? 'green' : undefined}>
                        {i18n(`setting.agent.toolStatus.${tool.status}`)}
                      </Tag>
                    </div>
                    <div className={styles.hint}>{toolDescription(tool, i18n)}</div>
                  </div>)}
            </div>
          </>}
        </div>
      }
    >
      <button type="button" className={styles.trigger} aria-label={i18n('setting.agent.tools.title')}>
        <Settings2 size={14} />
      </button>
    </Popover>
  </>;
}
