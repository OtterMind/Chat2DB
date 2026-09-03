const { existsSync } = require('node:fs');
const { spawnSync } = require('node:child_process');

const testFiles = [
  'src/store/dashboard/slices/common/dashboardDetailRequest.test.ts',
  'src/store/dashboard/slices/common/dashboardMutation.test.ts',
  'src/store/dashboard/slices/common/refreshCurrentDashboard.test.ts',
  'src/blocks/BI/ChartCardBox/DingChartModal/pinChartToDashboard.test.ts',
];
const tsxCli = require.resolve('tsx/cli');
let executed = 0;

for (const testFile of testFiles) {
  if (!existsSync(testFile)) continue;
  executed += 1;
  const result = spawnSync(process.execPath, [tsxCli, testFile], { stdio: 'inherit' });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

if (!executed) {
  console.error('No dashboard store tests were found.');
  process.exit(1);
}
