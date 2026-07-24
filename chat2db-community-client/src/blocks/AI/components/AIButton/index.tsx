import { useStyles } from './style';
import { useAIStore } from '@/store/ai';
import { IconButton } from '@chat2db/ui';
interface AIButtonProps {
  onClick: () => void;
  size?: 'lg' | 'md';
}

const AIButton = (props: AIButtonProps) => {
  const { onClick, size = 'lg' } = props;
  const { styles } = useStyles();
  const showPanel = useAIStore((s) => s.showPanel);
  return (
    <IconButton
      type="primary"
      isActive={showPanel}
      size={size}
      className={styles.aiButton}
      onClick={onClick}
      code={'icon-moren'}
    />
  );
};

export default AIButton;
