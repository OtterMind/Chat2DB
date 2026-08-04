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

export interface SafeTableReleaseResult {
  released: boolean;
  viewport: TableViewport | null;
  error: unknown | null;
}

export function getActiveTableInstance<T>(active: boolean, instance: T | null): T | null {
  return active ? instance : null;
}

export function isCurrentTableInstance<T extends ReleasableTableInstance>(
  active: boolean,
  instance: T | null,
  instanceRef: TableInstanceRef<T>,
): instance is T {
  return active && instance !== null && instanceRef.current === instance;
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

export function releaseTableInstanceSafely<T extends ReleasableTableInstance>(
  instanceRef: TableInstanceRef<T>,
  onRelease?: () => void,
): SafeTableReleaseResult {
  if (!instanceRef.current) {
    return { released: false, viewport: null, error: null };
  }

  try {
    return {
      released: true,
      viewport: releaseTableInstance(instanceRef, onRelease),
      error: null,
    };
  } catch (error) {
    return { released: true, viewport: null, error };
  }
}
