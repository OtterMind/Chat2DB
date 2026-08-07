import { OperationColumn, TreeNodeType } from '@/constants/tree';

export {
  DATA_SOURCE_COLOR_CONTROL_COUNT,
  DATA_SOURCE_COLOR_PRESETS,
  getDataSourceColorSelectionIndex,
  getNextDataSourceColorControlIndex,
  resolveDataSourceColorSelection,
} from '@/components/DataSourceColorPicker/model';
export type {
  DataSourceColorNavigationKey,
  DataSourceColorSelection,
} from '@/components/DataSourceColorPicker/model';

export function withDataSourceColorMenuOption(
  menuOptions: readonly OperationColumn[],
  treeNodeType: TreeNodeType,
): OperationColumn[] {
  if (treeNodeType !== TreeNodeType.DATA_SOURCE) {
    return menuOptions as OperationColumn[];
  }

  const optionsWithoutLegacyColorItems = menuOptions.filter(
    (option) => option !== OperationColumn.SetDataSourceColor && option !== OperationColumn.ClearDataSourceColor,
  );
  const editSourceIndex = optionsWithoutLegacyColorItems.indexOf(OperationColumn.EditSource);
  const insertIndex = editSourceIndex === -1 ? 0 : editSourceIndex + 1;
  const followingOptions = optionsWithoutLegacyColorItems.slice(insertIndex);
  const needsDivider = followingOptions.length > 0 && followingOptions[0] !== OperationColumn.Divider;
  return [
    ...optionsWithoutLegacyColorItems.slice(0, insertIndex),
    OperationColumn.SetDataSourceColor,
    ...(needsDivider ? [OperationColumn.Divider] : []),
    ...followingOptions,
  ];
}
