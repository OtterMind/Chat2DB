import { USER_FILLED_VALUE, DATE_TIME } from './components/TableBox/index';
import dayjs from 'dayjs';

// 在input中把USER_FILLED_VALUE转换为null
export const transformInputValue = (value: string) => {
  if (value === USER_FILLED_VALUE.DEFAULT) {
    return null;
  }
  return value;
};

export const transformDateTimeValue = (value: string) => {
  return dayjs(value, DATE_TIME.DATEFORMAT);
};
