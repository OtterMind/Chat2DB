import type { ReactNode } from 'react';

interface SearchTargetLabelProps {
  children: ReactNode;
  className?: string;
  targetId: string;
}

export default function SearchTargetLabel({ children, className, targetId }: SearchTargetLabelProps) {
  return (
    <span className={className} data-setting-search-id={targetId}>
      {children}
    </span>
  );
}
