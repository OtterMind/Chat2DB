import { type ButtonHTMLAttributes, forwardRef, memo } from 'react';
import { ChevronDown } from 'lucide-react';
import { useStyles } from './style';

type DropdownChevronTriggerProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'>;

const DropdownChevronTrigger = forwardRef<HTMLButtonElement, DropdownChevronTriggerProps>(
  ({ children, className, ...buttonProps }, ref) => {
    const { styles, cx } = useStyles();

    return (
      <button {...buttonProps} ref={ref} type="button" className={cx(styles.trigger, className)}>
        {children ? <span className={styles.label}>{children}</span> : null}
        <span className={styles.chevronSlot}>
          <ChevronDown aria-hidden="true" size={14} strokeWidth={1.75} />
        </span>
      </button>
    );
  },
);

DropdownChevronTrigger.displayName = 'DropdownChevronTrigger';

export default memo(DropdownChevronTrigger);
