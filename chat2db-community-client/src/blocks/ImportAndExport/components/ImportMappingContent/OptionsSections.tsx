import { Collapse, type CollapseProps } from 'antd';
import { useStyles } from './style';

interface Props {
  activeKeys: string[];
  onActiveKeysChange: (keys: string[]) => void;
  items: CollapseProps['items'];
}

/** Shared section shell for the import option panels. */
export default function OptionsSections({ activeKeys, onActiveKeysChange, items }: Props) {
  const { styles } = useStyles();
  return (
    <Collapse
      className={styles.sections}
      ghost
      size="small"
      activeKey={activeKeys}
      onChange={(keys) => onActiveKeysChange(Array.isArray(keys) ? keys : [keys])}
      items={items}
    />
  );
}
