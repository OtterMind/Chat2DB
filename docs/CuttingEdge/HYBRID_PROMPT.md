# Master Prompt — Cutting Edge v0.9.34 — Hybrid UI/UX Session

> **دستورالعمل استفاده:** کل محتوای فایل را کپی کنید و در ابتدای نشست جدید به AI بدهید.
> سپس اولین سؤال خود را بپرسید. AI تمام context را خواهد داشت.

---

```
═══════════════════════════════════════════════════════════════════════════
🎬 CUTTING EDGE v0.9.34 — HYBRID DESIGN SYSTEM SESSION
═══════════════════════════════════════════════════════════════════════════

## CONTEXT (خواندن و درک کن قبل از هر پاسخ)

You are continuing a design + engineering session for **Cutting Edge v0.9.34**, a
desktop video editor for Windows. The project is real, the code is on disk, and a
complete design specification has already been produced.

### THE PROJECT
- **Name:** Cutting Edge (CE) — میز تدوین هوشمند رومیزی
- **Type:** Desktop video editor (Electron + React/Vite + FastAPI/SQLite + FFmpeg)
- **Audience:** Content creators making short-form video (Reels, Shorts, TikTok)
- **Distribution:** Windows desktop (electron-builder NSIS installer)
- **License:** Fully offline, on-device, no cloud
- **Differentiation:** AI-powered style matching with deterministic fallback

### TECH STACK (verified by reading the actual code)
- **Frontend:** React 18.3.1 + TypeScript 5.5 + Vite 5.4 + Ant Design 5.19
  + Zustand 4.5 + framer-motion 13.1 + Tailwind 3.4 (utility, not used much)
  + react-router 6.26 + @tanstack/react-query 5.51
- **Backend:** Python 3.11+ + FastAPI + SQLite (WAL mode) + Pydantic v2
- **Optional:** faster-whisper (transcription), Ollama (local LLM), FFmpeg
- **Installer:** electron-builder with electron-updater

### CODE STATS (read directly from the repo)
- **Total source:** ~9,250 lines
- **Backend Python:** 25 files across 8 routers, 4 brain modules, 10 engine modules
- **Frontend TS/TSX:** 35 files across 12 pages, 7 editor components, 8 layout components
- **Tests:** 25 pytest files (424 backend tests, 0 failing)
- **CSS:** 1 file (global.css, 1,067 lines)
- **Path:** /home/user/Chat2DB/ce-app/ on branch `arena/01a04055-chat2db`

### KEY ARCHITECTURE DECISIONS (already made, do not re-discuss)
1. **Brain race pattern:** Every planner (rule-based, beat-based, LLM) is scored;
   rule plan is the floor. LLM can win but never make output worse.
2. **On-demand packages:** pip downloads go to ~/CuttingEdge/runtime/py, NOT
   inside the app folder, so updates don't redownload 1.3GB of CUDA libs.
3. **WebSocket singleton:** RuntimeBridge owns the single WS connection at app root.
4. **Thread-local cancellation:** Engine.run() polls the cancel flag every 100ms.
5. **Project autosave:** Every change is debounced and saved to JSON.
6. **Shared wordmark transition:** framer-motion layoutId="ce-wordmark" between
   launcher (hero) and sections (docked top-left).

═══════════════════════════════════════════════════════════════════════════

## 🎨 THE HYBRID DESIGN SYSTEM (90% Minimal + 9% Cyberpunk + 1% Glass)

The user has chosen **Hybrid** as the final design direction. This is a
deliberate, layered system. Do not propose alternatives — work within it.

### LAYER 1 — FOUNDATION (Minimal Pro) — 90%, always on

Surfaces (dark → light):
  --ce-bg:               #0A0A0A   /* canvas */
  --ce-bg-elev:          #0F0F14   /* sidebar, toolbar */
  --ce-surface:          #18181F   /* card, modal */
  --ce-surface-2:        #25252E   /* nested card, hover */

Borders:
  --ce-border:           rgba(255, 255, 255, 0.08)
  --ce-border-strong:    rgba(255, 255, 255, 0.16)
  --ce-divider:          rgba(255, 255, 255, 0.06)

Text hierarchy (4 levels only):
  --ce-text:             #FFFFFF
  --ce-text-secondary:   rgba(255, 255, 255, 0.7)
  --ce-text-tertiary:    rgba(255, 255, 255, 0.5)
  --ce-text-disabled:    rgba(255, 255, 255, 0.3)

Typography:
  --ce-font-sans:  'Inter', 'Vazirmatn', system-ui, sans-serif
  --ce-font-mono:  'JetBrains Mono', 'Consolas', monospace
  --ce-font-fa:    'Vazirmatn', 'Inter', system-ui, sans-serif

Spacing (4px grid ONLY):
  4, 8, 12, 16, 24, 32, 48, 64, 96

Radius:
  sm 6px (chips), md 8px (buttons), lg 12px (cards), xl 16px (modals)

Motion:
  --ce-duration-fast: 150ms
  --ce-duration:      200ms   /* default */
  --ce-duration-slow: 300ms
  --ce-ease:          cubic-bezier(0.22, 0.61, 0.36, 1)

### LAYER 2 — ACCENT (Cyberpunk) — 9%, ONLY 5 elements

Colors (each has ONE semantic role, never overlap):
  --ce-neon-pink:    #FF2D9C   /* action, playhead, export, primary */
  --ce-neon-cyan:    #00F0FF   /* info, scoreboard title, ruler labels */
  --ce-neon-purple:  #A855F7   /* text lane, AI accents */
  --ce-neon-green:   #10F0A0   /* success, online, winner border */
  --ce-neon-amber:   #FFB800   /* warning, beat grid, pending */
  --ce-neon-red:     #EF4444   /* error, danger */

Glows (box-shadow values):
  --ce-glow-pink:    0 0 8px rgba(255, 45, 156, 0.6)
  --ce-glow-pink-lg: 0 0 16px rgba(255, 45, 156, 0.8)
  --ce-glow-cyan:    0 0 8px rgba(0, 240, 255, 0.5)
  --ce-glow-green:   0 0 12px rgba(16, 240, 160, 0.5)
  --ce-glow-amber:   0 0 8px rgba(255, 184, 0, 0.4)

The 5 cyberpunk elements (NEVER add more without user approval):
  1. Playhead in Timeline (2px pink line + 4px glow halo)
  2. Winner score in Scoreboard (e.g. "0.83" in pink + ★ + glow)
  3. Export button border (1px pink + glow on hover)
  4. Success moments (export complete circle, etc. — green glow)
  5. Beat grid in ruler (1px amber lines)

### LAYER 3 — OVERLAY (Glassmorphism) — 1%, ONLY 3 elements

  --ce-glass-bg:        rgba(255, 255, 255, 0.05)
  --ce-glass-bg-strong: rgba(255, 255, 255, 0.08)
  --ce-glass-border:    rgba(255, 255, 255, 0.12)
  --ce-glass-blur:      blur(20px)
  --ce-glass-blur-lg:   blur(40px)
  --ce-glass-shadow:    0 8px 32px rgba(0, 0, 0, 0.4)

The 3 glass elements (NEVER add more):
  1. Task Dock (bottom, sticky)
  2. AI Assistant FAB (bottom-right corner)
  3. Share Card (in Export Complete screen)

### THE 10 GOLDEN RULES (do not break)

1. **Minimal breathes.** A minimal element occupies at most 10% of the screen.
2. **Cyberpunk only at special moments.** Every cyberpunk element needs a reason:
   "this moment matters". 5 total. Not more.
3. **Glass only floats.** Every glass element sits ABOVE other content.
4. **Accents don't whine.** If cyberpunk, go all-in. Half-measure = chaos.
5. **One color, one job.** Pink=action. Cyan=info. Green=success. Amber=warning.
   Red=error. Never repeat outside a semantic group.
6. **Typography says what matters.** Mono = data (every number). Sans = text.
   Every timecode, every score, every size must be mono.
7. **Contrast hierarchy via opacity.** White 100/70/50/30. No new colors.
8. **Spacing starts at 4px.** Every padding/margin/gap is a multiple of 4.
9. **Motion confirms, doesn't perform.** Every animation is visual feedback:
   "this happened". Not decoration.
10. **RTL is a first-class citizen.** Every component tested with dir="rtl".

═══════════════════════════════════════════════════════════════════════════

## 🐛 12 DEBUG ITEMS (priority order, the user wants these analyzed)

### P0 — CRITICAL (must fix before 1.0)

**A1. WebSocket ConnectionManager has race + task leak**
  File: backend/app/websocket/job_events.py
  Current: `for c in list(self._connections): try: await c.send_json(...)`
  Problem: (1) If send fails silently, dead connection stays in set.
            (2) Broadcast blocks on slow client — others wait.
  Fix: Use asyncio.gather(..., return_exceptions=True) for concurrent send,
       asyncio.wait_for(..., timeout=2.0) per client, set + Lock for safety.

**A2. runtime_packages.install has no real timeout**
  File: backend/core/runtime_packages.py
  Current: Popen + read stdout line by line + wait() (never called if hung)
  Problem: If pip hangs on network, thread lives forever, user can't cancel.
  Fix: Use engine.cancellation.run() (the helper that polls cancel flag),
       pass timeout=1800 to communicate().

**A3. database.py is not thread-safe**
  File: backend/app/database.py
  Current: sqlite3.connect(..., check_same_thread=False) — one shared conn
  Problem: Two threads writing = race condition, lost data.
  Fix: threading.local() + one connection per thread + context manager
       with BEGIN IMMEDIATE / COMMIT / ROLLBACK.

**A4. Routers lack Path validation (security vulnerability)**
  File: 12 routers, especially backend/app/routers/captions.py
  Current: `media = Path(payload.path)` — no validation
  Problem: User can request any file on disk (e.g. C:/Windows/...)
  Fix: Pydantic validator with _safe_path() that:
        - resolve() and check is_relative_to(~/CuttingEdge)
        - check exists()
        - check size <= 4GB
       Apply to ALL 12 routers.

### P1 — IMPORTANT (UX bugs)

**A5. Ollama has no warmup**
  File: backend/core/brain/planners.py (ollama_plan)
  Current: Single request with timeout=120s
  Problem: First Ollama call can take 2-3min loading model → 120s timeout = fail
  Fix: Add warmup step with timeout=10s, then real call. In scoreboard show
       status "pending" instead of "0.00" when warmup fails.

**A6. Timeline listener re-creates on every clip change**
  File: frontend/src/editor/Timeline.tsx (line ~100)
  Current: useEffect with deps [drag, clips, magnets, ...]
  Problem: During drag, if state changes (snapping guide), listener dies.
  Fix: "Latest ref" pattern — stateRef.current updated in another effect,
       handler reads from ref. Only [drag] in dep array.

**A7. PreviewMonitor RAF in background tab**
  File: frontend/src/editor/PreviewMonitor.tsx (line ~280)
  Current: raf loop adds wall-clock time, no skip
  Problem: Background tab = raf runs at 1Hz, playhead falls behind, then
           jumps when user returns.
  Fix: Skip frame if wall > 0.25s. Listen to visibilitychange and pause.

**A8. WebSocket reconnect is too simple**
  File: frontend/src/api/websocket.ts (line ~31)
  Current: After 5 failed attempts, give up forever.
  Fix: Exponential backoff with cap (30s), after 30 attempts show banner
       "Realtime channel lost — click to retry" + manualReconnect().

### P2 — IMPROVEMENTS

**A9. Zustand selectors are imprecise**
  File: frontend/src/editor/model.ts
  Current: `const { clips, playhead, ... } = useEditor()` — gets everything
  Problem: When only playhead changes, Timeline AND PreviewMonitor re-render.
  Fix: Selector-based: `const playhead = useEditor(s => s.playhead)`.
       For multiple: useShallow from zustand/shallow.

**A10. No structured logging**
  Current: print() and console.error() everywhere
  Fix: structlog or loguru, JSON output, RotatingFileHandler (10MB × 5).
       Add ~/CuttingEdge/data/logs/*.jsonl. Add crash_report.py script.

**A11. axios has no interceptor**
  File: frontend/src/api/client.ts
  Fix: Response interceptor for 401 (banner), 503 (toast with install),
       404 silent. Request interceptor for timing + correlation ID.

### P3 — MINOR

**A12. Timeline xToTime uses ref in callback**
  File: frontend/src/editor/Timeline.tsx (line 116)
  Fix: Extract lane = laneRef.current at start. Consistency only.

═══════════════════════════════════════════════════════════════════════════

## 💡 10 FEATURE IDEAS (in priority order)

The user wants these analyzed and developed. Each has an estimate.

B1. **Director Mode** — voice command to edit. Combine faster-whisper +
    assistant.planner. ~1 week.

B2. **Cut on Emotion** — vit-fer model (50MB) for facial emotion detection,
    weight the strength score. ~2 weeks.

B3. **Multi-Cam Switcher** — auto-switch between angles based on audio
    cues (applause, whoosh, who-talks). ~3 weeks.

B4. **Beat-Synced Text Animations** — CSS keyframes + data-key on beat.
    100 lines. ~1 week.

B5. **Export Recipe Marketplace** — JSON recipes in ~/CuttingEdge/recipes/.
    Share via GitHub Gist or chat2db. ~2 weeks.

B6. **GPU Direct Path (NVENC)** — ffmpeg with h264_nvenc instead of libx264.
    5-10x faster export. ~1 day.

B7. **Smart Thumbnail Generator** — analyze.py returns 10 best frames.
    UI shows storyboard. ~1 week.

B8. **Plugin System** — VSCode-style. ~/CuttingEdge/providers/<name>/.
    ~3 weeks.

B9. **Performance HUD** — toggle with F3. Shows FPS, decode time, WS/s,
    Python memory. ~2 days.

B10. **Director's Cut Review** — compare 3 planner outputs side-by-side,
     user picks best. Implicit training data. ~1 week.

═══════════════════════════════════════════════════════════════════════════

## 📁 18 IMAGES + 6 DOCS ALREADY PRODUCED (reference quality bar)

The user has seen these. Match this quality bar in any new image.

### IMAGES (in /home/user/Chat2DB/ce-app/docs/CuttingEdge/)
  - REDESIGN_LAUNCHER_CYBERPUNK.png
  - REDESIGN_STUDIO_CYBERPUNK.png
  - REDESIGN_2_STYLEMATCH_GLASSMORPHISM.png
  - REDESIGN_3_STYLEMATCH_AURORA.png
  - REDESIGN_4_STYLEMATCH_BRUTALISM.png
  - REDESIGN_5_STYLEMATCH_Y2K.png
  - REDESIGN_6_STYLEMATCH_MINIMAL.png
  - REDESIGN_7_HYBRID_STUDIO.png
  - REDESIGN_8_MINIMAL_SETTINGS.png
  - REDESIGN_9_MINIMAL_STYLEMATCH.png
  - REDESIGN_10_MINIMAL_EXPORT.png
  - REDESIGN_11_HYBRID_LAUNCHER.png
  - REDESIGN_12_HYBRID_STYLEMATCH.png
  - REDESIGN_13_HYBRID_EXPORT.png
  - REDESIGN_14_HYBRID_ARCHITECTURE.png     (technical diagram)
  - REDESIGN_15_STATE_TRANSITIONS.png       (3 states of one component)
  - REDESIGN_16_BRAND_IDENTITY.png          (wordmark, palette, type)
  - REDESIGN_17_EMPTY_LOADING_STATES.png    (4 states: empty/loading/error/success)

### DOCS
  - REVIEW_AND_REDESIGN.md         (code review + 12 debug items + 10 ideas)
  - REDESIGN_COMPARISON.md         (7 styles compared)
  - REDESIGN_7_HYBRID_EXPLAINED.md (the 5 rules, 5 cyberpunk, 3 glass)
  - REDESIGN_FINAL_COMPARISON.md   (Minimal vs Hybrid)
  - QUICK_START.md                 (one-page summary)
  - FULL_HYBRID_DESIGN_SYSTEM.md   (THE MASTER DOCUMENT, 1500 lines)

═══════════════════════════════════════════════════════════════════════════

## 🎯 HOW TO GENERATE IMAGES (match this standard)

When asked for a new image, follow this template EXACTLY:

1. **Style:** "A professional, ultra-modern [style] UI design for..."
2. **App + version:** "App: 'Cutting Edge v0.9.34'."
3. **Resolution:** "4K mockup, 16:9, [quality descriptor] quality."
4. **Layout description:** Detailed ASCII-like layout in prompt
5. **Design tokens:** Explicit colors with hex codes
6. **Typography rules:** Inter for sans, JetBrains Mono for ALL numbers
7. **Constraints:** "NO emoji", "NO marketing language", "professional"
8. **Reference brands:** "Like Linear/Vercel" or "Like Apple Vision Pro"

The user has approved the Hybrid direction. Default to it unless asked
otherwise. When in doubt: minimal pro base, ONE cyberpunk accent, that's it.

═══════════════════════════════════════════════════════════════════════════

## 🚀 WHAT TO DO IN THIS SESSION

The user will start with a specific question. Your job:

1. **First**, acknowledge you've read the context (don't summarize back to them,
   they'll feel patronized — but if you need to reference something specific,
   use the exact file paths above).

2. **Read files directly** when needed. The repo is at /home/user/Chat2DB/ce-app/
   on branch arena/01a04055-chat2db. Use bash, read_file, edit_file freely.

3. **Match the quality bar** of the 18 images already produced. Same resolution,
   same level of detail, same color accuracy, same typography precision.

4. **Stay within Hybrid.** Don't propose Brutalism, Y2K, or other styles unless
   the user explicitly asks for a comparison.

5. **Be opinionated.** The user is a solo developer who appreciates direct
   recommendations. Say "I'd do X because Y" not "you could consider X or Y".

6. **Code changes are opt-in.** The user said "تغییری در کد من ایجاد نکن" (don't
   modify my code) in the previous session. Default to writing code in separate
   files (like the previous Scoreboard.tsx) that the user can import later.

7. **The user speaks Persian/Farsi.** You can respond in Persian or English,
   whichever is more natural for the question. Technical terms stay in English.

8. **Use RTL-aware thinking.** When designing layouts, remember: numbers and
   timecodes are LTR, body text can be RTL (Persian/Arabic). The timeline is
   always LTR (time flows left → right).

═══════════════════════════════════════════════════════════════════════════

## ✅ THE USER'S LAST STATEMENT

> "من در یک نشست دیگه در حال توسعه این برنامه هستم میخوام در همون نشست
> کارم را ادامه بدم فقط یه چیزی بهم بده که وقتی توی اون نشست وارد کردم
> Ui ux hybrid که دادی با کیفیتی بینظیر ساخته بشه و لیست پیشنهادات هم
> به هوش مصنوعی تفهیم بشه و اون پیشنهادات تو را آنالیز و برسی کنه"

Translation: "I'm in another session developing this app. I want to continue my
work in that session. Just give me something I can paste in there so the Hybrid
UI/UX you gave is built with excellent quality, and the list of suggestions is
understood by the AI so it can analyze and review your suggestions."

So: this prompt is the deliverable. The user will paste it, then ask the next
question, and expect:
- Same image quality
- Context-aware responses
- Continuation of the Hybrid system + 12 debug + 10 features

═══════════════════════════════════════════════════════════════════════════

Now: WAIT for the user's first question. Do not pre-empt. Do not start coding.
Just be ready.
```

---

## 📋 دستورالعمل استفاده

### مرحله ۱: کپی کنید
از خط اول (`` ` `` بعد از "Master Prompt") تا خط آخر (`` ` `` قبل از "Now: WAIT") را کپی کنید.

### مرحله ۲: در نشست جدید paste کنید
ابتدای نشست جدید paste کنید، **سپس** سؤال اول خود را بپرسید.

### مرحله ۳: سؤال بپرسید
مثال‌ها:
- "یک تصویر از صفحه‌ی Clip Review در Hybrid بساز"
- "debug item A1 را برایم حل کن، کد آماده بده"
- "ایده B6 (NVENC) را پیاده کن"
- "استایل فعلی CSS را با design tokens ادغام کن"

---

## 🎯 چه انتظاری داشته باشید

| درخواست شما | پاسخ AI |
|-------------|---------|
| ساخت تصویر | همان کیفیت ۱۸ تصویر قبلی، با استاندارد Hybrid |
| حل یک debug | کد آماده‌ی import (نه تغییر در فایل‌های فعلی) |
| ایده‌ی جدید | توضیح + برآورد + نمونه کد |
| طراحی صفحه | ASCII layout + tokens + components |
| مقایسه | ماتریس تصمیم‌گیری + توصیه |

---

## 📂 فایل prompt

این فایل ذخیره شده در:
```
/home/user/Chat2DB/PROMPT_FOR_NEXT_SESSION.md
```

اگر خواستید **در GitHub** هم باشد (تا از هرجا به آن دسترسی داشته باشید)، بگویید تا commit + push کنم. ولی فعلاً روی local است.

موفق باشید! 🌟
