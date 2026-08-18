import type { ReactNode } from 'react';

import { useStyles } from './style';

export const PANEL_TOOLBAR_BUTTON_SIZE = {
  boxSize: 28,
  iconSize: 16,
  borderRadius: 5,
  strokeWidth: 2,
} as const;

interface PanelToolbarProps {
  leading: ReactNode;
  trailing?: ReactNode;
}

const PanelToolbar = ({ leading, trailing }: PanelToolbarProps) => {
  const { styles } = useStyles();

  return (
    <div className={styles.toolbar}>
      <div className={styles.leading}>{leading}</div>
      {trailing ? <div className={styles.trailing}>{trailing}</div> : null}
    </div>
  );
};

export default PanelToolbar;
