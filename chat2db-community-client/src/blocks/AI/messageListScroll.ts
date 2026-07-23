type MessageScrollTarget = Pick<HTMLElement, 'scrollLeft' | 'scrollTo'>;
type MessageScrollContainer = Pick<HTMLElement, 'scrollHeight' | 'scrollLeft' | 'scrollTo'>;

export function resetMessageContainerHorizontalScroll(container: Pick<HTMLElement, 'scrollLeft'>) {
  if (container.scrollLeft === 0) {
    return false;
  }
  container.scrollLeft = 0;
  return true;
}

export function scrollMessageContainerTo(
  container: MessageScrollTarget,
  top: number,
  behavior: ScrollBehavior = 'auto',
) {
  resetMessageContainerHorizontalScroll(container);
  container.scrollTo({
    top,
    left: 0,
    behavior,
  });
}

export function scrollMessageContainerToBottom(
  container: MessageScrollContainer,
  behavior: ScrollBehavior = 'auto',
) {
  scrollMessageContainerTo(container, container.scrollHeight, behavior);
}
