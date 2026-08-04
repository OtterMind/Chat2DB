import { getLocalTextFileTabPresentation } from './localTextFile';

function assertEqual(actual: unknown, expected: unknown, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

assertEqual(
  getLocalTextFileTabPresentation('/Users/example/Desktop/test.sql', 'fallback.sql'),
  {
    label: 'test.sql',
    popover: '/Users/example/Desktop/test.sql',
  },
  'uses a POSIX file name as the label and preserves the path for hover',
);
assertEqual(
  getLocalTextFileTabPresentation('C:\\Users\\example\\Desktop\\test.sql', 'fallback.sql'),
  {
    label: 'test.sql',
    popover: 'C:\\Users\\example\\Desktop\\test.sql',
  },
  'uses a Windows file name as the label and preserves the path for hover',
);
assertEqual(
  getLocalTextFileTabPresentation('\\\\server\\share\\queries\\report.sql', 'fallback.sql'),
  {
    label: 'report.sql',
    popover: '\\\\server\\share\\queries\\report.sql',
  },
  'uses a UNC file name as the label and preserves the path for hover',
);
assertEqual(
  getLocalTextFileTabPresentation('release.sql', 'fallback.sql'),
  { label: 'release.sql', popover: 'release.sql' },
  'preserves a bare file name and exposes it on hover',
);
assertEqual(
  getLocalTextFileTabPresentation(undefined, 'saved-title.sql'),
  { label: 'saved-title.sql', popover: undefined },
  'falls back to the persisted title when the path is missing',
);

console.log('local text file tests passed');
