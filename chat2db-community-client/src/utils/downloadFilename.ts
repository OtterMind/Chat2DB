export function getDownloadFilename(contentDisposition: string | null) {
  if (!contentDisposition) {
    return 'download';
  }

  const encodedFilename = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encodedFilename) {
    return decodeURIComponent(encodedFilename);
  }

  return contentDisposition.match(/filename="?([^";]+)"?/i)?.[1] ?? 'download';
}
