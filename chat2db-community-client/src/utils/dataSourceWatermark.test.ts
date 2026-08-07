import assert from 'node:assert/strict';
import {
  getDataSourceWatermarkContent,
  getDataSourceWatermarkLayout,
  LARGE_WATERMARK_MIN_HEIGHT,
  LARGE_WATERMARK_MIN_WIDTH,
} from './dataSourceWatermark';

assert.deepEqual(getDataSourceWatermarkLayout(), { itemCount: 1, columns: 1, rows: 1 });
assert.deepEqual(getDataSourceWatermarkLayout(480, 700), { itemCount: 1, columns: 1, rows: 1 });
assert.deepEqual(getDataSourceWatermarkLayout(700, 240), { itemCount: 2, columns: 2, rows: 1 });
assert.deepEqual(getDataSourceWatermarkLayout(LARGE_WATERMARK_MIN_WIDTH, LARGE_WATERMARK_MIN_HEIGHT), {
  itemCount: 4,
  columns: 2,
  rows: 2,
});
assert.deepEqual(getDataSourceWatermarkLayout(LARGE_WATERMARK_MIN_WIDTH - 1, LARGE_WATERMARK_MIN_HEIGHT), {
  itemCount: 2,
  columns: 2,
  rows: 1,
});

assert.deepEqual(
  getDataSourceWatermarkContent({
    dataSourceName: '订单生产库',
    watermarkEnabled: true,
    environment: {
      id: 1,
      name: 'Production',
      shortName: 'PROD',
      color: '#FF0000',
    },
    databaseName: 'orders',
    schemaName: 'public',
  }),
  {
    title: 'PROD',
    subtitle: '订单生产库 / orders / public',
  },
);

assert.deepEqual(
  getDataSourceWatermarkContent({
    dataSourceName: '本地 MySQL',
    databaseName: 'app',
    watermarkEnabled: true,
  }),
  {
    title: '本地 MySQL',
    subtitle: 'app',
  },
);

assert.deepEqual(
  getDataSourceWatermarkContent({
    dataSourceName: 'Warehouse',
    watermarkEnabled: true,
    environment: {
      id: 2,
      name: 'Staging',
      shortName: '   ',
      color: '#00AAFF',
    },
  }),
  {
    title: 'Staging',
    subtitle: 'Warehouse',
  },
);

assert.equal(getDataSourceWatermarkContent({}), undefined);

assert.equal(
  getDataSourceWatermarkContent({
    dataSourceName: 'legacy-source',
    watermarkContent: 'DO NOT DISPLAY',
  }),
  undefined,
);

assert.equal(
  getDataSourceWatermarkContent({
    dataSourceName: 'nullable-source',
    watermarkEnabled: null,
    watermarkContent: 'DO NOT DISPLAY',
  }),
  undefined,
);

assert.equal(
  getDataSourceWatermarkContent({
    dataSourceName: 'orders-primary',
    watermarkEnabled: false,
    watermarkContent: 'DO NOT DISPLAY',
  }),
  undefined,
);

assert.deepEqual(
  getDataSourceWatermarkContent({
    dataSourceName: 'orders-primary',
    databaseName: 'orders',
    watermarkEnabled: true,
    watermarkContent: '  Finance Read Only  ',
  }),
  {
    title: 'Finance Read Only',
  },
);

assert.deepEqual(
  getDataSourceWatermarkContent({
    dataSourceName: 'orders-primary',
    databaseName: 'orders',
    watermarkEnabled: true,
    watermarkContent: '   ',
  }),
  {
    title: 'orders-primary',
    subtitle: 'orders',
  },
);

assert.deepEqual(
  getDataSourceWatermarkContent(
    {
      dataSourceName: 'orders-primary',
      databaseName: 'orders',
      watermarkEnabled: true,
    },
    'unavailable',
  ),
  {
    title: 'orders-primary',
    subtitle: 'orders',
    connectionUnavailable: true,
  },
);

assert.deepEqual(
  getDataSourceWatermarkContent(
    {
      dataSourceName: 'deleted-source',
      databaseName: 'archive',
      watermarkEnabled: true,
    },
    'deleted',
  ),
  {
    title: 'deleted-source',
    subtitle: 'archive',
  },
  'deleted data sources retain cached identity text without being mislabeled as temporarily unavailable',
);

console.log('Data source watermark tests passed');
