import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import {
  BarChart3,
  Captions,
  Check,
  ChevronLeft,
  CircleUserRound,
  Clapperboard,
  Cpu,
  Download,
  FileVideo,
  FolderOpen,
  Gauge,
  Languages,
  LayoutDashboard,
  MonitorPlay,
  Play,
  Plus,
  Radio,
  Scissors,
  Settings,
  Sparkles,
  Upload,
  WandSparkles,
  Youtube,
  Zap,
} from "lucide-react";

type Language = "fa" | "en";
type Page = "home" | "projects" | "editor" | "youtube" | "analytics" | "settings";
type SystemProfile = { os: string; arch: string; nvidia_available: boolean; gpu_name?: string; ffmpeg_available: boolean };
type VideoInfo = { name: string; path: string; size_bytes: number; extension: string; duration_seconds: number; width: number; height: number; codec: string };
type Project = { id: number; name: string; path: string; status: "ready" | "draft"; createdAt: string; duration: number; width: number; height: number; codec: string };

const copy = {
  fa: {
    appName: "ایزی‌کلیپ",
    appSub: "استودیوی هوشمند ویدیو",
    home: "خانه",
    projects: "پروژه‌ها",
    youtube: "یوتیوب",
    analytics: "آمار",
    settings: "تنظیمات",
    badge: "پردازش کاملاً محلی",
    title1: "یک ویدیو،",
    title2: "ده‌ها کلیپ تماشایی.",
    subtitle: "هوش مصنوعی محلی بهترین لحظه‌ها را پیدا می‌کند، زیرنویس می‌سازد و خروجی عمودی آماده انتشار تحویل می‌دهد.",
    import: "انتخاب ویدیو",
    connect: "اتصال کانال یوتیوب",
    privacy: "ویدیوهای شما از کامپیوتر خارج نمی‌شوند",
    pipeline: "خط تولید هوشمند",
    pipelineSub: "از ویدیوی خام تا Shorts آماده انتشار",
    allLocal: "همه مراحل روی دستگاه شما",
    transcript: "زیرنویس دقیق",
    transcriptSub: "Whisper فارسی و انگلیسی",
    moments: "لحظات برتر",
    momentsSub: "امتیازدهی وایرال با AI",
    reframe: "کادر عمودی",
    reframeSub: "دنبال‌کردن خودکار چهره",
    render: "خروجی سریع",
    renderSub: "رندر NVIDIA با FFmpeg",
    recent: "پروژه‌های اخیر",
    empty: "اولین ویدیوی خود را اضافه کنید",
    emptySub: "فرمت‌های MP4، MOV، MKV و WebM پشتیبانی می‌شوند.",
    ready: "آماده پردازش",
    draft: "پیش‌نویس",
    open: "باز کردن",
    system: "وضعیت سیستم",
    checking: "در حال بررسی…",
    nvidia: "شتاب‌دهی NVIDIA",
    detected: "فعال و آماده",
    notDetected: "شناسایی نشد؛ حالت CPU استفاده می‌شود",
    phase: "نسخه اولیه",
    phaseText: "پوسته ویندوز و واردکردن ویدیو آماده است. موتور Whisper و FFmpeg در مرحله بعد متصل می‌شود.",
    lang: "EN",
    local: "LOCAL",
    editor: "ویرایش و خروجی",
    start: "شروع کلیپ (ثانیه)",
    end: "پایان کلیپ (ثانیه)",
    captionsFile: "فایل زیرنویس SRT (اختیاری)",
    chooseCaptions: "انتخاب زیرنویس",
    renderClip: "ساخت کلیپ عمودی",
    rendering: "در حال رندر با FFmpeg…",
    rendered: "کلیپ با موفقیت ساخته شد",
    renderError: "ساخت کلیپ ناموفق بود",
    videoInfo: "مشخصات ویدیو",
    back: "بازگشت به پروژه‌ها",
  },
  en: {
    appName: "EasyClip",
    appSub: "AI Video Studio",
    home: "Home",
    projects: "Projects",
    youtube: "YouTube",
    analytics: "Analytics",
    settings: "Settings",
    badge: "100% local processing",
    title1: "One video,",
    title2: "dozens of great clips.",
    subtitle: "Local AI finds the best moments, creates captions, and delivers vertical videos ready to publish.",
    import: "Choose video",
    connect: "Connect YouTube channel",
    privacy: "Your videos never leave your computer",
    pipeline: "Smart pipeline",
    pipelineSub: "From raw footage to publish-ready Shorts",
    allLocal: "Every step runs on your device",
    transcript: "Accurate captions",
    transcriptSub: "Persian & English Whisper",
    moments: "Best moments",
    momentsSub: "AI viral-score analysis",
    reframe: "Vertical framing",
    reframeSub: "Automatic face tracking",
    render: "Fast export",
    renderSub: "NVIDIA FFmpeg rendering",
    recent: "Recent projects",
    empty: "Add your first video",
    emptySub: "MP4, MOV, MKV and WebM are supported.",
    ready: "Ready to process",
    draft: "Draft",
    open: "Open",
    system: "System status",
    checking: "Checking…",
    nvidia: "NVIDIA acceleration",
    detected: "Active and ready",
    notDetected: "Not detected; CPU mode will be used",
    phase: "Foundation build",
    phaseText: "The Windows shell and video import are ready. Whisper and FFmpeg engines are connected next.",
    lang: "فا",
    local: "LOCAL",
    editor: "Edit & export",
    start: "Clip start (seconds)",
    end: "Clip end (seconds)",
    captionsFile: "SRT captions (optional)",
    chooseCaptions: "Choose captions",
    renderClip: "Create vertical clip",
    rendering: "Rendering with FFmpeg…",
    rendered: "Clip created successfully",
    renderError: "Clip rendering failed",
    videoInfo: "Video details",
    back: "Back to projects",
  },
};

type Translation = (typeof copy)[Language];

const isTauri = () => "__TAURI_INTERNALS__" in window;

export default function App() {
  const [language, setLanguage] = useState<Language>(() => (localStorage.getItem("easyclip-language") as Language) || "fa");
  const [page, setPage] = useState<Page>("home");
  const [profile, setProfile] = useState<SystemProfile | null>(null);
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);
  const [projects, setProjects] = useState<Project[]>(() => {
    try { return JSON.parse(localStorage.getItem("easyclip-projects") || "[]"); } catch { return []; }
  });
  const fileInput = useRef<HTMLInputElement>(null);
  const t = copy[language];
  const rtl = language === "fa";

  useEffect(() => {
    document.documentElement.lang = language;
    document.documentElement.dir = rtl ? "rtl" : "ltr";
    localStorage.setItem("easyclip-language", language);
  }, [language, rtl]);

  useEffect(() => {
    if (isTauri()) {
      invoke<SystemProfile>("system_profile").then(setProfile).catch(() => setProfile({ os: "Windows", arch: "x64", nvidia_available: false, ffmpeg_available: false }));
    } else {
      setProfile({ os: "Windows 11", arch: "x64", nvidia_available: true, gpu_name: "NVIDIA GPU (preview)", ffmpeg_available: true });
    }
  }, []);

  const addProject = async (path: string, browserFile?: File) => {
    let info: VideoInfo;
    if (isTauri()) {
      info = await invoke<VideoInfo>("inspect_video", { path });
    } else {
      info = { name: browserFile?.name || path, path, size_bytes: browserFile?.size || 0, extension: path.split(".").pop() || "mp4", duration_seconds: 120, width: 1920, height: 1080, codec: "h264" };
    }
    const project: Project = { id: Date.now(), name: info.name, path: info.path, status: "ready", createdAt: new Date().toLocaleDateString(language === "fa" ? "fa-IR" : "en-US"), duration: info.duration_seconds, width: info.width, height: info.height, codec: info.codec };
    const next = [project, ...projects.filter((item) => item.path !== path)];
    setProjects(next);
    localStorage.setItem("easyclip-projects", JSON.stringify(next));
    setSelectedProject(project);
    setPage("editor");
  };

  const chooseVideo = async () => {
    if (!isTauri()) { fileInput.current?.click(); return; }
    const selected = await open({ multiple: false, title: t.import, filters: [{ name: "Video", extensions: ["mp4", "mov", "mkv", "webm", "avi", "m4v"] }] });
    if (typeof selected === "string") {
      try { await addProject(selected); } catch (error) { window.alert(String(error)); }
    }
  };

  const openProject = (project: Project) => {
    setSelectedProject(project);
    setPage("editor");
  };

  const nav = useMemo(() => [
    { id: "home" as Page, label: t.home, icon: LayoutDashboard },
    { id: "projects" as Page, label: t.projects, icon: Clapperboard },
    { id: "youtube" as Page, label: t.youtube, icon: Youtube },
    { id: "analytics" as Page, label: t.analytics, icon: BarChart3 },
    { id: "settings" as Page, label: t.settings, icon: Settings },
  ], [t]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><img src="/icon.png" /><div><strong>{t.appName}</strong><span>{t.appSub}</span></div></div>
        <nav>{nav.map(({ id, label, icon: Icon }) => <button key={id} className={page === id ? "active" : ""} onClick={() => setPage(id)}><Icon size={19}/><span>{label}</span></button>)}</nav>
        <div className="side-bottom">
          <div className="local-chip"><span className="pulse"/><div><b>{t.local}</b><small>{t.privacy}</small></div></div>
          <button className="profile"><CircleUserRound size={30}/><div><b>Creator</b><span>Windows 11</span></div><ChevronLeft size={16}/></button>
        </div>
      </aside>

      <main>
        <header><div className="status"><span className="status-dot"/>{t.badge}</div><button className="language" onClick={() => setLanguage(language === "fa" ? "en" : "fa")}><Languages size={17}/>{t.lang}</button></header>
        {page === "home" && <Home t={t} chooseVideo={chooseVideo} setPage={setPage} profile={profile} projects={projects} />}
        {page === "projects" && <Projects t={t} projects={projects} chooseVideo={chooseVideo} openProject={openProject} />}
        {page === "editor" && selectedProject && <Editor t={t} project={selectedProject} profile={profile} goBack={() => setPage("projects")} />}
        {page !== "home" && page !== "projects" && page !== "editor" && <ComingSoon page={page} t={t} profile={profile} />}
      </main>
      <input ref={fileInput} hidden type="file" accept="video/*" onChange={(e) => { const file = e.target.files?.[0]; if (file) void addProject(file.name, file); }} />
    </div>
  );
}

function Home({ t, chooseVideo, setPage, profile, projects }: { t: Translation; chooseVideo: () => void; setPage: (p: Page) => void; profile: SystemProfile | null; projects: Project[] }) {
  const stages = [
    { icon: Captions, title: t.transcript, sub: t.transcriptSub, tone: "violet", step: "01" },
    { icon: Sparkles, title: t.moments, sub: t.momentsSub, tone: "lime", step: "02" },
    { icon: MonitorPlay, title: t.reframe, sub: t.reframeSub, tone: "blue", step: "03" },
    { icon: Zap, title: t.render, sub: t.renderSub, tone: "orange", step: "04" },
  ];
  return <div className="content">
    <section className="hero">
      <div className="hero-copy"><div className="eyebrow"><WandSparkles size={15}/>{t.badge}</div><h1>{t.title1}<br/><span>{t.title2}</span></h1><p>{t.subtitle}</p><div className="hero-actions"><button className="primary" onClick={chooseVideo}><FolderOpen size={20}/>{t.import}</button><button className="secondary" onClick={() => setPage("youtube")}><Youtube size={20}/>{t.connect}</button></div><div className="privacy"><Check size={14}/>{t.privacy}</div></div>
      <div className="hero-visual"><div className="glow"/><div className="video-frame"><div className="frame-top"><span/><span/><span/></div><div className="portrait"><div className="play"><Play size={28} fill="currentColor"/></div><div className="caption-lines"><b>YOUR NEXT VIRAL MOMENT</b><span>شروع داستان از همین‌جاست</span></div></div><div className="timeline"><div className="wave"/><div className="cut cut-a"/><div className="cut cut-b"/><span className="playhead"/></div></div><div className="score"><Gauge size={18}/><div><small>VIRAL SCORE</small><b>92</b></div></div></div>
    </section>

    <section className="section-head"><div><h2>{t.pipeline}</h2><p>{t.pipelineSub}</p></div><span><Cpu size={16}/>{t.allLocal}</span></section>
    <section className="pipeline">{stages.map(({ icon: Icon, title, sub, tone, step }) => <article key={step} className={`stage ${tone}`}><div className="step">{step}</div><div className="stage-icon"><Icon size={24}/></div><h3>{title}</h3><p>{sub}</p></article>)}</section>

    <section className="lower-grid"><div className="recent-card"><div className="card-title"><div><h2>{t.recent}</h2><span>{projects.length}</span></div><button onClick={chooseVideo}><Plus size={17}/></button></div>{projects.length === 0 ? <button className="empty" onClick={chooseVideo}><div><Upload size={25}/></div><b>{t.empty}</b><span>{t.emptySub}</span></button> : projects.slice(0, 3).map(p => <div className="project-row" key={p.id}><div className="thumb"><FileVideo size={22}/></div><div><b>{p.name}</b><span>{p.createdAt}</span></div><span className="ready">{t.ready}</span></div>)}</div>
      <div className="system-card"><div className="card-title"><div><h2>{t.system}</h2></div><Cpu size={20}/></div><div className="gpu-icon"><Zap size={28}/></div><h3>{profile?.gpu_name || (profile ? "CPU" : t.checking)}</h3><p>{t.nvidia}</p><div className={`system-state ${profile?.nvidia_available ? "ok" : "warn"}`}><span/>{profile ? (profile.nvidia_available ? t.detected : t.notDetected) : t.checking}</div><div className="phase-note"><b>{t.phase}</b><span>{t.phaseText}</span></div></div></section>
  </div>;
}

function Projects({ t, projects, chooseVideo, openProject }: { t: Translation; projects: Project[]; chooseVideo: () => void; openProject: (project: Project) => void }) {
  return <div className="content page"><div className="page-title"><div><span>LIBRARY</span><h1>{t.projects}</h1></div><button className="primary" onClick={chooseVideo}><Plus size={19}/>{t.import}</button></div>{projects.length === 0 ? <button className="large-empty" onClick={chooseVideo}><div><Scissors size={34}/></div><h2>{t.empty}</h2><p>{t.emptySub}</p></button> : <div className="project-grid">{projects.map(p => <article className="project-card" key={p.id}><div className="project-preview"><FileVideo size={35}/><span>9:16</span></div><div className="project-info"><h3>{p.name}</h3><p>{p.path}</p><div><span className="ready">{t.ready}</span><button onClick={() => openProject(p)}>{t.open}<ChevronLeft size={15}/></button></div></div></article>)}</div>}</div>;
}

function Editor({ t, project, profile, goBack }: { t: Translation; project: Project; profile: SystemProfile | null; goBack: () => void }) {
  const [start, setStart] = useState(0);
  const [end, setEnd] = useState(Math.min(project.duration || 60, 60));
  const [captionPath, setCaptionPath] = useState<string | null>(null);
  const [rendering, setRendering] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const chooseCaptions = async () => {
    if (!isTauri()) return;
    const selected = await open({ multiple: false, filters: [{ name: "SubRip captions", extensions: ["srt"] }] });
    if (typeof selected === "string") setCaptionPath(selected);
  };

  const render = async () => {
    if (!isTauri()) { setMessage({ ok: false, text: "Rendering is available in the installed Windows app." }); return; }
    const baseName = project.name.replace(/\.[^.]+$/, "");
    const outputPath = await save({ defaultPath: `${baseName}-vertical.mp4`, filters: [{ name: "MP4 video", extensions: ["mp4"] }] });
    if (!outputPath) return;
    setRendering(true);
    setMessage(null);
    try {
      const result = await invoke<{ outputPath: string; usedNvidia: boolean }>("render_vertical_clip", {
        request: { inputPath: project.path, outputPath, startSeconds: start, endSeconds: end, captionPath, useNvidia: profile?.nvidia_available ?? false },
      });
      setMessage({ ok: true, text: `${t.rendered}: ${result.outputPath}${result.usedNvidia ? " · NVIDIA NVENC" : " · CPU"}` });
    } catch (error) {
      setMessage({ ok: false, text: `${t.renderError}: ${String(error)}` });
    } finally {
      setRendering(false);
    }
  };

  return <div className="content page editor-page">
    <button className="back-button" onClick={goBack}><ChevronLeft size={17}/>{t.back}</button>
    <div className="page-title"><div><span>VERTICAL 9:16</span><h1>{t.editor}</h1></div></div>
    <div className="editor-grid">
      <section className="editor-preview"><div className="preview-phone"><FileVideo size={50}/><b>{project.name}</b><span>1080 × 1920</span></div></section>
      <section className="editor-controls">
        <div className="info-panel"><h3>{t.videoInfo}</h3><div><span>{project.width} × {project.height}</span><span>{project.codec.toUpperCase()}</span><span>{Math.round(project.duration)}s</span></div></div>
        <div className="range-row"><label>{t.start}<input type="number" min="0" max={end - 1} step="0.5" value={start} onChange={(e) => setStart(Number(e.target.value))}/></label><label>{t.end}<input type="number" min={start + 1} max={project.duration || 180} step="0.5" value={end} onChange={(e) => setEnd(Number(e.target.value))}/></label></div>
        <div className="duration-bar"><span style={{ width: `${Math.min(100, ((end - start) / Math.max(project.duration, 1)) * 100)}%` }}/></div>
        <button className="caption-picker" onClick={chooseCaptions}><Captions size={19}/><div><b>{t.captionsFile}</b><span>{captionPath || t.chooseCaptions}</span></div></button>
        <div className="render-engine"><Zap size={18}/><div><b>{profile?.nvidia_available ? profile.gpu_name : "CPU / libx264"}</b><span>{profile?.nvidia_available ? "NVIDIA NVENC" : "Software encoder"}</span></div><Check size={17}/></div>
        <button className="primary render-button" disabled={rendering} onClick={render}>{rendering ? <><span className="spinner"/>{t.rendering}</> : <><Download size={20}/>{t.renderClip}</>}</button>
        {message && <div className={`render-message ${message.ok ? "success" : "error"}`}>{message.ok ? <Check size={17}/> : <span>!</span>}<p>{message.text}</p></div>}
      </section>
    </div>
  </div>;
}

function ComingSoon({ page, t, profile }: { page: Page; t: Translation; profile: SystemProfile | null }) {
  const Icon = page === "youtube" ? Youtube : page === "analytics" ? BarChart3 : Settings;
  return <div className="content page"><div className="coming"><div><Icon size={36}/></div><span>{t.phase}</span><h1>{page === "youtube" ? t.connect : page === "analytics" ? t.analytics : t.settings}</h1><p>{t.phaseText}</p>{page === "settings" && <div className="spec"><Cpu size={18}/><b>{profile?.gpu_name || "NVIDIA / CPU"}</b><span>{profile?.os} · {profile?.arch}</span></div>}</div></div>;
}
