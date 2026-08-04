export interface TableViewport {
  scrollLeft: number;
  scrollTop: number;
}

export interface ReleasableTableInstance {
  completeEditCell?: () => void;
  release: () => void;
  getScrollLeft?: () => number;
  getScrollTop?: () => number;
}

export interface TableInstanceRef<T extends ReleasableTableInstance> {
  current: T | null;
}

export function releaseTableInstance<T extends ReleasableTableInstance>(
  instanceRef: TableInstanceRef<T>,
  onRelease?: () => void,
): TableViewport | null {
  const instance = instanceRef.current;
  if (!instance) {
    return null;
  }

  instanceRef.current = null;
  try {
    instance.completeEditCell?.();
    return {
      scrollLeft: instance.getScrollLeft?.() || 0,
      scrollTop: instance.getScrollTop?.() || 0,
    };
  } finally {
    try {
      instance.release();
    } finally {
      onRelease?.();
    }
  }
}
