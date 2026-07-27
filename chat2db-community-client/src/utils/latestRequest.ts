export interface RequestGenerationRef {
  current: number;
}

export const beginLatestRequest = (generationRef: RequestGenerationRef) => {
  generationRef.current += 1;
  return generationRef.current;
};

export const invalidateLatestRequest = (generationRef: RequestGenerationRef) => {
  generationRef.current += 1;
};

export const isLatestRequest = (generationRef: RequestGenerationRef, generation: number) => {
  return generationRef.current === generation;
};
