import { Input, Modal } from 'antd';
import i18n from '@/i18n';
import type { PiCallOptions } from '../contract';

// A Web workspace path belongs to the Pi server, not the browser's filesystem.
export function promptServerDirectory(current: string, options?: PiCallOptions): Promise<string | null> {
  options?.signal?.throwIfAborted();
  return new Promise((resolve, reject) => {
    let value = current;
    const cleanup = () => options?.signal?.removeEventListener('abort', abort);
    const dialog = Modal.confirm({
      title: i18n('setting.agent.workingDirectory'),
      icon: null,
      content: <>
        <p>{i18n('setting.agent.workingDirectory.serverHint')}</p>
        <Input autoFocus defaultValue={current} aria-label={i18n('setting.agent.workingDirectory')}
          onChange={(event) => { value = event.target.value; }}
        />
      </>,
      onOk: () => { cleanup(); resolve(value.trim()); },
      onCancel: () => { cleanup(); resolve(null); },
    });
    const abort = () => { dialog.destroy(); cleanup(); reject(options?.signal?.reason); };
    options?.signal?.addEventListener('abort', abort, { once: true });
    if (options?.signal?.aborted) abort();
  });
}
