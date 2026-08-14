import { useEffect, useState } from 'react';
import { Clipboard, Image, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import type { Privacy, VideoMetadata } from '../../electron/types';

const valid = (url: string): boolean => /^https?:\/\/(?:www\.|m\.)?(?:youtube\.com\/(?:watch\?v=|shorts\/)|youtu\.be\/)[\w-]+/i.test(url);
const categories = [['1','Film'],['2','Autos'],['10','Music'],['15','Pets'],['17','Sports'],['20','Gaming'],['22','People'],['23','Comedy'],['24','Entertainment'],['25','News'],['26','How-to'],['27','Education'],['28','Science']] as const;

export function UploadSingle(): JSX.Element {
  const [url, setUrl] = useState('');
  const [meta, setMeta] = useState<VideoMetadata | null>(null);
  const [loading, setLoading] = useState(false);
  const [title, setTitle] = useState('{original_title}');
  const [description, setDescription] = useState('');
  const [tags, setTags] = useState('');
  const [privacy, setPrivacy] = useState<Privacy>('unlisted');
  const [quality, setQuality] = useState('bestvideo[height<=1080]+bestaudio/best');
  const [categoryId, setCategoryId] = useState('22');
  const [madeForKids, setMadeForKids] = useState(false);
  const [thumbnailPath, setThumbnailPath] = useState<string>();

  useEffect(() => {
    if (!valid(url)) { setMeta(null); return; }
    const timer = setTimeout(() => {
      setLoading(true);
      void window.api.video.metadata(url).then((value) => { setMeta(value); setDescription(value.description); }).catch((error: unknown) => toast.error(error instanceof Error ? error.message : String(error))).finally(() => setLoading(false));
    }, 500);
    return () => clearTimeout(timer);
  }, [url]);

  const submit = async (): Promise<void> => {
    if (!meta) return;
    try {
      const handle = await window.api.upload.single({ url, title, description, tags: tags.split(',').map((tag) => tag.trim()).filter(Boolean), privacy, quality, categoryId, madeForKids, thumbnailPath });
      toast.success(`Job queued: ${handle.jobId.slice(0, 8)}`);
    } catch (error: unknown) { toast.error(error instanceof Error ? error.message : String(error)); }
  };

  return <div className="page">
    <h1 className="text-2xl font-bold">Upload a single video</h1><p className="muted mt-1">Use only videos you own or have permission to redistribute.</p>
    <div className="card mt-7 p-6 min-h-[190px] border-dashed grid place-items-center"><div className="w-full max-w-3xl text-center"><div className="text-lg font-semibold mb-4">Paste a YouTube video URL</div><div className="flex gap-2"><input className="input" placeholder="https://youtube.com/watch?v=..." value={url} onChange={(event) => setUrl(event.target.value)}/><button className="btn" aria-label="Paste from clipboard" onClick={() => void navigator.clipboard.readText().then(setUrl)}><Clipboard size={18}/></button></div>{loading && <Loader2 className="animate-spin mx-auto mt-5"/>}</div></div>
    {meta && <div className="grid grid-cols-[minmax(300px,1fr)_1fr] gap-6 mt-6">
      <div className="card overflow-hidden h-fit"><img src={meta.thumbnail} className="w-full aspect-video object-cover"/><div className="p-4"><b>{meta.title}</b><div className="muted text-sm mt-2">{meta.channel} · {Math.floor(meta.duration / 60)}:{String(meta.duration % 60).padStart(2, '0')} · {meta.viewCount.toLocaleString()} views</div></div></div>
      <div className="card p-5 space-y-4">
        <label><span className="label">Title</span><input className="input" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={100}/></label>
        <label><span className="label">Description</span><textarea className="input h-28" value={description} onChange={(event) => setDescription(event.target.value)}/></label>
        <label><span className="label">Tags, separated by commas</span><input className="input" value={tags} onChange={(event) => setTags(event.target.value)}/></label>
        <div className="grid grid-cols-3 gap-3"><label><span className="label">Privacy</span><select className="input" value={privacy} onChange={(event) => setPrivacy(event.target.value as Privacy)}><option value="private">Private</option><option value="unlisted">Unlisted</option><option value="public">Public</option></select></label><label><span className="label">Quality</span><select className="input" value={quality} onChange={(event) => setQuality(event.target.value)}><option value="bestvideo[height<=720]+bestaudio/best">720p</option><option value="bestvideo[height<=1080]+bestaudio/best">1080p</option><option value="bestvideo[height<=1440]+bestaudio/best">1440p</option><option value="bestvideo[height<=2160]+bestaudio/best">2160p</option></select></label><label><span className="label">Category</span><select className="input" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>{categories.map(([id,name]) => <option key={id} value={id}>{name}</option>)}</select></label></div>
        <div className="flex items-center justify-between"><label className="flex items-center gap-2"><input type="checkbox" checked={madeForKids} onChange={(event) => setMadeForKids(event.target.checked)}/> Made for kids</label><button className="btn flex gap-2" onClick={() => void window.api.settings.pickImage().then((value) => value && setThumbnailPath(value))}><Image size={17}/>{thumbnailPath ? 'Thumbnail selected' : 'Custom thumbnail'}</button></div>
        <button className="btn btn-primary w-full" onClick={() => void submit()}>Download & upload</button>
      </div>
    </div>}
  </div>;
}
