const TREE_SCROLL_END_PADDING = 8;

export interface TreeTitleWidth {
  left: number;
  width: number;
}

export function resolveNextTreeScrollWidth(
  currentWidth: number | undefined,
  measuredWidth: number,
  resetMeasurement: boolean,
) {
  return resetMeasurement ? measuredWidth : Math.max(currentWidth || 0, measuredWidth);
}

export function resolveTreeVirtualScrollOffset(marginLeft: string, marginRight: string) {
  const offsets = [marginLeft, marginRight].map((margin) => {
    const value = Number.parseFloat(margin);
    return Number.isFinite(value) ? -value : 0;
  });
  return Math.max(0, ...offsets);
}

export function resolveTreeScrollWidth(containerWidth: number, titles: TreeTitleWidth[]) {
  return Math.ceil(
    titles.reduce(
      (maximum, title) => Math.max(maximum, title.left + title.width + TREE_SCROLL_END_PADDING),
      Math.max(0, containerWidth),
    ),
  );
}

export function measureTreeScrollWidth(container: HTMLElement) {
  const containerRect = container.getBoundingClientRect();
  const holderInner = container.querySelector<HTMLElement>('.ant-tree-list-holder-inner');
  const holderInnerStyle = holderInner ? window.getComputedStyle(holderInner) : null;
  const virtualScrollOffset = holderInnerStyle
    ? resolveTreeVirtualScrollOffset(holderInnerStyle.marginLeft, holderInnerStyle.marginRight)
    : 0;
  const titles = Array.from(container.querySelectorAll<HTMLElement>('.ant-tree-title > :first-child')).map((title) => {
    const titleRect = title.getBoundingClientRect();
    return {
      left: Math.max(0, titleRect.left - containerRect.left + virtualScrollOffset),
      width: Math.max(title.scrollWidth, titleRect.width),
    };
  });

  return resolveTreeScrollWidth(container.clientWidth, titles);
}
