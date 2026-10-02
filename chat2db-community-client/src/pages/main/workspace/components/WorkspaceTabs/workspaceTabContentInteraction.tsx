import { useCallback, useEffect, useRef, type ComponentPropsWithoutRef } from 'react';

const EMBEDDED_CONTENT_SELECTOR = 'iframe, object, embed';

function getEmbeddedContentDocument(host: Element): Document | null {
  if (host.tagName !== 'IFRAME' && host.tagName !== 'OBJECT') {
    return null;
  }
  try {
    return (host as HTMLIFrameElement | HTMLObjectElement).contentDocument;
  } catch {
    return null;
  }
}

type WorkspaceTabContentInteractionProps = Omit<
  ComponentPropsWithoutRef<'div'>,
  'onFocusCapture' | 'onPointerDownCapture'
> & {
  isActive: boolean;
  isVisible: boolean;
  onActivate: () => void;
};

export function WorkspaceTabContentInteraction({
  isActive,
  isVisible,
  onActivate,
  ...props
}: WorkspaceTabContentInteractionProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const activeRef = useRef(isActive);
  const onActivateRef = useRef(onActivate);
  activeRef.current = isActive;
  onActivateRef.current = onActivate;

  const activate = useCallback(() => {
    if (activeRef.current) {
      return;
    }
    onActivateRef.current();
    activeRef.current = true;
  }, []);

  useEffect(() => {
    if (!isVisible) {
      return;
    }
    const container = containerRef.current;
    const ownerDocument = container?.ownerDocument;
    const ownerWindow = ownerDocument?.defaultView;
    if (!container || !ownerDocument || !ownerWindow) {
      return;
    }

    const hostCleanups = new Map<Element, () => void>();
    let focusCheckTimer: number | undefined;

    const attachEmbeddedHost = (host: Element) => {
      let detachDocumentListeners = () => undefined;
      const attachDocumentListeners = () => {
        detachDocumentListeners();
        const embeddedDocument = getEmbeddedContentDocument(host);
        if (!embeddedDocument) {
          detachDocumentListeners = () => undefined;
          return;
        }
        embeddedDocument.addEventListener('focusin', activate, true);
        embeddedDocument.addEventListener('pointerdown', activate, true);
        detachDocumentListeners = () => {
          embeddedDocument.removeEventListener('focusin', activate, true);
          embeddedDocument.removeEventListener('pointerdown', activate, true);
        };
      };

      host.addEventListener('load', attachDocumentListeners);
      attachDocumentListeners();
      return () => {
        host.removeEventListener('load', attachDocumentListeners);
        detachDocumentListeners();
      };
    };

    const collectEmbeddedHosts = (node: Node) => {
      const hosts: Element[] = [];
      if (node.nodeType === 1) {
        const element = node as Element;
        if (element.matches(EMBEDDED_CONTENT_SELECTOR)) {
          hosts.push(element);
        }
        hosts.push(...element.querySelectorAll(EMBEDDED_CONTENT_SELECTOR));
      }
      return hosts;
    };
    const addEmbeddedHosts = (node: Node) => {
      collectEmbeddedHosts(node).forEach((host) => {
        if (!hostCleanups.has(host)) {
          hostCleanups.set(host, attachEmbeddedHost(host));
        }
      });
    };
    const removeEmbeddedHosts = (node: Node) => {
      if (node.nodeType !== 1) {
        return;
      }
      const removedRoot = node as Element;
      hostCleanups.forEach((cleanup, host) => {
        if (host === removedRoot || removedRoot.contains(host)) {
          cleanup();
          hostCleanups.delete(host);
        }
      });
    };

    const checkEmbeddedActiveElement = () => {
      focusCheckTimer = undefined;
      const activeElement = ownerDocument.activeElement;
      if (
        activeElement &&
        container.contains(activeElement) &&
        activeElement.matches(EMBEDDED_CONTENT_SELECTOR)
      ) {
        activate();
      }
    };
    const scheduleEmbeddedActiveElementCheck = () => {
      if (focusCheckTimer !== undefined) {
        ownerWindow.clearTimeout(focusCheckTimer);
      }
      focusCheckTimer = ownerWindow.setTimeout(checkEmbeddedActiveElement, 0);
    };

    addEmbeddedHosts(container);
    const observer = new ownerWindow.MutationObserver((records) => {
      records.forEach((record) => {
        record.removedNodes.forEach(removeEmbeddedHosts);
        record.addedNodes.forEach(addEmbeddedHosts);
      });
    });
    observer.observe(container, { childList: true, subtree: true });
    ownerWindow.addEventListener('blur', scheduleEmbeddedActiveElementCheck);
    ownerWindow.addEventListener('focus', scheduleEmbeddedActiveElementCheck);
    ownerDocument.addEventListener('visibilitychange', scheduleEmbeddedActiveElementCheck);

    return () => {
      observer.disconnect();
      ownerWindow.removeEventListener('blur', scheduleEmbeddedActiveElementCheck);
      ownerWindow.removeEventListener('focus', scheduleEmbeddedActiveElementCheck);
      ownerDocument.removeEventListener('visibilitychange', scheduleEmbeddedActiveElementCheck);
      if (focusCheckTimer !== undefined) {
        ownerWindow.clearTimeout(focusCheckTimer);
      }
      hostCleanups.forEach((cleanup) => cleanup());
      hostCleanups.clear();
    };
  }, [activate, isVisible]);

  return <div {...props} ref={containerRef} onFocusCapture={activate} onPointerDownCapture={activate} />;
}
