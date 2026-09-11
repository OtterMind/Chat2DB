import type { CSSProperties } from 'react';

type TabAccentStyle = CSSProperties & {
  '--chat2db-tab-accent-color'?: string;
};

export function getTabAccentStyle(accentColor?: string | null): TabAccentStyle {
  return accentColor ? { '--chat2db-tab-accent-color': accentColor } : {};
}
