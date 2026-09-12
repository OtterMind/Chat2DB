import { useEffect, useId, useState } from 'react';
import { useMergedState } from 'rc-util';
import { Checkbox, Popover, Spin, Tag, Tooltip } from 'antd';
import { HelpCircle, Settings2 } from 'lucide-react';
import DirectoryPicker from '@/components/DirectoryPicker';
import agentService, { AgentToolState } from '@/service/agent';
import { useGlobalStore } from '@/store/global';
import i18n from '@/i18n';
import feedback from '@/utils/feedback';
import { agentErrorText } from '../../agentEvents';
import { toolDescription } from './model';
import { useStyles } from './style';

export default function PiToolSettings(props: { open?: boolean; onOpenChange?: (open: boolean) => void }) {
  const { styles } = useStyles();
  const directoryInputId = useId();
  useGlobalStore((state) => state.baseSetting.language);
  const [picking, setPicking] = useState(false);
  const [open, setOpen] = useMergedState(false, { value: props.open, onChange: props.onOpenChange });
  const [tools, setTools] = useState<AgentToolState[]>([]);
  const [directory, setDirectory] = useState('');
  const [loading, setLoading] = useState(false);
  const [pending, setPending] = useState<'directory' | 'tool' | null>(null);
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
    })
      .catch((error) => {
      if (!controller.signal.aborted) setLoadError(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    })
      .finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [open]);

  const saveDirectory = async (workingDirectory: string) => {
    if (pending || workingDirectory === directory) return;
    setPending('directory');
    try {
      const settings = await agentService.saveWorkspaceSettings({ workingDirectory });
      setDirectory(settings.workingDirectory);
      feedback.success(i18n('common.message.modifySuccessfully'));
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPending(null);
    }
  };

  const chooseDirectory = async () => {
    if (pending || picking) return;
    setPicking(true);
    try {
      const selected = await agentService.selectDirectory();
      if (selected) await saveDirectory(selected);
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPicking(false);
    }
  };

  const changeTool = async (toolName: string, enabled: boolean) => {
    if (pending || picking) return;
    setPending('tool');
    try {
      const updated = await agentService.setToolEnabled({ toolName, enabled });
      setTools((current) => current.map((tool) => tool.name === toolName ? updated : tool));
    } catch (error) {
      feedback.error(agentErrorText(error) || i18n('setting.agent.enableFailed'));
    } finally {
      setPending(null);
    }
  };

  return (
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
            <div className={styles.directory}>
              <div className={styles.directoryLabel}>
                <label htmlFor={directoryInputId}>{i18n('setting.agent.workingDirectory')}</label>
                <Tooltip title={i18n('setting.agent.workingDirectory.hint')}>
                  <button type="button" className={styles.help} aria-label={i18n('setting.agent.workingDirectory.hint')}>
                    <HelpCircle size={14} />
                  </button>
                </Tooltip>
              </div>
              <DirectoryPicker id={directoryInputId} value={directory} disabled={!!pending || picking}
                emptyLabel={i18n('setting.agent.workingDirectory.choose')}
                clearLabel={i18n('stream.directory.clear')}
                onSelect={() => void chooseDirectory()} onClear={() => void saveDirectory('')}
              />
            </div>
            <div className={styles.tools}>
              {tools.filter((tool) => tool.category === 'BUILTIN').map((tool) =>
                  <div className={styles.row} key={tool.name}>
                    <div className={styles.rowHeader}>
                      <code>{tool.name}</code>
                      {tool.status === 'UNAVAILABLE' ? <Tag>{i18n('setting.agent.toolStatus.UNAVAILABLE')}</Tag> :
                        <Checkbox checked={tool.status === 'ENABLED'} disabled={!!pending || picking}
                          aria-label={`${i18n('setting.agent.tool.enable')} ${tool.name}`}
                          onChange={(event) => void changeTool(tool.name, event.target.checked)}
                        >{i18n('setting.agent.tool.enable')}</Checkbox>}

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
  );
}
