import assert from 'node:assert/strict';
import { domWindow } from './index.dom.test.setup';
import { act } from 'react';
import { SelectEditor } from './index';
import { MultiSelectEditor } from '../MultiSelectIEditor';

const keyCodes: Record<string, number> = {
  Enter: 13,
  Escape: 27,
  Tab: 9,
};

const dispatchKey = (target: HTMLElement, key: keyof typeof keyCodes) => {
  const event = new domWindow.KeyboardEvent('keydown', {
    bubbles: true,
    cancelable: true,
    code: key,
    key,
  });
  Object.defineProperties(event, {
    keyCode: { configurable: true, value: keyCodes[key] },
    which: { configurable: true, value: keyCodes[key] },
  });
  target.dispatchEvent(event);
};

const clickOption = (option: HTMLElement) => {
  option.dispatchEvent(new domWindow.MouseEvent('mousemove', { bubbles: true, cancelable: true }));
  option.dispatchEvent(new domWindow.MouseEvent('click', { bubbles: true, cancelable: true }));
};

const nextTask = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

async function main() {
  const options = [
    { label: 'Alpha', value: 'ALPHA' },
    { label: 'Beta', value: 'BETA' },
  ];

  const mountEditor = async (editor: InstanceType<typeof SelectEditor>, value: unknown, handleTab = true) => {
    const container = document.createElement('div');
    container.tabIndex = 0;
    Object.defineProperty(container, 'clientHeight', { configurable: true, value: 300 });
    document.body.appendChild(container);

    let active = true;
    let committedValue: unknown;
    let endCount = 0;
    const endEdit = () => {
      if (!active) {
        return;
      }
      active = false;
      committedValue = editor.getValue();
      editor.onEnd();
      endCount++;
    };

    if (handleTab) {
      container.addEventListener('keydown', (event) => {
        if (event.key === 'Tab') {
          endEdit();
          container.focus();
        }
      });
    }

    await act(async () => {
      editor.onStart({
        col: 1,
        row: 1,
        container,
        endEdit,
        referencePosition: { rect: { top: 10, left: 10, width: 160, height: 28 } },
        value,
      });
      await nextTask();
      await nextTask();
    });

    const input = container.querySelector<HTMLInputElement>('.ant-select-selection-search-input');
    assert.ok(input, 'the real Ant Select input is mounted');
    const popup = Array.from(document.querySelectorAll<HTMLElement>('.chat2db-result-set-select-popup')).at(-1);
    assert.ok(popup, 'the real Ant Select popup is mounted');
    const firstOption = popup.querySelector<HTMLElement>('.ant-select-item-option');
    assert.ok(firstOption, 'the real Ant Select option list is mounted');

    return {
      container,
      editor,
      firstOption,
      input,
      getCommittedValue: () => committedValue,
      getEndCount: () => endCount,
      isActive: () => active,
      cleanup: () => {
        if (active) {
          active = false;
          editor.onEnd();
        }
        container.remove();
      },
    };
  };

  const multi = await mountEditor(new MultiSelectEditor(options, {}), '');
  await act(async () => {
    clickOption(multi.firstOption);
    await nextTask();
  });
  assert.equal(multi.editor.getValue(), 'ALPHA', 'the Ant Select option updates the SET editor value');
  assert.equal(multi.getEndCount(), 0, 'selecting a SET member keeps multi-select editing open');
  assert.ok(
    multi.firstOption.classList.contains('ant-select-item-option-active'),
    'the selected SET member remains the option that rc-select would toggle on Tab',
  );

  await act(async () => {
    multi.input.focus();
    dispatchKey(multi.input, 'Tab');
    await nextTask();
  });
  assert.equal(multi.getCommittedValue(), 'ALPHA', 'Tab commits without toggling the active SET member again');
  assert.equal(multi.getEndCount(), 1, 'the table-level Tab handler completes editing exactly once');
  assert.equal(document.activeElement, multi.container, 'the table container retains focus after Tab navigation');
  multi.cleanup();

  const single = await mountEditor(new SelectEditor(options, {}), 'BETA');
  await act(async () => {
    clickOption(single.firstOption);
    await nextTask();
  });
  assert.equal(single.getCommittedValue(), 'ALPHA', 'selecting a different ENUM option commits the new value');
  assert.equal(single.getEndCount(), 1, 'ENUM option selection completes editing exactly once');
  assert.equal(document.activeElement, single.container, 'ENUM completion restores VTable keyboard focus');
  single.cleanup();

  const unchanged = await mountEditor(new SelectEditor(options, {}), 'BETA');
  await act(async () => {
    dispatchKey(unchanged.input, 'Enter');
    await nextTask();
  });
  assert.equal(unchanged.getCommittedValue(), 'BETA', 'Enter can finish an unchanged ENUM value');
  assert.equal(unchanged.getEndCount(), 1, 'unchanged ENUM Enter completes editing exactly once');
  unchanged.cleanup();

  const cancelled = await mountEditor(new MultiSelectEditor(options, {}), 'BETA');
  await act(async () => {
    clickOption(cancelled.firstOption);
    await nextTask();
  });
  await act(async () => {
    cancelled.input.focus();
    dispatchKey(cancelled.input, 'Escape');
    await nextTask();
  });
  assert.equal(cancelled.getCommittedValue(), 'BETA', 'Escape restores the original SET value');
  assert.equal(cancelled.getEndCount(), 1, 'Escape ends editing exactly once');
  assert.equal(document.activeElement, cancelled.container, 'Escape restores VTable keyboard focus');
  cancelled.cleanup();

  const committedByEnter = await mountEditor(new MultiSelectEditor(options, {}), 'ALPHA,BETA');
  await act(async () => {
    committedByEnter.input.focus();
    dispatchKey(committedByEnter.input, 'Enter');
    await nextTask();
  });
  assert.equal(committedByEnter.getCommittedValue(), 'ALPHA,BETA', 'Enter commits the unchanged SET value');
  assert.equal(committedByEnter.getEndCount(), 1, 'Enter ends SET editing exactly once');
  assert.equal(document.activeElement, committedByEnter.container, 'Enter restores VTable keyboard focus');
  committedByEnter.cleanup();

  const lastCell = await mountEditor(new MultiSelectEditor(options, {}), 'ALPHA', false);
  await act(async () => {
    dispatchKey(lastCell.input, 'Tab');
    await nextTask();
  });
  assert.equal(lastCell.getCommittedValue(), 'ALPHA', 'last-cell Tab commits without changing the SET value');
  assert.equal(lastCell.getEndCount(), 1, 'the editor completes Tab when VTable has no next cell');
  assert.equal(document.activeElement, lastCell.container, 'last-cell Tab restores focus to the table');
  lastCell.cleanup();

  domWindow.close();
  console.log('SelectEditor DOM interaction tests passed');
  process.exit(0);
}

main().catch((error) => {
  console.error(error);
  domWindow.close();
  process.exit(1);
});
