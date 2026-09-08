import { memo } from 'react';
import RunSqlModal from './components/RunSqlModal';
import ImportFileModal from './components/ImportFileModal';

export default memo(() => {
  return (
    <>
      <RunSqlModal />
      <ImportFileModal />
    </>
  );
});
