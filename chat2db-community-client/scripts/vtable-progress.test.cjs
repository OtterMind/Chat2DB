const assert = require('node:assert/strict');
const { SceneProxy } = require('@visactor/vtable/cjs/scenegraph/group-creater/progress/proxy');

async function run() {
  const originalSetTimeout = global.setTimeout;
  const scheduled = [];
  global.setTimeout = (callback) => { scheduled.push(callback); return 0; };
  try {
    const table = {
      isPivotChart: () => false,
      isAutoRowHeight: () => false,
      isPivotTable: () => false,
      widthMode: 'autoWidth',
      defaultRowHeight: 28,
      defaultColWidth: 100,
      options: {},
      internalProps: { stick: { changedCells: new Map() } },
      frozenRowCount: 0,
      frozenColCount: 0,
      rightFrozenColCount: 0,
      bottomFrozenRowCount: 0,
      rowCount: 0,
      colCount: 0,
      getFrozenRowsHeight: () => 0,
      getFrozenColsWidth: () => 0,
      getAllRowsHeight: () => 0,
      getAllColsWidth: () => 0,
      getRowAt: () => ({ row: 0 }),
      getColAt: () => ({ col: 0 }),
      scenegraph: { updateNextFrame() {} },
    };
    const proxy = new SceneProxy(table);
    Object.assign(proxy, {
      currentCol: 14, totalCol: 45, colUpdatePos: 46, colEnd: 14,
      currentRow: 29, totalRow: 199, rowUpdatePos: 200, rowEnd: 29,
    });

    for (const [width, height] of [[0, 0], [-10, -10], [1000, 0], [0, 480]]) {
      table.tableNoFrameWidth = width;
      table.tableNoFrameHeight = height;
      proxy.resize();
      const completion = proxy.progress();
      assert.equal(scheduled.length, 0, `zero-size work must stop: ${width} x ${height}`);
      await completion;
      assert.equal(proxy.currentCol, 14);
    }

    table.tableNoFrameWidth = 1000;
    table.tableNoFrameHeight = 480;
    proxy.resize();
    const completion = proxy.progress();
    assert.equal(scheduled.length, 1, 'a restored table must resume progressive rendering');
    table.tableNoFrameWidth = 0;
    table.tableNoFrameHeight = 0;
    proxy.resize();
    await scheduled.shift()();
    await completion;
    assert.equal(scheduled.length, 0, 'hiding during a queued frame must settle the Promise chain');
    assert.equal(proxy.isProgressing, false);
    assert.equal(proxy.currentCol, 14);
  } finally {
    global.setTimeout = originalSetTimeout;
  }
  console.log('VTable zero-size progress, restore, and in-flight hide tests passed');
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
