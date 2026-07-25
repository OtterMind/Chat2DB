import { useEffect, useRef, useState } from 'react';

/**
 * A custom Hook to provide automatic scrolling to the bottom in a scrollable container.
 * When new content is added, it automatically scrolls to the bottom,
 * regardless of whether the user scrolls up manually.
 * If in streaming output state, it will automatically decide whether to scroll to the bottom based on user behavior.
 *
 * @param triggerData The dependent data that triggers scrolling.
 * When the data changes, the scrolling logic runs again.
 * @param isStreaming Whether it is in streaming output state, used to control automatic scrolling logic.
 * @returns a ref object that needs to be bound to the scrollable container element.
 */
const useAutoScroll = (triggerData: any, isStreaming: boolean): React.RefObject<HTMLDivElement> => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isUserScrolling, setIsUserScrolling] = useState(false);
  const lastScrollTop = useRef(0);

  const scrollToBottom = (smooth: boolean = true) => {
    if (checkContainerHeight()) {
      containerRef.current?.scrollTo({
        top: containerRef.current.scrollHeight,
        // smooth
        behavior: smooth ? 'smooth' : 'auto',
      });
    }
    
  };

  const handleScroll = () => {
    if (!containerRef.current) return;

    const { scrollTop, scrollHeight, clientHeight } = containerRef.current;
    const isAtBottom = scrollTop + clientHeight >= scrollHeight - 5;

    setIsUserScrolling(!isAtBottom);
  };

  // Check if the height of the container has changed
  const checkContainerHeight = () => { 
    if (!containerRef.current) return;

    const { scrollHeight } = containerRef.current;

    // Compare the last height and return true if there is a change
    if (lastScrollTop.current !== scrollHeight) {
      lastScrollTop.current = scrollHeight;
      return true;
    }
    return false;
  }

  useEffect(() => {
    // If not in streaming output mode, always scroll to bottom
    if (!isStreaming || !isUserScrolling) {
      scrollToBottom(isStreaming);
    }
  }, [triggerData]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new MutationObserver(() => {
      // When the element changes, if it is not a streaming output, it will always scroll to the bottom.
      if (!isStreaming || !isUserScrolling) {
        scrollToBottom(isStreaming);
      }
    });

    observer.observe(container, { childList: true, subtree: true });
    container.addEventListener('scroll', handleScroll);

    return () => {
      observer.disconnect();
      container.removeEventListener('scroll', handleScroll);
    };
  }, [isStreaming, isUserScrolling]);

  return containerRef;
};

export default useAutoScroll;
