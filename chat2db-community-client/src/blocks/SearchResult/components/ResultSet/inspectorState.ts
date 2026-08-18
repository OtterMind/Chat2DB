export function areResultCellValuesEquivalent(currentValue: unknown, nextValue: string | null) {
  if (currentValue === null || currentValue === undefined || nextValue === null) {
    return (currentValue === null || currentValue === undefined) && nextValue === null;
  }
  return String(currentValue) === nextValue;
}
