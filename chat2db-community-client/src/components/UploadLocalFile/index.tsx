import React, { memo, useEffect, useRef, useState, forwardRef, ForwardedRef, useImperativeHandle } from 'react';
import { useStyles } from './style';
import { Button, Upload, type UploadProps, GetProp } from 'antd';
import { IconfontSvg, staticMessage } from '@chat2db/ui';
import { Plus, Trash2 } from 'lucide-react';
import i18n from '@/i18n';
import { useUpdateEffect } from 'ahooks';
import { customRequestOSS, formatFileSize } from '@/utils/file';
import { UploadTypeEnum } from '@/typings/upload';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import { exceedsSingleFileSizeLimit, mergeFileSelections, type FileSelectionLimitViolation } from './selectionLimits';

type FileType = Parameters<GetProp<UploadProps, 'beforeUpload'>>[0];

export interface FileUrl {
  fileName?: string;
  filePath?: string;
  file?: File;
  fileSize?: number;
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
  maxFiles?: number;
  maxTotalSizeBytes?: number;
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
    maxFiles,
    maxTotalSizeBytes,
    ...rest
  } = props;
  const { styles, cx } = useStyles();
  const [fileList, setFileList] = useState<FileUrl[]>([]);
  const fileListRef = useRef<FileUrl[]>([]);

  const commitFileList = (nextFileList: FileUrl[]) => {
    fileListRef.current = nextFileList;
    setFileList(nextFileList);
  };

  const showSelectionLimitErrors = (violations: FileSelectionLimitViolation[]) => {
    if (violations.includes('maxFiles')) {
      staticMessage.error(i18n('common.text.uploadFileCountLimit', maxFiles));
    }
    if (violations.includes('maxTotalSizeBytes')) {
      staticMessage.error(i18n('common.text.uploadTotalSizeLimit', formatFileSize(maxTotalSizeBytes!)));
    }
  };

  const addFileSelections = (selections: FileUrl[]) => {
    const result = mergeFileSelections(fileListRef.current, selections, {
      multiple,
      maxFiles,
      maxTotalSizeBytes,
    });
    showSelectionLimitErrors(result.violations);
    if (result.accepted.length) {
      commitFileList(result.fileList);
    }
  };

  useUpdateEffect(() => {
    fileUrlListChange && fileUrlListChange(fileList);
  }, [fileList]);

  const deleteFile = (index: number) => {
    commitFileList(fileListRef.current.filter((_, i) => i !== index));
  };

  const renderFileItem = (filePath: FileUrl, index: number) => {
    return (
      <div key={index} className={styles.fileItem}>
        <span className={styles.fileName} title={filePath.fileName}>
          {filePath.fileName}
        </span>
        <div className={styles.deleteIconBox}>
          <Button
            aria-label={`${i18n('common.button.delete')}: ${filePath.fileName || ''}`}
            className={styles.deleteIcon}
            title={i18n('common.button.delete')}
            type="text"
            size="small"
            icon={<Trash2 size={14} />}
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
      commitFileList([]);
    }
  }, [accept]);

  useImperativeHandle(ref, () => ({
    resetFileList: () => {
      commitFileList([]);
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
        fileSize: selectedFile?.size,
      };
      addFileSelections([selection]);
      return;
    }

    if (file.status === 'done') {
      const selection = {
        fileName: file.name,
        filePath: file.response.privateUrl || file.originFileObj?.path,
        fileSize: file.originFileObj?.size,
      };
      addFileSelections([selection]);
    }
  };

  const beforeUpload = (file: FileType) => {
    if (exceedsSingleFileSizeLimit(file.size, fileSize)) {
      staticMessage.error(i18n('common.text.singleUploadFileSize', fileSize));
      return Upload.LIST_IGNORE;
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

    jcefApi
      .selectFile({ fileTypeList, fileSize, multiple })
      .then((data) => {
        if (data) {
          const selectedFiles = (Array.isArray(data) ? data : [data]) as FileUrl[];
          addFileSelections(selectedFiles);
        }
      })
      .catch(() => staticMessage.error(i18n('common.text.failure')));
  };

  return (
    <div className={cx(className)}>
      {fileList.length ? (
        <div className={styles.uploadLocalFile}>
          <div className={styles.uploadLocalFileHeader}>
            <span>{i18n('common.text.selectedFile')}</span>
            {multiple &&
              (isDesktop ? (
                <Button
                  aria-label={i18n('common.button.add')}
                  className={styles.addIcon}
                  title={i18n('common.button.add')}
                  type="text"
                  size="small"
                  icon={<Plus size={14} />}
                  onClick={handleUpdate}
                />
              ) : (
                <Upload
                  accept={accept}
                  multiple
                  beforeUpload={beforeUpload}
                  onChange={fileUploadOnChange}
                  showUploadList={false}
                  {...rest}
                >
                  <Button
                    aria-label={i18n('common.button.add')}
                    className={styles.addIcon}
                    title={i18n('common.button.add')}
                    type="text"
                    size="small"
                    icon={<Plus size={14} />}
                  />
                </Upload>
              ))}
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
          <button
            type="button"
            className={cx(styles.uploadDragger, styles.desktopUploadDragger)}
            aria-label={description[0] || i18n('workspace.importExport.clickOrDrag')}
            onClick={handleUpdate}
          >
            <IconfontSvg className={styles.uploadDraggerIcon} size={36} code="icon-upload" />
            <span className={styles.description}>
              <span className={styles.description1}>
                {description[0] || i18n('workspace.importExport.clickOrDrag')}
              </span>
              <span className={styles.description2}>{description[1]}</span>
              {descriptionSlot}
            </span>
            {fileSize && (
              <span className={styles.limitFileSize}>
                <span>{i18n('common.text.limitFileSize', fileSize)}</span>
              </span>
            )}
          </button>
        )}
      </div>
    </div>
  );
});

export default memo(UploadLocalFile);
