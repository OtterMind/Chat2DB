import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  pretendToBeVisual: true,
  url: 'http://localhost',
});
export const domWindow = dom.window;

Object.defineProperties(globalThis, {
  window: { configurable: true, value: domWindow },
  document: { configurable: true, value: domWindow.document },
  navigator: { configurable: true, value: domWindow.navigator },
  Node: { configurable: true, value: domWindow.Node },
  Element: { configurable: true, value: domWindow.Element },
  HTMLElement: { configurable: true, value: domWindow.HTMLElement },
  HTMLInputElement: { configurable: true, value: domWindow.HTMLInputElement },
  HTMLTextAreaElement: { configurable: true, value: domWindow.HTMLTextAreaElement },
  SVGElement: { configurable: true, value: domWindow.SVGElement },
  ShadowRoot: { configurable: true, value: domWindow.ShadowRoot },
  MutationObserver: { configurable: true, value: domWindow.MutationObserver },
  getComputedStyle: { configurable: true, value: domWindow.getComputedStyle.bind(domWindow) },
  requestAnimationFrame: { configurable: true, value: domWindow.requestAnimationFrame.bind(domWindow) },
  cancelAnimationFrame: { configurable: true, value: domWindow.cancelAnimationFrame.bind(domWindow) },
  PointerEvent: { configurable: true, value: domWindow.MouseEvent },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});

domWindow.matchMedia = () =>
  ({
    matches: false,
    media: '',
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }) as MediaQueryList;
Object.defineProperty(globalThis, 'matchMedia', {
  configurable: true,
  value: domWindow.matchMedia.bind(domWindow),
});

class TestResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: TestResizeObserver });
Object.defineProperty(domWindow, 'ResizeObserver', { configurable: true, value: TestResizeObserver });
Object.defineProperty(domWindow.HTMLElement.prototype, 'scrollIntoView', {
  configurable: true,
  value: () => undefined,
});
