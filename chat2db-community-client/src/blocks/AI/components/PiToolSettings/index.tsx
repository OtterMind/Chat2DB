import React, { useEffect, useId, useState } from 'react';
import { Button, Checkbox, Input, Modal, Popover, Spin, Tag } from 'antd';
import { Settings2 } from 'lucide-react';
import agentService, { AgentToolState } from '@/service/agent';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import feedback from '@/utils/feedback';
import { confirmBetaFeature } from '@/utils/confirmBetaFeature';
import { agentErrorText } from '../../agentEvents';
import { toolDescription } from './model';
import { useStyles } from './style';

export default function PiToolSettings() {
  const { styles } = useStyles();
  const directoryInputId = useId();
  useGlobalStore((state) => state.baseSetting.language);
  const [modal, contextHolder] = Modal.useModal();
  const [open, setOpen] = useState(false);
  const [tools, setTools] = useState<AgentToolState[]>([]);
  const [directory, setDirectory] = useState('');
  const [draft, setDraft] = useState('');
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState<'directory' | 'bash' | null>(null);
  const [loadError, setLoadError] = useState('');

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setLoading(true);
    setLoadError('');
    void Promise.all([
      agentService.listTools(undefined, { signal: controller.signal }),
      agentService.getShellSettings(undefined, { signal: controller.signal }),
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
    if (pending || draft === directory) return;
    setPending('directory');
    try {
      const settings = await agentService.saveShellSettings({ workingDirectory: draft });
      setDirectory(settings.workingDirectory);
      setDraft(settings.workingDirectory);
      feedback.success(i18n('common.message.modifySuccessfully'));
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPending(null);
    }
  };

  const changeBash = async (enabled: boolean) => {
    if (pending) return;
    setPending('bash');
    try {
      if (enabled && !await confirmBetaFeature(modal, {
        title: i18n('setting.agent.bash.confirmTitle'),
        content: i18n('setting.agent.bash.confirmContent'),
        okText: i18n('common.button.confirm'),
        cancelText: i18n('common.button.cancel'),
      })) return;
      const result = enabled ? await agentService.enableBash({ confirmed: true }) : await agentService.disableBash();
      const status = !result.available ? 'UNAVAILABLE' : result.enabled ? 'ENABLED' : 'DISABLED';
      setTools((previous) => previous.map((tool) => tool.name === 'bash' ? { ...tool, status } : tool));
      if (enabled && !result.enabled) feedback.error(Object.values(result.diagnostics).join('; '));
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPending(null);
    }
  };

  return <>
    {contextHolder}
    <Popover trigger="click" placement="topRight" open={open}
      onOpenChange={(value) => { if (!pending) setOpen(value); }}
      content={
        <div className={styles.panel} onKeyDown={(event) => {
          if (event.key === 'Escape' && !pending) { event.stopPropagation(); setOpen(false); }
        }}
        >
          <div className={styles.title}>{i18n('setting.agent.tools.title')}</div>
          {loading ? <Spin size="small" /> : loadError ? <span role="alert">{loadError}</span> : <>
            <form className={styles.directory} onSubmit={saveDirectory}>
              <label htmlFor={directoryInputId}>{i18n('setting.agent.workingDirectory')}</label>
              <Input id={directoryInputId} value={draft} allowClear disabled={!!pending}
                placeholder={i18n('setting.agent.workingDirectory.default')}
                onChange={(event) => setDraft(event.target.value)}
              />
              <div className={styles.hint}>{i18n('setting.agent.workingDirectory.hint')}</div>
              <div className={styles.actions}>
                <Button size="small" disabled={!!pending || draft === directory}
                  onClick={() => setDraft(directory)}
                >{i18n('common.button.cancel')}</Button>
                <Button size="small" type="primary" htmlType="submit" loading={pending === 'directory'}
                  disabled={draft === directory}
                >{i18n('common.button.save')}</Button>
              </div>
            </form>
            <div className={styles.tools}>
              {(['BUILTIN', 'DATABASE'] as const).map((category) => <React.Fragment key={category}>
                <div className={styles.group}>{i18n(`setting.agent.tools.${category}`)}</div>
                {tools.filter((tool) => tool.category === category).map((tool) =>
                  <div className={styles.row} key={tool.name}>
                    <div className={styles.rowHeader}>
                      <code>{tool.name}</code>
                      {tool.name === 'bash' && tool.status !== 'UNAVAILABLE'
                        ? <Checkbox aria-label={i18n('setting.agent.bash.label')}
                            checked={tool.status === 'ENABLED'} disabled={!!pending}
                            onChange={(event) => void changeBash(event.target.checked)}
                          >{i18n('setting.agent.bash.label')}</Checkbox>
                        : <Tag color={tool.status === 'ENABLED' ? 'green' : undefined}>
                          {i18n(`setting.agent.toolStatus.${tool.status}`)}
                        </Tag>}
                    </div>
                    <div className={styles.hint}>{toolDescription(tool, i18n)}</div>
                  </div>)}
              </React.Fragment>)}
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
