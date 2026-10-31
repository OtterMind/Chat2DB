import type { CollapseProps } from 'antd';
import { useStyles } from './style';

interface Props {
  items: CollapseProps['items'];
  fillHeight?: boolean;
}

/**
 * Stacked option groups of the import format step: every group is always visible, so the wizard
 * shows what it will parse without hiding the advanced options behind a collapse.
 */
export default function OptionsSections({ items, fillHeight = false }: Props) {
  const { styles, cx } = useStyles();
  return (
    <div className={cx(styles.sections, fillHeight && styles.fillSections)}>
      {(items ?? []).map((item) =>
        item ? (
          <section key={String(item.key)} className={styles.sectionBlock}>
            <div className={styles.sectionHeading}>{item.label}</div>
            <div className={styles.sectionBody}>{item.children}</div>
          </section>
        ) : null,
      )}
    </div>
  );
}
