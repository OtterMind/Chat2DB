import * as VTable from '@visactor/vtable';
import { copyResultData } from '../../copyResultData';

export const copyResultGridSelection = (tableInstance: VTable.ListTable) => copyResultData(tableInstance);

const onCopyData = (tableInstance: VTable.ListTable) => {
  void copyResultGridSelection(tableInstance);
};

export default onCopyData;
