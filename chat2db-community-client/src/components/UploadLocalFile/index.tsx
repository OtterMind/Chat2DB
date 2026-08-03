import React, { memo, useEffect, useState, forwardRef, ForwardedRef, useImperativeHandle } from 'react';
import { useStyles } from './style';
import { Upload, type UploadProps, GetProp } from 'antd';
import { IconfontSvg, IconButton, staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import { useUpdateEffect } from 'ahooks';
import { customRequestOSS } from '@/utils/file';
import { UploadTypeEnum } from '@/typings/upload';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';

type FileType = Parameters<GetProp<UploadProps, 'beforeUpload'>>[0];

export interface FileUrl {
  fileName?: string;
  filePath?: string;
  file?: File;
}

interface IProps extends UploadProps {
  className?: string;
  multiple?: boolean;
  fileUrlListChange?: (fileUrl: FileUrl[]) => void;
  description?: [string, string];
  descriptionSlot?: React.ReactNode;
  // Whether OSS upload is enabled on the web.
  webOssUpload?: boolean;
  fileSize?: number;
}

export interface UploadLocalFileRef {
  resetFileList: () => void;
}

const UploadLocalFile = forwardRef((props: IProps, ref: ForwardedRef<UploadLocalFileRef>) => {
  const {
    className,
    multiple,
    fileUrlListChange,
    accept,
    description = [],
    webOssUpload,
    descriptionSlot,
    fileSize,
    ...rest
  } = props;
  const { styles, cx } = useStyles();
  const [fileList, setFileList] = useState<FileUrl[]>([]);

  useUpdateEffect(() => {
    fileUrlListChange && fileUrlListChange(fileList);
  }, [fileList]);

  const deleteFile = (index: number) => {
    setFileList(fileList.filter((_, i) => i !== index));
  };

  const renderFileItem = (filePath: FileUrl, index: number) => {
    return (
      <div key={index} className={styles.fileItem}>
        <span>{filePath.fileName}</span>
        <div className={styles.deleteIconBox}>
          <IconButton
            className={styles.deleteIcon}
            code="icon-trash"
            size="xs"
            onClick={() => {
              deleteFile(index);
            }}
          />
        </div>
      </div>
    );
  };

  useEffect(() => {
    if (accept) {
      setFileList([]);
    }
  }, [accept]);

  useImperativeHandle(ref, () => ({
    resetFileList: () => {
      setFileList([]);
    },
  }));

  const isWebOssUpload = webOssUpload && !isDesktop;
  const isWebLocalUpload = !isDesktop && !isWebOssUpload;

  const fileUploadOnChange = ({ file }) => {
    if (isWebLocalUpload) {
      const selectedFile = file.originFileObj || file;
      const selection = {
        file: selectedFile,
        filePath: selectedFile?.path,
        fileName: file.name,
      };
      setFileList((current) => (multiple ? [...current, selection] : [selection]));
      return;
    }

    if (file.status === 'done') {
      const selection = {
        fileName: file.name,
        filePath: file.response.privateUrl || file.originFileObj?.path,
      };
      setFileList((current) => (multiple ? [...current, selection] : [selection]));
    }
  };

  const beforeUpload = (file: FileType) => {
    if (fileSize) {
      const isLtxM = file.size / 1024 / 1024 < fileSize;
      if (!isLtxM) {
        staticMessage.error(i18n('common.text.singleUploadFileSize', fileSize));
        return Upload.LIST_IGNORE;
      }
    }
    if (isWebLocalUpload) {
      return false;
    }
  };

  const handleUpdate = () => {
    const fileTypeList =
      accept?.split(',').map((type) => {
        return type.replace('.', '');
      }) || [];

    jcefApi.selectFile({ fileTypeList, fileSize, multiple }).then((data) => {
      if (data) {
        setFileList(data);
      }
    });
  };

  return (
    <div className={cx(className)}>
      {fileList.length ? (
        <div className={styles.uploadLocalFile}>
          <div className={styles.uploadLocalFileHeader}>
            <span>{i18n('common.text.selectedFile')}</span>
            {/* {multiple && (
              <Upload beforeUpload={beforeUpload} onChange={fileUploadOnChange} showUploadList={false} {...rest}>
                <IconButton className={styles.addIcon} code="icon-add" size="xs" />
              </Upload>
            )} */}
          </div>
          <div className={styles.uploadLocalFileBody}>
            {fileList.map((filePath, index) => {
              return renderFileItem(filePath, index);
            })}
          </div>
        </div>
      ) : null}
      <div className={cx({ [styles.hiddenUploadDraggerBox]: !!fileList.length })}>
        {!isDesktop ? (
          <Upload.Dragger
            onChange={fileUploadOnChange}
            beforeUpload={beforeUpload}
            showUploadList={false}
            accept={accept}
            multiple={multiple}
            customRequest={
              isWebOssUpload
                ? (e) => {
                    customRequestOSS({
                      ...e,
                      uploadType: UploadTypeEnum.FEEDBACK_IMG,
                    });
                  }
                : undefined
            }
            {...rest}
          >
            <div className={styles.uploadDragger}>
              <IconfontSvg className={styles.uploadDraggerIcon} size={36} code="icon-upload" />
              <div className={styles.description}>
                <p className={styles.description1}>{description[0] || i18n('workspace.importExport.clickOrDrag')}</p>
                <p className={styles.description2}>{description[1]}</p>
                {descriptionSlot}
              </div>
              {fileSize && (
                <div className={styles.limitFileSize}>
                  <span>{i18n('common.text.limitFileSize', fileSize)}</span>
                </div>
              )}
            </div>
          </Upload.Dragger>
        ) : (
          <div className={styles.uploadDragger} onClick={handleUpdate}>
            <IconfontSvg className={styles.uploadDraggerIcon} size={36} code="icon-upload" />
            <div className={styles.description}>
              <p className={styles.description1}>{description[0] || i18n('workspace.importExport.clickOrDrag')}</p>
              <p className={styles.description2}>{description[1]}</p>
              {descriptionSlot}
            </div>
            {fileSize && (
              <div className={styles.limitFileSize}>
                <span>{i18n('common.text.limitFileSize', fileSize)}</span>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
});

export default memo(UploadLocalFile);
