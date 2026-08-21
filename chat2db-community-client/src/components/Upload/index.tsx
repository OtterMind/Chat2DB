import { memo } from 'react';
import classnames from 'classnames';
import { Upload, type UploadProps } from 'antd';
import miscService from '@/service/misc';
import { InboxOutlined } from '@ant-design/icons';
import { i18n } from '@/i18n';
import { BucketTypeEnum, UploadTypeEnum } from '@/typings/upload';
import OSS from 'ali-oss';
import { v4 as uuid } from 'uuid';
import { useUserStore } from '@/store/session';
interface IProps extends UploadProps {
  dragger?: boolean;
  /**
   * Upload type used to distinguish OSS buckets.
   */
  uploadType?: UploadTypeEnum;
}

/**
 * Uploads files through OSS and handles desktop and web upload flows.
 */

const uploadTypeObject = {
  [UploadTypeEnum.FEEDBACK_IMG]: {
    bucketType: BucketTypeEnum.PRIVATE,
    uploadType: UploadTypeEnum.FEEDBACK_IMG,
  },
  [UploadTypeEnum.AVATOR]: {
    bucketType: BucketTypeEnum.PUBLIC,
    uploadType: UploadTypeEnum.AVATOR,
  },
};

export default memo(({ className, children, dragger, uploadType = UploadTypeEnum.FEEDBACK_IMG, ...rest }: IProps) => {
  const { curUser } = useUserStore((s) => {
    return {
      curUser: s.curUser,
    };
  });

  const queryOSSCertificate = async () => {
    const params = uploadTypeObject[uploadType];
    const result = await miscService.getOSSCertificate(params);

    return result;
  };

  const customRequestOSS = async ({ file, onSuccess, onError }: { file: any; onSuccess?: any; onError?: any }) => {
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

    try {
      const fileName = `${uuid()}_${curUser?.id}${file.name.substring(file.name.lastIndexOf('.'))}`;
      const result = await client.put(`${signature.fileFolder}${fileName}`, file);

      let privateUrl = '';
      if (uploadTypeObject[uploadType]?.bucketType === BucketTypeEnum.PRIVATE) {
        // Sign a one-month URL for a private bucket.
        privateUrl = client.signatureUrl(result.name, { expires: 60 * 60 * 24 * 30 }) || '';
      }
      onSuccess?.({ ...result, cdn: signature.cdn, privateUrl }, file);
      console.log('Upload success:', result);
    } catch (err) {
      console.error('Upload error:', err);
      onError?.(err);
    }
  };

  const draggerChildren = (
    <div>
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      <p className="ant-upload-text">{i18n('common.text.uploadDragFile')}</p>
    </div>
  );

  const FinalDragger = dragger ? Upload.Dragger : Upload;

  const FinalChildren = dragger ? children || draggerChildren : children;

  return (
    <div className={classnames(className)}>
      <FinalDragger customRequest={customRequestOSS} {...rest}>
        {FinalChildren}
      </FinalDragger>
    </div>
  );
});
