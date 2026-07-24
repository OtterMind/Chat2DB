// menuKey of right-click menu
export enum ContextmenuType {
  // View/modify data
  viewUpdateData = 'viewUpdateData',
  // View single line details
  viewRowDetail = 'viewRowDetail',
  // View the complete large field
  viewFullValue = 'viewFullValue',
  // Copy large field preview
  copyPreview = 'copyPreview',
  // Save large fields to file
  saveToFile = 'saveToFile',
  // copy
  copy = 'copy',
  // Copy column name
  copyFieldName = 'copyFieldName',
  // paste
  paste = 'paste',
  // clone line
  cloneRow = 'cloneRow',
  // Delete row
  deleteRow = 'deleteRow',
  // is set to null
  setNull = 'setNull',
  // copy behavior
  copyRow = 'copyRow',
  // Copy behavior insert statement
  copyRowInsert = 'copyRowInsert',
  // Copy behavior update statement
  copyRowUpdate = 'copyRowUpdate',
  // Copy behavior where statement
  copyRowWhere = 'copyRowWhere',
  // Copy as SQL IN list of values
  copyAsSqlInValues = 'copyAsSqlInValues',
  // tab separated value
  tabSplit = 'tabSplit',
  // tab-delimited field
  tabSplitField = 'tabSplitField',
  // Tab-separated value field
  tabSplitFieldAndValue = 'tabSplitFieldAndValue',
  // Markdown table
  markdownTable = 'markdownTable',
}
