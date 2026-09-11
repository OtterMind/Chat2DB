import { useEffect, useState } from 'react';
import { Divider, Input, Select } from 'antd';

export interface SingleCharacterSelectProps {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  className: string;
  customInputClassName: string;
  customOptionLabel: (value: string) => string;
  customInputLabel: string;
  allowCustom?: boolean;
  disabled?: boolean;
  onChange: (value: string) => void;
}

export const buildSingleCharacterOptions = (
  value: string,
  options: SingleCharacterSelectProps['options'],
  allowCustom: boolean,
  customOptionLabel: SingleCharacterSelectProps['customOptionLabel'],
) =>
  options.some((option) => option.value === value) || !allowCustom
    ? options
    : [...options, { value, label: customOptionLabel(value) }];

const SingleCharacterSelect = ({
  label,
  value,
  options,
  className,
  customInputClassName,
  customOptionLabel,
  customInputLabel,
  allowCustom = true,
  disabled,
  onChange,
}: SingleCharacterSelectProps) => {
  const preset = options.some((option) => option.value === value);
  const [customValue, setCustomValue] = useState(preset ? '' : value);

  useEffect(() => {
    setCustomValue(preset ? '' : value);
  }, [preset, value]);

  const selectOptions = buildSingleCharacterOptions(value, options, allowCustom, customOptionLabel);

  return (
    <div className={className}>
      <span>{label}</span>
      <Select
        value={value}
        options={selectOptions}
        disabled={disabled}
        onChange={onChange}
        dropdownRender={
          allowCustom
            ? (menu) => (
                <>
                  {menu}
                  <Divider style={{ margin: '4px 0' }} />
                  <div className={customInputClassName} onMouseDown={(event) => event.stopPropagation()}>
                    <Input
                      aria-label={customInputLabel}
                      maxLength={1}
                      placeholder={customInputLabel}
                      value={customValue}
                      disabled={disabled}
                      onKeyDown={(event) => event.stopPropagation()}
                      onChange={(event) => {
                        const nextValue = event.target.value;
                        setCustomValue(nextValue);
                        if (nextValue) {
                          onChange(nextValue);
                        }
                      }}
                    />
                  </div>
                </>
              )
            : undefined
        }
      />
    </div>
  );
};

export default SingleCharacterSelect;
