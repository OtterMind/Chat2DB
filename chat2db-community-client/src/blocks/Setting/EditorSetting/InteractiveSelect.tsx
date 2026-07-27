import { useState, useRef } from 'react';
import { Select } from 'antd';
import { ChevronDown } from 'lucide-react';

const InteractiveSelect = ({ onChange, options, ...props }) => {
  const [open, setOpen] = useState(false);
  const isSelectingRef = useRef(false);

  const handleSelect = (selectedValue) => {
    onChange(selectedValue);
    isSelectingRef.current = true;
    setOpen(true);
  };

  const handleBlur = () => {
    if (!isSelectingRef.current) {
      setOpen(false);
    }
  };

  const handleDropdownVisibleChange = (visible) => {
    if (!visible && !isSelectingRef.current) {
      setOpen(false);
    }
    if (visible) {
      setOpen(true);
    }
    isSelectingRef.current = false;
  };

  const handleKeyDown = (event) => {
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault();
      const currentIndex = options.findIndex((option) => option.value === props.value);
      let newIndex;
      if (event.key === 'ArrowUp') {
        newIndex = (currentIndex - 1 + options.length) % options.length;
      } else {
        newIndex = (currentIndex + 1) % options.length;
      }
      const newValue = options[newIndex].value;
      onChange(newValue);
    }
  };

  return (
    <Select
      {...props}
      open={open}
      onDropdownVisibleChange={handleDropdownVisibleChange}
      onSelect={handleSelect}
      onBlur={handleBlur}
      onKeyDown={handleKeyDown}
      options={options}
      suffixIcon={<ChevronDown size={14} />}
    />
  );
};

export default InteractiveSelect;
