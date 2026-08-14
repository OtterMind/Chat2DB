import { useMemo, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { Pause, Play, Search } from 'lucide-react';
import { toast } from 'sonner';
import type { Privacy, VideoMetadata } from '../../electron/types';

export function UploadChannel(): JSX.Element {
  const [url, setUrl] = useState('');
  const [videos, setVideos] = useState<VideoMetadata[]>([]);
  const [selected, setSelected] = useState(new Set<string>());
  const [search, setSearch] = useState('');
  const [titles, setTitles] = useState<Record<string, string>>({});
  const [privacy, setPrivacy] = useState<Record<string, Privacy>>({});
  const [loading, setLoading] = useState(false);
  const [started, setStarted] = useState(false);
  const [paused, setPaused] = useState(false);
  const parent = useRef<HTMLDivElement>(null);
  const filtered = useMemo(() => videos.filter((video) => video.title.toLowerCase().includes(search.toLowerCase())), [search, videos]);
  const virtualizer = useVirtualizer({ count: filtered.length, getScrollElement: () => parent.current, estimateSize: () => 88, overscan: 6 });

  const fetchVideos = async (): Promise<void> => {
    try {
      setLoading(true);
      const result = await window.api.video.playlist(url);
      setVideos(result); setSelected(new Set(result.map((video) => video.id)));
      setTitles(Object.fromEntries(result.map((video) => [video.id, video.title])));
    } catch (error: unknown) { toast.error(error instanceof Error ? error.message : String(error)); }
    finally { setLoading(false); }
  };
  const start = async (): Promise<void> => {
    const inputs = videos.filter((video) => selected.has(video.id)).map((video) => ({ url: video.url, title: titles[video.id] ?? video.title, privacy: privacy[video.id] ?? 'unlisted' as Privacy }));
    await window.api.upload.batch(inputs); setStarted(true); toast.success(`${String(inputs.length)} videos queued`);
  };
  const togglePause = async (): Promise<void> => {
    if (paused) await window.api.upload.resume(); else await window.api.upload.pause();
    setPaused(!paused);
  };
  const selectLast = (count: number): void => setSelected(new Set(videos.slice(0, count).map((video) => video.id)));

  return <div className="page">
    <h1 className="text-2xl font-bold">Batch upload a channel</h1><p className="muted mt-1">Review every item before queuing authorized content.</p>
    <div className="card p-5 mt-6 flex gap-2"><input className="input" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="Channel URL, @handle, or playlist"/><button disabled={loading} className="btn btn-primary whitespace-nowrap" onClick={() => void fetchVideos()}>{loading ? 'Fetching…' : 'Fetch videos'}</button></div>
    {videos.length > 0 && <>
      <div className="my-4 flex items-center justify-between gap-3"><div className="relative flex-1 max-w-md"><Search className="absolute left-3 top-3 text-zinc-500" size={17}/><input className="input !pl-10" placeholder="Search titles" value={search} onChange={(event) => setSearch(event.target.value)}/></div><span className="muted text-sm">{selected.size} of {videos.length} selected</span><div className="flex gap-2"><button className="btn" onClick={() => setSelected(new Set(videos.map((video) => video.id)))}>All</button><button className="btn" onClick={() => setSelected(new Set())}>None</button><button className="btn" onClick={() => selectLast(10)}>Last 10</button>{started && <button className="btn" onClick={() => void togglePause()}>{paused ? <Play size={17}/> : <Pause size={17}/>}</button>}<button className="btn btn-primary" onClick={() => void start()}>Start batch</button></div></div>
      <div ref={parent} className="card h-[560px] overflow-auto"><div style={{ height: `${String(virtualizer.getTotalSize())}px`, position: 'relative' }}>{virtualizer.getVirtualItems().map((item) => {const video=filtered[item.index];if(!video)return null;return <div key={video.id} className="absolute left-0 right-0 border-b border-border p-3 flex gap-3 items-center" style={{height:`${String(item.size)}px`,transform:`translateY(${String(item.start)}px)`}}><input type="checkbox" checked={selected.has(video.id)} onChange={(event) => setSelected((current) => {const next=new Set(current);if(event.target.checked)next.add(video.id);else next.delete(video.id);return next;})}/><img src={video.thumbnail} className="w-24 aspect-video object-cover rounded"/><div className="flex-1 min-w-0"><input className="input !py-2" value={titles[video.id] ?? video.title} onChange={(event) => setTitles((current) => ({...current,[video.id]:event.target.value}))}/><div className="muted text-xs mt-1">{Math.floor(video.duration/60)}:{String(Math.round(video.duration%60)).padStart(2,'0')} · {video.viewCount.toLocaleString()} views</div></div><select className="input !w-28" value={privacy[video.id] ?? 'unlisted'} onChange={(event) => setPrivacy((current) => ({...current,[video.id]:event.target.value as Privacy}))}><option value="private">Private</option><option value="unlisted">Unlisted</option><option value="public">Public</option></select></div>})}</div></div>
    </>}
  </div>;
}
