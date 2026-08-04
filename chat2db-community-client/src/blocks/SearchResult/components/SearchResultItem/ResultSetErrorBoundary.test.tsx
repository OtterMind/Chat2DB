import assert from 'node:assert/strict';
import { useEffect } from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import ResultSetErrorBoundary from './ResultSetErrorBoundary';

const originalConsoleError = console.error;
console.error = () => undefined;

let retry: (() => void) | null = null;
let throwOnMount = true;

const FailingResult = ({ cleanupKey = 0 }: { cleanupKey?: number }) => {
  useEffect(() => {
    if (throwOnMount) {
      throw new Error('result effect failed');
    }
    return () => {
      if (cleanupKey === 1) {
        throw new Error('result cleanup failed');
      }
    };
  }, [cleanupKey]);
  return <div data-testid="result">result</div>;
};

const renderTree = (resetKey: string, cleanupKey = 0) => (
  <div data-testid="workspace">
    <div data-testid="workspace-shell">workspace shell</div>
    <ResultSetErrorBoundary
      resetKey={resetKey}
      fallback={(error, retryResult) => {
        retry = retryResult;
        return <div data-testid="result-fallback">{error.message}</div>;
      }}
    >
      <FailingResult cleanupKey={cleanupKey} />
    </ResultSetErrorBoundary>
  </div>
);

try {
  let renderer: TestRenderer.ReactTestRenderer;
  act(() => {
    renderer = TestRenderer.create(renderTree('result-1'));
  });
  assert.equal(renderer!.root.findByProps({ 'data-testid': 'workspace-shell' }).children[0], 'workspace shell');
  assert.equal(renderer!.root.findByProps({ 'data-testid': 'result-fallback' }).children[0], 'result effect failed');

  throwOnMount = false;
  act(() => retry?.());
  assert.equal(renderer!.root.findByProps({ 'data-testid': 'result' }).children[0], 'result');

  act(() => {
    renderer!.update(renderTree('result-1', 1));
  });
  act(() => {
    renderer!.update(renderTree('result-1', 2));
  });
  assert.equal(
    renderer!.root.findByProps({ 'data-testid': 'result-fallback' }).children[0],
    'result cleanup failed',
  );
  assert.equal(renderer!.root.findByProps({ 'data-testid': 'workspace-shell' }).children[0], 'workspace shell');

  act(() => {
    renderer!.update(renderTree('result-2', 2));
  });
  assert.equal(renderer!.root.findByProps({ 'data-testid': 'result' }).children[0], 'result');

  act(() => renderer!.unmount());
} finally {
  console.error = originalConsoleError;
}

console.log('ResultSet error boundary tests passed');
