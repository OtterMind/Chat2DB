export function getTabWheelScrollAmount(deltaX: number, deltaY: number): number | null {
  if (!deltaY || Math.abs(deltaX) >= Math.abs(deltaY)) {
    return null;
  }

  const deltaMagnitude = Math.abs(deltaY);
  if (deltaMagnitude < 10) {
    return deltaY;
  }
  if (deltaMagnitude < 30) {
    return deltaY * 0.5;
  }
  return deltaY * 0.2;
}
