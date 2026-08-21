import assert from 'node:assert/strict';
import { DataSourceIdentityColorRequestRegistry } from './dataSourceIdentityColorRequest';

const registry = new DataSourceIdentityColorRequestRegistry();
const firstSourceRequest = registry.begin(1);
const otherSourceRequest = registry.begin(2);

assert.equal(registry.isLatest(firstSourceRequest), true, 'requests for another data source stay independent');
assert.equal(registry.isLatest(otherSourceRequest), true);

const secondSourceRequest = registry.begin(1);
assert.equal(registry.isLatest(firstSourceRequest), false, 'a newer request supersedes the same data source');
assert.equal(registry.isLatest(secondSourceRequest), true);
assert.equal(registry.isLatest(otherSourceRequest), true);
assert.equal(registry.isLatest(undefined), false);

let committedColor = '#000000';
if (registry.isLatest(firstSourceRequest)) {
  committedColor = '#111111';
}
if (registry.isLatest(secondSourceRequest)) {
  committedColor = '#222222';
}
assert.equal(committedColor, '#222222', 'a late success cannot overwrite the latest optimistic color');

let rollbackCount = 0;
if (registry.isLatest(firstSourceRequest)) {
  rollbackCount += 1;
}
assert.equal(rollbackCount, 0, 'a late failure cannot roll back a newer request');

function createGate() {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

async function flushScheduledRequests() {
  await Promise.resolve();
  await Promise.resolve();
}

async function testConfirmedColorRollback() {
  const dataSourceId = 11;
  const initialColor = '#101010';

  const runScenario = async (requests: Array<{ optimistic: string; request: () => Promise<string> }>) => {
    const scenarioRegistry = new DataSourceIdentityColorRequestRegistry();
    let displayedColor = initialColor;
    const issueRequest = ({ optimistic, request }: (typeof requests)[number]) => {
      const token = scenarioRegistry.begin(dataSourceId, displayedColor);
      displayedColor = optimistic;
      return scenarioRegistry
        .enqueue(dataSourceId, request)
        .then((confirmedColor) => {
          scenarioRegistry.confirm(dataSourceId, confirmedColor);
          if (scenarioRegistry.isLatest(token)) {
            displayedColor = confirmedColor;
          }
          return confirmedColor;
        })
        .catch((error) => {
          if (scenarioRegistry.isLatest(token)) {
            displayedColor = scenarioRegistry.getConfirmedColor(dataSourceId)!;
          }
          return Promise.reject(error);
        });
    };

    const results = await Promise.allSettled(requests.map(issueRequest));
    return { displayedColor, results, confirmedColor: scenarioRegistry.getConfirmedColor(dataSourceId) };
  };

  const doubleFailure = await runScenario([
    { optimistic: '#AAAAAA', request: async () => Promise.reject(new Error('A failed')) },
    { optimistic: '#BBBBBB', request: async () => Promise.reject(new Error('B failed')) },
  ]);
  assert.equal(doubleFailure.displayedColor, initialColor, 'A/B failures roll back to the initial confirmed color');
  assert.equal(doubleFailure.confirmedColor, initialColor);
  assert.deepEqual(
    doubleFailure.results.map(({ status }) => status),
    ['rejected', 'rejected'],
  );

  const normalizedAColor = '#A1B2C3';
  const successThenFailure = await runScenario([
    { optimistic: '#a1b2c3', request: async () => normalizedAColor },
    { optimistic: '#BBBBBB', request: async () => Promise.reject(new Error('B failed')) },
  ]);
  assert.equal(
    successThenFailure.displayedColor,
    normalizedAColor,
    'B failure rolls back to the normalized color confirmed by A',
  );
  assert.equal(successThenFailure.confirmedColor, normalizedAColor);
}

async function testRequestQueue() {
  const queueRegistry = new DataSourceIdentityColorRequestRegistry();
  const firstRequestGate = createGate();
  const startedRequests: string[] = [];

  const firstRequest = queueRegistry.enqueue(1, async () => {
    startedRequests.push('source-1:first');
    await firstRequestGate.promise;
    return 'first';
  });
  const secondRequest = queueRegistry.enqueue(1, async () => {
    startedRequests.push('source-1:second');
    return 'second';
  });
  const independentRequest = queueRegistry.enqueue(2, async () => {
    startedRequests.push('source-2:first');
    return 'independent';
  });

  await flushScheduledRequests();
  assert.deepEqual(
    startedRequests,
    ['source-1:first', 'source-2:first'],
    'the next request waits for the same data source while another data source stays independent',
  );

  firstRequestGate.release();
  assert.equal(await firstRequest, 'first');
  await flushScheduledRequests();
  assert.deepEqual(startedRequests, ['source-1:first', 'source-2:first', 'source-1:second']);
  assert.deepEqual(await Promise.all([secondRequest, independentRequest]), ['second', 'independent']);

  const failureOrder: string[] = [];
  const failedRequest = queueRegistry.enqueue(3, async () => {
    failureOrder.push('failed:start');
    throw new Error('first request failed');
  });
  const requestAfterFailure = queueRegistry.enqueue(3, async () => {
    failureOrder.push('next:start');
    return 'continued';
  });
  await assert.rejects(failedRequest, /first request failed/);
  assert.equal(await requestAfterFailure, 'continued');
  assert.deepEqual(failureOrder, ['failed:start', 'next:start'], 'a failed request does not block the queue');
}

testRequestQueue()
  .then(testConfirmedColorRollback)
  .then(() => {
    console.log('Data source identity color request tests passed');
  })
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
