import { useEffect, useState, type ReactNode } from 'react';

export default function LazyMount({ active, children }: { active: boolean; children: ReactNode }) {
  const [visited, setVisited] = useState(active);

  useEffect(() => {
    if (active) {
      setVisited(true);
    }
  }, [active]);

  return active || visited ? <>{children}</> : null;
}
