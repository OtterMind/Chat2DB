export type ImportTransportSource = {
  file?: File;
};

export type ImportTaskTransport<T extends ImportTransportSource> =
  | { kind: 'path'; params: Omit<T, 'file'> }
  | { kind: 'upload'; params: { file: File; request: Blob } };

export function resolveImportTaskTransport<T extends ImportTransportSource>(
  params: T,
  desktopRuntime: boolean,
): ImportTaskTransport<T> {
  const { file, ...request } = params;
  if (!desktopRuntime && file) {
    return {
      kind: 'upload',
      params: {
        file,
        request: new Blob([JSON.stringify(request)], { type: 'application/json' }),
      },
    };
  }
  return { kind: 'path', params: request };
}
