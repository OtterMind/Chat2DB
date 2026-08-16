import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { MinusOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Modal, Segmented, Tooltip } from 'antd';
import { staticMessage } from '@chat2db/ui';
import { Code, Columns2, Eye } from 'lucide-react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import ReactMarkdown, { type Components } from 'react-markdown';
import rehypeKatex from 'rehype-katex';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import MonacoEditor, { type IEditorIns, type IExportRefFunction } from '@/components/MonacoEditor';
import i18n from '@/i18n';
import type { IBoundInfo } from '@/typings';
import { updateFileContent } from '@/utils/file';
import 'katex/dist/katex.min.css';
import styles from './FilePreviewTab.less';
import { getSqlDirectoryPreviewUrl } from '@/utils/previewResource';
import LocalFileEncodingSelect from '@/components/LocalFileEncodingSelect';
import { useWorkspaceStore } from '@/store/workspace';
import { hasUnsavedLocalFileChanges } from '@/utils/localFileEncoding';

interface FilePreviewTabProps {
  file: IBoundInfo;
  workspaceTabId: string | number;
}

type MarkdownViewMode = 'source' | 'review' | 'split';
type ScrollSyncOwner = 'source' | 'review' | null;
type PdfPreviewState = 'loading' | 'loaded' | 'failed';

const MIN_IMAGE_ZOOM = 0.25;
const MAX_IMAGE_ZOOM = 5;
const IMAGE_ZOOM_STEP = 0.25;
const PDF_LOAD_TIMEOUT_MS = 8000;
const MARKDOWN_PREVIEW_DELAY_MS = 200;
const MERMAID_CACHE_LIMIT = 50;

let mermaidPromise: Promise<(typeof import('mermaid'))['default']> | undefined;
let mermaidRenderQueue: Promise<unknown> = Promise.resolve();
const mermaidSvgCache = new Map<string, { diagramId: string; svg: string }>();

function getMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'neutral',
        fontFamily: 'inherit',
      });
      return mermaid;
    });
  }
  return mermaidPromise;
}

function renderMermaid(source: string, diagramId: string, host?: HTMLDivElement) {
  const cached = mermaidSvgCache.get(source);
  if (cached) {
    return Promise.resolve(cached.svg.replaceAll(cached.diagramId, diagramId));
  }
  const renderPromise = mermaidRenderQueue
    .catch(() => undefined)
    .then(async () => {
      const mermaid = await getMermaid();
      const parseResult = await mermaid.parse(source, { suppressErrors: true });
      if (!parseResult) {
        return undefined;
      }
      const { svg } = await mermaid.render(diagramId, source, host);
      mermaidSvgCache.set(source, { diagramId, svg });
      if (mermaidSvgCache.size > MERMAID_CACHE_LIMIT) {
        mermaidSvgCache.delete(mermaidSvgCache.keys().next().value!);
      }
      return svg;
    });
  mermaidRenderQueue = renderPromise;
  return renderPromise;
}

function MermaidDiagram({ source }: { source: string }) {
  const reactId = useId();
  const diagramId = useMemo(() => `chat2db-mermaid-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`, [reactId]);
  const renderHostRef = useRef<HTMLDivElement>(null);
  const [svg, setSvg] = useState('');
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    setSvg('');
    setError(false);
    renderMermaid(source, diagramId, renderHostRef.current || undefined)
      .then((renderedSvg) => {
        if (active && renderedSvg) {
          setSvg(renderedSvg);
        } else if (active) {
          setError(true);
        }
      })
      .catch((renderError) => {
        console.error('render mermaid preview error', renderError);
        if (active) {
          setError(true);
        }
      });
    return () => {
      active = false;
    };
  }, [diagramId, source]);

  return (
    <>
      <div ref={renderHostRef} className={styles.mermaidRenderHost} aria-hidden="true" />
      {error && <pre className={styles.diagramError}>{i18n('workspace.filePreview.mermaidFailed')}</pre>}
      {!error && !svg && <div className={styles.diagramLoading}>{i18n('common.text.loading')}</div>}
      {!error && svg && <div className={styles.diagram} dangerouslySetInnerHTML={{ __html: svg }} />}
    </>
  );
}

function MarkdownSourceEditor({
  source,
  filePath,
  fileCharset,
  fileBom,
  onChange,
  onSave,
  onEditorChange,
  onCursorPositionChange,
}: {
  source: string;
  filePath?: string;
  fileCharset?: string;
  fileBom?: boolean;
  onChange: (value: string) => void;
  onSave: (value: string) => void;
  onEditorChange: (editor?: IEditorIns) => void;
  onCursorPositionChange: (position: monaco.IPosition) => void;
}) {
  const reactId = useId();
  const editorId = useMemo(() => `markdown-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`, [reactId]);
  const editorRef = useRef<IExportRefFunction>(null);

  useEffect(() => {
    if (editorRef.current?.getAllContent() !== source) {
      editorRef.current?.setValue(source, 'reset');
    }
  }, [source]);

  useEffect(() => () => onEditorChange(undefined), [onEditorChange]);

  return (
    <MonacoEditor
      ref={editorRef}
      id={editorId}
      language="markdown"
      defaultValue={source}
      options={{
        minimap: { enabled: false },
        wordWrap: 'on',
        scrollBeyondLastLine: false,
        automaticLayout: true,
        padding: { top: 12, bottom: 12 },
      }}
      didMount={(editor) => {
        onEditorChange(editor);
        const initialPosition = editor.getPosition();
        if (initialPosition) {
          onCursorPositionChange(initialPosition);
        }
        editor.onDidChangeCursorPosition(({ position }) => onCursorPositionChange(position));
        editor.onDidChangeModelContent(() => onChange(editor.getValue()));
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
          if (filePath) {
            const fileContent = editor.getValue();
            void updateFileContent({
              filePath,
              fileContent,
              charset: fileCharset,
              bom: fileBom,
            })
              .then(() => {
                onSave(fileContent);
                staticMessage.success(i18n('workspace.text.changeFileSuccess'));
              })
              .catch((error) => {
                console.error('update local file error', error);
                staticMessage.error(i18n('common.text.failure'));
              });
          }
        });
      }}
    />
  );
}

function resolveRelativePath(markdownPath: string | undefined, source: string) {
  if (!markdownPath || !source || /^(?:[a-z]+:|#|\/)/i.test(source)) {
    return undefined;
  }
  const segments = markdownPath.replace(/\\/g, '/').split('/');
  segments.pop();
  for (const segment of source.replace(/\\/g, '/').split('/')) {
    if (!segment || segment === '.') {
      continue;
    }
    if (segment === '..') {
      segments.pop();
    } else {
      segments.push(segment);
    }
  }
  return segments.join('/');
}

function MarkdownImage({
  alt,
  src,
  file,
}: {
  alt?: string;
  src?: string;
  file: IBoundInfo;
}) {
  const relativePath = resolveRelativePath(file.fileRelativePath, src || '');
  const resolvedSource =
    file.fileRootToken && relativePath
      ? getSqlDirectoryPreviewUrl(file.fileRootToken, relativePath)
      : src;

  if (!resolvedSource) {
    return <span className={styles.brokenImage}>{alt || i18n('workspace.filePreview.imageFailed')}</span>;
  }
  return <img alt={alt || ''} src={resolvedSource} loading="lazy" />;
}

const FilePreviewTab = memo(({ file, workspaceTabId }: FilePreviewTabProps) => {
  const extension = (file.fileExtension || '').toLowerCase();
  const isMarkdown = extension === 'md' || extension === 'markdown';
  const [markdownViewMode, setMarkdownViewMode] = useState<MarkdownViewMode>('review');
  const [markdownContent, setMarkdownContent] = useState(file.ddl || '');
  const [markdownPreviewContent, setMarkdownPreviewContent] = useState(file.ddl || '');
  const [markdownPersistedContent, setMarkdownPersistedContent] = useState(file.ddl || '');
  const [editor, setEditor] = useState<IEditorIns>();
  const [imageZoom, setImageZoom] = useState(1);
  const [imageNaturalSize, setImageNaturalSize] = useState({ width: 0, height: 0 });
  const [imageViewportSize, setImageViewportSize] = useState({ width: 0, height: 0 });
  const [pdfPreviewState, setPdfPreviewState] = useState<PdfPreviewState>('loading');
  const markdownReviewRef = useRef<HTMLDivElement>(null);
  const imageViewportRef = useRef<HTMLDivElement>(null);
  const pdfObjectRef = useRef<HTMLObjectElement>(null);
  const scrollSyncOwnerRef = useRef<ScrollSyncOwner>(null);
  const scrollSyncFrameRef = useRef<number | undefined>(undefined);
  const pdfLoadTimeoutRef = useRef<number | undefined>(undefined);
  const markdownCursorPositionRef = useRef<HTMLSpanElement>(null);
  const [modal, modalContextHolder] = Modal.useModal();

  const confirmDiscardUnsavedChanges = useCallback(
    () =>
      new Promise<boolean>((resolve) => {
        modal.confirm({
          title: i18n('workspace.fileEncoding.unsavedTitle'),
          content: i18n('workspace.fileEncoding.unsavedContent'),
          okText: i18n('workspace.fileEncoding.reloadConfirm'),
          okButtonProps: { danger: true },
          cancelText: i18n('common.button.cancel'),
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      }),
    [modal],
  );

  const handleFileEncodingChange = useCallback(
    async (charset?: string) => {
      if (!file.filePath) {
        return;
      }
      if (
        hasUnsavedLocalFileChanges(markdownContent, markdownPersistedContent) &&
        !(await confirmDiscardUnsavedChanges())
      ) {
        return;
      }
      const reloadedFile = await useWorkspaceStore.getState().readFile(file.filePath, file.fileExtension, {
        rootToken: file.fileRootToken,
        relativePath: file.fileRelativePath,
        charset,
        workspaceTabId,
      });
      if (!reloadedFile) {
        return;
      }
      setMarkdownContent(reloadedFile.content);
      setMarkdownPreviewContent(reloadedFile.content);
      setMarkdownPersistedContent(reloadedFile.content);
    },
    [
      confirmDiscardUnsavedChanges,
      file.fileExtension,
      file.filePath,
      file.fileRelativePath,
      file.fileRootToken,
      markdownContent,
      markdownPersistedContent,
      workspaceTabId,
    ],
  );

  const handleMarkdownCursorPositionChange = useCallback((position: monaco.IPosition) => {
    if (markdownCursorPositionRef.current) {
      markdownCursorPositionRef.current.textContent = `Ln ${position.lineNumber}, Col ${position.column}`;
    }
  }, []);

  useEffect(() => {
    setMarkdownContent(file.ddl || '');
    setMarkdownPreviewContent(file.ddl || '');
    setMarkdownPersistedContent(file.ddl || '');
    setMarkdownViewMode('review');
    setImageZoom(1);
    setImageNaturalSize({ width: 0, height: 0 });
  }, [file.filePath]);

  useEffect(() => {
    if (markdownViewMode === 'source') {
      return undefined;
    }
    const timer = window.setTimeout(() => setMarkdownPreviewContent(markdownContent), MARKDOWN_PREVIEW_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [markdownContent, markdownViewMode]);

  useEffect(() => {
    const viewport = imageViewportRef.current;
    if (!viewport) {
      return undefined;
    }
    const updateViewportSize = () => {
      setImageViewportSize({
        width: viewport.clientWidth,
        height: viewport.clientHeight,
      });
    };
    updateViewportSize();
    const resizeObserver = new ResizeObserver(updateViewportSize);
    resizeObserver.observe(viewport);
    return () => resizeObserver.disconnect();
  }, [file.filePath, file.filePreviewMimeType]);

  useEffect(() => {
    if (pdfLoadTimeoutRef.current) {
      window.clearTimeout(pdfLoadTimeoutRef.current);
      pdfLoadTimeoutRef.current = undefined;
    }
    if (file.filePreviewMimeType !== 'application/pdf' || !file.filePreviewUrl) {
      return undefined;
    }

    const pdfViewerEnabled = (navigator as Navigator & { pdfViewerEnabled?: boolean }).pdfViewerEnabled;
    if (pdfViewerEnabled === false) {
      setPdfPreviewState('failed');
      return undefined;
    }

    setPdfPreviewState('loading');
    pdfLoadTimeoutRef.current = window.setTimeout(() => {
      setPdfPreviewState('failed');
      pdfLoadTimeoutRef.current = undefined;
    }, PDF_LOAD_TIMEOUT_MS);

    return () => {
      if (pdfLoadTimeoutRef.current) {
        window.clearTimeout(pdfLoadTimeoutRef.current);
        pdfLoadTimeoutRef.current = undefined;
      }
    };
  }, [file.filePath, file.filePreviewUrl, file.filePreviewMimeType]);

  const handlePdfLoad = () => {
    if (pdfLoadTimeoutRef.current) {
      window.clearTimeout(pdfLoadTimeoutRef.current);
      pdfLoadTimeoutRef.current = undefined;
    }
    setPdfPreviewState('loaded');
  };

  useEffect(() => {
    const object = pdfObjectRef.current;
    if (!object || pdfPreviewState === 'failed') {
      return undefined;
    }
    const handleError = () => {
      if (pdfLoadTimeoutRef.current) {
        window.clearTimeout(pdfLoadTimeoutRef.current);
        pdfLoadTimeoutRef.current = undefined;
      }
      setPdfPreviewState('failed');
    };
    object.addEventListener('error', handleError);
    return () => object.removeEventListener('error', handleError);
  }, [pdfPreviewState]);

  useEffect(() => {
    if (
      markdownViewMode !== 'split' ||
      !editor ||
      typeof editor.onDidScrollChange !== 'function' ||
      typeof editor.getScrollHeight !== 'function' ||
      typeof editor.getLayoutInfo !== 'function' ||
      typeof editor.getScrollTop !== 'function'
    ) {
      return undefined;
    }
    const releaseScrollOwner = (owner: ScrollSyncOwner) => {
      if (scrollSyncFrameRef.current) {
        cancelAnimationFrame(scrollSyncFrameRef.current);
      }
      scrollSyncFrameRef.current = requestAnimationFrame(() => {
        if (scrollSyncOwnerRef.current === owner) {
          scrollSyncOwnerRef.current = null;
        }
      });
    };
    const syncReviewScroll = () => {
      try {
        if (scrollSyncOwnerRef.current === 'review') {
          return;
        }
        const reviewScroller = markdownReviewRef.current;
        if (!reviewScroller) {
          return;
        }
        const sourceMaxScroll = Math.max(0, editor.getScrollHeight() - editor.getLayoutInfo().height);
        const reviewMaxScroll = Math.max(0, reviewScroller.scrollHeight - reviewScroller.clientHeight);
        const ratio = sourceMaxScroll ? editor.getScrollTop() / sourceMaxScroll : 0;
        scrollSyncOwnerRef.current = 'source';
        reviewScroller.scrollTop = ratio * reviewMaxScroll;
        releaseScrollOwner('source');
      } catch (error) {
        scrollSyncOwnerRef.current = null;
        console.error('sync markdown review scroll error', error);
      }
    };
    let scrollDisposable: { dispose: () => void } | undefined;
    try {
      scrollDisposable = editor.onDidScrollChange((event: { scrollTopChanged: boolean }) => {
        if (event.scrollTopChanged) {
          syncReviewScroll();
        }
      });
      syncReviewScroll();
    } catch (error) {
      console.error('initialize markdown scroll sync error', error);
    }
    return () => {
      scrollDisposable?.dispose();
      if (scrollSyncFrameRef.current) {
        cancelAnimationFrame(scrollSyncFrameRef.current);
      }
      scrollSyncOwnerRef.current = null;
    };
  }, [editor, markdownViewMode]);

  const handleReviewScroll = () => {
    if (
      markdownViewMode !== 'split' ||
      !editor ||
      typeof editor.setScrollTop !== 'function' ||
      typeof editor.getScrollHeight !== 'function' ||
      typeof editor.getLayoutInfo !== 'function' ||
      scrollSyncOwnerRef.current === 'source'
    ) {
      return;
    }
    const reviewScroller = markdownReviewRef.current;
    if (!reviewScroller) {
      return;
    }
    try {
      const reviewMaxScroll = Math.max(0, reviewScroller.scrollHeight - reviewScroller.clientHeight);
      const sourceMaxScroll = Math.max(0, editor.getScrollHeight() - editor.getLayoutInfo().height);
      const ratio = reviewMaxScroll ? reviewScroller.scrollTop / reviewMaxScroll : 0;
      scrollSyncOwnerRef.current = 'review';
      editor.setScrollTop(ratio * sourceMaxScroll);
      if (scrollSyncFrameRef.current) {
        cancelAnimationFrame(scrollSyncFrameRef.current);
      }
      scrollSyncFrameRef.current = requestAnimationFrame(() => {
        if (scrollSyncOwnerRef.current === 'review') {
          scrollSyncOwnerRef.current = null;
        }
      });
    } catch (error) {
      scrollSyncOwnerRef.current = null;
      console.error('sync markdown source scroll error', error);
    }
  };

  const markdownComponents = useMemo<Components>(
    () => ({
      a: ({ href, children }) => (
        <a href={href} target="_blank" rel="noreferrer noopener">
          {children}
        </a>
      ),
      code: ({ className, children, ...props }) => {
        const language = /language-([^\s]+)/.exec(className || '')?.[1]?.toLowerCase();
        const source = String(children).replace(/\n$/, '');
        if (language === 'mermaid') {
          return <MermaidDiagram source={source} />;
        }
        return (
          <code className={className} {...props}>
            {children}
          </code>
        );
      },
      img: ({ alt, src }) => <MarkdownImage alt={alt} src={src} file={file} />,
    }),
    [file],
  );

  if (isMarkdown) {
    const review = (
      <div ref={markdownReviewRef} className={styles.markdownScroller} onScroll={handleReviewScroll}>
        <article className={styles.markdown}>
          <ReactMarkdown
            remarkPlugins={[remarkGfm, remarkMath]}
            rehypePlugins={[rehypeKatex]}
            components={markdownComponents}
          >
            {markdownPreviewContent}
          </ReactMarkdown>
        </article>
      </div>
    );
    const source = (
      <MarkdownSourceEditor
        key={`${workspaceTabId}:${file.filePath || ''}:${file.fileCharset || ''}:${String(file.fileBom)}`}
        source={markdownContent}
        filePath={file.filePath}
        fileCharset={file.fileCharset}
        fileBom={file.fileBom}
        onChange={setMarkdownContent}
        onSave={setMarkdownPersistedContent}
        onEditorChange={setEditor}
        onCursorPositionChange={handleMarkdownCursorPositionChange}
      />
    );

    return (
      <div className={styles.markdownWorkspace}>
        {modalContextHolder}
        <div className={styles.markdownToolbar}>
          <Segmented
            size="small"
            value={markdownViewMode}
            onChange={(value) => setMarkdownViewMode(value as MarkdownViewMode)}
            options={[
              {
                label: (
                  <Tooltip title={i18n('workspace.filePreview.source')}>
                    <span className={styles.viewModeIcon} aria-label={i18n('workspace.filePreview.source')}>
                      <Code size={16} />
                    </span>
                  </Tooltip>
                ),
                value: 'source',
              },
              {
                label: (
                  <Tooltip title={i18n('workspace.filePreview.preview')}>
                    <span className={styles.viewModeIcon} aria-label={i18n('workspace.filePreview.preview')}>
                      <Eye size={16} />
                    </span>
                  </Tooltip>
                ),
                value: 'review',
              },
              {
                label: (
                  <Tooltip title={i18n('workspace.filePreview.split')}>
                    <span className={styles.viewModeIcon} aria-label={i18n('workspace.filePreview.split')}>
                      <Columns2 size={16} />
                    </span>
                  </Tooltip>
                ),
                value: 'split',
              },
            ]}
          />
        </div>
        <div className={styles.markdownContent}>
          {markdownViewMode === 'source' && <div className={styles.sourcePane}>{source}</div>}
          {markdownViewMode === 'review' && <div className={styles.reviewPane}>{review}</div>}
          {markdownViewMode === 'split' && (
            <div className={styles.splitPane}>
              <div className={styles.sourcePane}>{source}</div>
              <div className={styles.reviewPane}>{review}</div>
            </div>
          )}
        </div>
        <div className={styles.markdownStatus}>
          <LocalFileEncodingSelect
            charset={file.fileCharset}
            bom={file.fileBom}
            onEncodingChange={handleFileEncodingChange}
          />
          {markdownViewMode !== 'review' && (
            <span ref={markdownCursorPositionRef} className={styles.markdownCursorPosition}>
              Ln 1, Col 1
            </span>
          )}
        </div>
      </div>
    );
  }

  if (file.filePreviewMimeType?.startsWith('image/') && file.filePreviewUrl) {
    const fitScale =
      imageNaturalSize.width && imageNaturalSize.height && imageViewportSize.width && imageViewportSize.height
        ? Math.min(
            1,
            imageViewportSize.width / imageNaturalSize.width,
            imageViewportSize.height / imageNaturalSize.height,
          )
        : 1;
    const renderedScale = fitScale * imageZoom;
    const renderedWidth = imageNaturalSize.width * renderedScale;
    const renderedHeight = imageNaturalSize.height * renderedScale;
    return (
      <div className={styles.imagePreview}>
        <div className={styles.imageToolbar}>
          <Tooltip title={i18n('setting.shortcut.zoomOut')}>
            <Button
              type="text"
              size="small"
              icon={<MinusOutlined />}
              aria-label={i18n('setting.shortcut.zoomOut')}
              disabled={imageZoom <= MIN_IMAGE_ZOOM}
              onClick={() => setImageZoom((zoom) => Math.max(MIN_IMAGE_ZOOM, zoom - IMAGE_ZOOM_STEP))}
            />
          </Tooltip>
          <Tooltip title={i18n('setting.shortcut.zoomReset')}>
            <Button type="text" size="small" className={styles.imageZoomValue} onClick={() => setImageZoom(1)}>
              {Math.round(imageZoom * 100)}%
            </Button>
          </Tooltip>
          <Tooltip title={i18n('setting.shortcut.zoomIn')}>
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              aria-label={i18n('setting.shortcut.zoomIn')}
              disabled={imageZoom >= MAX_IMAGE_ZOOM}
              onClick={() => setImageZoom((zoom) => Math.min(MAX_IMAGE_ZOOM, zoom + IMAGE_ZOOM_STEP))}
            />
          </Tooltip>
        </div>
        <div ref={imageViewportRef} className={styles.imageScroller}>
          <div
            className={styles.imageCanvas}
            style={{
              width: Math.max(imageViewportSize.width, renderedWidth),
              height: Math.max(imageViewportSize.height, renderedHeight),
            }}
          >
            <img
              src={file.filePreviewUrl}
              alt={file.filePath || ''}
              style={imageNaturalSize.width ? { width: renderedWidth, height: renderedHeight } : undefined}
              onLoad={(event) =>
                setImageNaturalSize({
                  width: event.currentTarget.naturalWidth,
                  height: event.currentTarget.naturalHeight,
                })
              }
            />
          </div>
        </div>
      </div>
    );
  }

  if (file.filePreviewMimeType === 'application/pdf' && file.filePreviewUrl) {
    return (
      <div className={styles.pdfPreview}>
        {pdfPreviewState !== 'failed' && (
          <object
            ref={pdfObjectRef}
            className={styles.pdfObject}
            data={file.filePreviewUrl}
            type="application/pdf"
            aria-label={file.filePath || 'PDF'}
            onLoad={handlePdfLoad}
          >
            <div className={styles.previewFallback}>{i18n('workspace.filePreview.pdfUnsupported')}</div>
          </object>
        )}
        {pdfPreviewState === 'loading' && (
          <div className={styles.pdfStatus} aria-live="polite">
            {i18n('common.text.loading')}
          </div>
        )}
        {pdfPreviewState === 'failed' && (
          <div className={styles.previewFallback} role="alert">
            {i18n('workspace.filePreview.pdfUnsupported')}
          </div>
        )}
      </div>
    );
  }

  return <div className={styles.previewFallback}>{i18n('workspace.filePreview.unsupported')}</div>;
});

export default FilePreviewTab;
