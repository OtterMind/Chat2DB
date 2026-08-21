import { useStyles } from './style';
import { useAIStore } from '@/store/ai';
import { IconButton, type IconSize } from '@chat2db/ui';
import { Sparkles } from 'lucide-react';
interface AIButtonProps {
  onClick: () => void;
  size?: IconSize;
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
      icon={Sparkles}
    />
  );
};

export default AIButton;
