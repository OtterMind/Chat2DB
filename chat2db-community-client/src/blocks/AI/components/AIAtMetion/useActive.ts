import { useEvent } from 'rc-util';
import React, { useState } from 'react';

import { SuggestionItem } from './interface';

export default function useActive(
  items: SuggestionItem[],
  open: boolean,
  onSelect: (value: string[]) => void,
  onCancel: () => void,
) {
  const [activePaths, setActivePaths] = useState<string[]>([]);

  const activeValue = activePaths[0];

  const offsetRow = (offset: number) => {
    if (!items.length) return;
    const currentRowIndex = items.findIndex((item) => item.value === activeValue);
    const nextItem = items[(currentRowIndex + offset + items.length) % items.length];
    setActivePaths([nextItem.value]);

    // Add a delay to wait for the DOM to update before scrolling
    setTimeout(() => {
      // Gets the currently selected option element
      const activeElement = document.querySelector('.ant-cascader-menu-item-active');
      if (activeElement) {
        // ensures that the element is scrolled into the visible area
        activeElement.scrollIntoView({
          block: 'center',
          behavior: 'smooth',
        });
      }
    }, 0);
  };

  const onKeyDown = useEvent((e: React.KeyboardEvent) => {
    if (!open) {
      return;
    }
    switch (e.key) {
      case 'ArrowDown': {
        offsetRow(1);
        e.preventDefault();
        break;
      }

      case 'ArrowUp': {
        offsetRow(-1);
        e.preventDefault();
        break;
      }
      case 'Enter': {
        if (activeValue) {
          onSelect([activeValue]);
        }
        e.preventDefault();
        break;
      }

      case 'Escape': {
        onCancel();
        e.preventDefault();
        break;
      }
      default: {
        break;
      }
    }
  });

  React.useEffect(() => {
    if (open && items?.[0]?.value) {
      setActivePaths((previous) => (items.some((item) => item.value === previous[0]) ? previous : [items[0].value]));
    } else if (!open) {
      setActivePaths([]);
    }
  }, [open, items]);

  return [activePaths, onKeyDown] as const;
}
