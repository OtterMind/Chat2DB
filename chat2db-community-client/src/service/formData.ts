export type FormDataParameters = Record<string, unknown>;

export function createRequestFormData(params: FormDataParameters): FormData {
  const formData = new FormData();

  if (Object.prototype.hasOwnProperty.call(params, 'file')) {
    formData.append('file', params.file as Blob);
  }

  Object.keys(params).forEach((key) => {
    if (key !== 'file') {
      formData.append(key, params[key] as string | Blob);
    }
  });

  return formData;
}
