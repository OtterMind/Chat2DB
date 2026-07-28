import { BucketTypeEnum, UploadTypeEnum, uploadTypeObject } from '@/typings/upload';
import OSS from 'ali-oss';
import miscService from '@/service/misc';
import { v4 as uuid } from 'uuid';
import { useUserStore } from '@/store/user';
import html2canvas from 'html2canvas';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import sqlService from '@/service/sql';
import { LOCAL_SQL_FILE_SAVED_EVENT } from '@/constants';

export type LargeCellDownloadFormat = 'raw' | 'text' | 'hex';

/**
 /**
 *File download
 * @param url
 * @param params
 */
export function downloadFile(url: string, params: any) {
  // Create POST request
  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json', // Or set other content types according to the requirements of the server
    },
    body: JSON.stringify(params), // Convert parameters to JSON string
  })
    .then((response) => {
      // Get filename from content-disposition header
      const contentDisposition = response.headers.get('content-disposition');
      const filename = contentDisposition ? decodeURIComponent(contentDisposition.split("''")[1]) : 'file.text';

      // Get the returned Blob data
      return response.blob().then((blob) => ({ blob, filename }));
    })
    .then(({ blob, filename }) => {
      // Create a URL that represents a Blob object
      const blobUrl = URL.createObjectURL(blob);

      // Create a hidden <a> tag and set its href attribute
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = blobUrl;

      // Use filename parsed from response headers
      a.download = filename;

      // Append <a> tag to DOM and trigger click event
      document.body.appendChild(a);
      a.click();

      // Cleanup: Remove the <a> tag from the DOM and release the blob URL
      document.body.removeChild(a);
      URL.revokeObjectURL(blobUrl);
    })
    .catch((error) => {
      console.error('Failed to download file:', error);
    });
}

export async function downloadLargeCellValue(largeValueId: string, format: LargeCellDownloadFormat = 'raw') {
  if (!isDesktop) {
    return;
  }
  const filePath = await sqlService.downloadLargeCellValue({ largeValueId, format });
  if (filePath) {
    jcefApi?.revealInExplorer(filePath);
  }
}

// Update file content
export function updateFileContent({ filePath, fileContent }: { filePath: string; fileContent: string }) {
  jcefApi?.updateFileContent({ filePath, fileContent });
}

interface SavedDesktopFile {
  path: string;
  size: number;
}

// save file
export function saveFileToDesktop({
  fileName,
  fileContent,
  fileType,
}: {
  fileName: string;
  fileContent: string;
  fileType: string;
}) {
  if (isDesktop) {
    return jcefApi?.saveFile({ fileName, fileContent, fileType }).then((result: SavedDesktopFile | null) => {
      if (result?.path && result.path.toLowerCase().endsWith('.sql')) {
        window.dispatchEvent(new CustomEvent(LOCAL_SQL_FILE_SAVED_EVENT, { detail: { filePath: result.path } }));
      }
      return result;
    });
  }

  const blob = new Blob([fileContent], { type: 'text/plain' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${fileName || 'chat2db-sql'}.${fileType}`;
  a.click();
  URL.revokeObjectURL(url);
  return Promise.resolve({ path: a.download, size: blob.size });
}

export const customRequestOSS = async ({
  file,
  onSuccess,
  onError,
  uploadType,
}: {
  file: any;
  onSuccess?: any;
  onError?: any;
  uploadType: UploadTypeEnum;
}) => {
  const queryOSSCertificate = async () => {
    const params = uploadTypeObject[uploadType];
    const result = await miscService.getOSSCertificate(params);

    return result;
  };

  const signature = await queryOSSCertificate();
  if (!signature) {
    onError?.(new Error('Get signature error!'));
  }

  const client = new OSS({
    region: signature.endpoint.split('.')[0],
    accessKeyId: signature.accessKeyId,
    accessKeySecret: signature.accessKeySecret,
    stsToken: signature.securityToken,
    bucket: signature.bucket,
    secure: true,
  });

  const curUserId = useUserStore.getState().curUser?.id;

  try {
    const fileName = `${uuid()}_${curUserId}${file.name.substring(file.name.lastIndexOf('.'))}`;
    const result = await client.put(`${signature.fileFolder}${fileName}`, file);

    let privateUrl = '';
    if (uploadTypeObject[uploadType]?.bucketType === BucketTypeEnum.PRIVATE) {
      // Sign the URL address for the private bucket for one month
      privateUrl = client.signatureUrl(result.name, { expires: 60 * 60 * 24 * 30 }) || '';
    }
    onSuccess?.({ ...result, cdn: signature.cdn, privateUrl }, file);
    console.log('Upload success:', result);
  } catch (err) {
    console.error('Upload error:', err);
    onError?.(err);
  }
};

// Format file size
export function formatFileSize(size: number) {
  // KB MB GB
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(2)} KB`;
  }
  if (size < 1024 * 1024 * 1024) {
    return `${(size / 1024 / 1024).toFixed(2)} MB`;
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

// Draw grid lines
export function createGridPattern(width, height, paddingBottom, paddingRight, color) {
  const roundWidth = Math.round(width);
  // Create a canvas element
  const canvas = document.createElement('canvas');
  canvas.width = roundWidth + paddingRight;
  canvas.height = height + paddingBottom;

  // Get the 2D context and set willReadFrequently to true
  const ctx: any = canvas.getContext('2d', { willReadFrequently: true });

  // Draw grid lines
  ctx.strokeStyle = color; // Set the color of grid lines
  ctx.setLineDash([6, 5]); // Set dash mode, 6 pixels line, 5 pixels blank
  ctx.strokeRect(0.5, 0.5, roundWidth - 1, height - 1);

  // Convert canvas to data URL and back
  return canvas.toDataURL();
}

// Export the contents of a ref to an image
export const onExportToImage = (chartRef, name, options?) => {
  const element = chartRef.current;
  if (element) {
    html2canvas(element, options).then((canvas) => {
      const image = canvas.toDataURL('image/png');
      const a = document.createElement('a');
      const event = new MouseEvent('click');
      a.download = `${name}.png`;
      a.href = image;
      a.dispatchEvent(event);
    });
  }
};
