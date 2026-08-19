import DataSourceColorPicker from '@/components/DataSourceColorPicker';

interface DataSourceColorMenuItemProps {
  identityColor?: string | null;
  onSelect: (identityColor: string | null) => void;
  onPickerOpenChange: (open: boolean) => void;
  onEscape: () => void;
  registerFocusTarget: (focus: () => void) => () => void;
}

const DataSourceColorMenuItem = (props: DataSourceColorMenuItemProps) => {
  const { identityColor, onSelect, onPickerOpenChange, onEscape, registerFocusTarget } = props;
  return (
    <DataSourceColorPicker
      value={identityColor}
      onChange={onSelect}
      placement="rightTop"
      stopPropagation
      onPickerOpenChange={onPickerOpenChange}
      onEscape={onEscape}
      registerFocusTarget={registerFocusTarget}
    />
  );
};

export default DataSourceColorMenuItem;
