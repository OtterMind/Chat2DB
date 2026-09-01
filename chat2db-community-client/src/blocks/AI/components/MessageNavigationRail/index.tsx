import { memo, useState, type CSSProperties } from 'react';
import { cx } from 'antd-style';

import i18n from '@/i18n';
import {
  getMessageNavigationRailWidth,
  type UserMessageNavigationItem,
} from '../../messageNavigation';
import { useStyles } from './style';

interface MessageNavigationRailProps {
  items: UserMessageNavigationItem[];
  onNavigate: (messageId: string) => void;
}

const MessageNavigationRail = ({ items, onNavigate }: MessageNavigationRailProps) => {
  const { styles } = useStyles();
  const [selectedIndex, setSelectedIndex] = useState(-1);
  const [railScrollTop, setRailScrollTop] = useState(0);
  const activeIndex = selectedIndex < items.length ? selectedIndex : -1;
  const selectedItem = activeIndex >= 0 ? items[activeIndex] : null;

  if (!items.length) {
    return null;
  }

  return (
    <div
      className={styles.rail}
      aria-label={i18n('stream.messageNavigation.title')}
      onMouseLeave={() => setSelectedIndex(-1)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
          setSelectedIndex(-1);
        }
      }}
    >
      <div
        className={styles.list}
        onScroll={(event) => setRailScrollTop(event.currentTarget.scrollTop)}
      >
        {items.map((item, itemIndex) => (
          <button
            key={item.id}
            type="button"
            className={cx(styles.item, activeIndex === itemIndex && styles.itemActive)}
            style={
              {
                '--message-navigation-rail-width': `${getMessageNavigationRailWidth(itemIndex, activeIndex)}px`,
              } as CSSProperties
            }
            aria-label={`${item.index}. ${item.label}`}
            onMouseEnter={() => setSelectedIndex(itemIndex)}
            onFocus={() => setSelectedIndex(itemIndex)}
            onClick={() => onNavigate(item.id)}
          >
            <span />
          </button>
        ))}
      </div>
      {selectedItem ? (
        <div className={styles.preview} style={{ top: activeIndex * 14 + 6 - railScrollTop }}>
          <div className={styles.previewQuestion}>{selectedItem.label}</div>
          {selectedItem.assistantPreview ? (
            <div className={styles.previewAnswer}>{selectedItem.assistantPreview}</div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
};

export default memo(MessageNavigationRail);
