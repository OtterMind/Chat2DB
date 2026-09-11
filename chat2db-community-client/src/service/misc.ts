import { BucketTypeEnum, UploadTypeEnum } from '@/typings/upload';
import createRequest from './base';

const testService = createRequest<null, boolean>('/api/system', { errorLevel: false });
const systemStop = createRequest<void, void>('/api/system/stop', { errorLevel: false, method: 'post' });
const testApiSmooth = createRequest<void, void>('/api/system/get-version-a', { errorLevel: false, method: 'get' });
const uploadFile = createRequest<any, string>('/api/file/upload', { method: 'post' });

/** Upload CSS */
const getOSSCertificate = createRequest<
  {
    bucketType: BucketTypeEnum;
    uploadType: UploadTypeEnum;
  },
  {
    securityToken: string;
    accessKeySecret: string;
    accessKeyId: string;
    endpoint: string;
    expiration: string;
    requestId: string;
    bucket: string;
    cdn: string;
    fileFolder: string;
  }
>('/api/file/sts');

/**
 * Filter sql
 */
const characterHandler = createRequest<
  {
    text: string;
  },
  string
>('/api/character/handler', { method: 'post', errorLevel: false });

export default {
  testService,
  systemStop,
  testApiSmooth,
  uploadFile,
  getOSSCertificate,
  characterHandler,
};
