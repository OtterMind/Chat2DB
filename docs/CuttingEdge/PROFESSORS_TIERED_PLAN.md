# برنامه‌ی Tier‌بندی‌شده‌ی بازخورد دو استاد — v0.9.39 → 1.0

> **ورودی:** بازخورد استاد ۱ (شکاف‌ها + ایده‌ها + جدول اولویت) و استاد ۲ (لایه‌های P0–P3 +
> کاتالوگ اوپن‌سورس). **قید سرسخت:** هیچ آیتمی نباید از سیستم طراحی Hybrid فاصله بگیرد
> (توکن‌های `design-tokens.css` دست‌نخورده؛ شمار «۵ سایبرپانک / ۳ شیشه‌ای» حفظ؛ هر کارت
> جدید فقط توکن خنثی + لهجه‌های موجود).
>
> **ستون «وضعیت ما»** از روی کد شاخه‌ی فعلی پر شده، نه از روی ادعای اساتید.
> **ستون «لایسنس»** برای کتابخانه‌های محتملِ درون‌فرآیندی، مستقیم از API گیت‌هاب راستی‌آزمایی
> شد (۲۰۲۶-۰۸-۲۷).

---

## ۰) ذهنیت مشترک دو استاد (آن‌چه هر دو می‌خواهند)

هر دو، مستقل به هم، روی همین چهار ستون فشار آورده‌اند — یعنی اولویت واقعی این‌جاست:

1. **ویرایش متن‌محور (Transcript↔Timeline)** — هر دو آن را بزرگ‌ترین شکاف دانستند.
2. **Jump-cut / حذف Filler و سکوت** با پیش‌نمایش و undo.
3. **امتیاز قلاب/وایرالِ قابل مشاهده + توضیح‌پذیری برش** («چرا این برش؟»).
4. **خروجی/انتشار اجتماعی + چند کلیپ از یک منبع** (Batch/Hook Lab).

استاد ۲ یک لایه‌ی تمایز اضافه می‌گذارد: **Sports/Gym Brain** (والیبال/بدنسازی) و
**MCP/agent-native**. استاد ۱ بیشتر روی «featureهای استاندارد صنعت» (Descript/OpusClip) است.

---

## ۱) راستی‌آزمایی لایسنس (اندازه‌گیری‌شده، نه نقل‌قول)

| کتابخانه | لایسنس واقعی (API) | نتیجه برای ما |
|---|---|---|
| faster-whisper | MIT | ✅ درون‌فرآیندی (داریم) |
| whisperX | BSD-2 | ✅ درون‌فرآیندی (استاد ۱ گفت BSD-4؛ هم‌خانواده‌ی permissive) |
| stable-ts | MIT | ✅ |
| librosa | ISC | ✅ on-demand (داریم) |
| demucs | MIT | ✅ on-demand (داریم) |
| auto-editor | Unlicense | ✅ منطق را re-implement می‌کنیم، نه vendoring |
| OpenTimelineIO | Apache-2.0 | ✅ (داریم interchange) |
| CutScript | MIT | ✅ الگوی UX |
| kinocut | Apache-2.0 | ✅ الگوی MCP |
| chopify | MIT | ✅ الگو |
| **rescript** | **NOASSERTION** | ⚠️ فقط الهام UI — قابل import نیست |
| **whisper-timestamped** | **AGPL-3.0** | ⚠️ فقط برون‌فرآیندی |
| supoclip / vibeclip / OpenChatCut / ScrAIbe | AGPL/GPL | ⚠️ فقط plugin / خواندن |
| pyannote.audio | MIT (مدل‌ها gated) | ⚠️ on-demand + HF token + رضایت کاربر |

---

## ۲) فهرست یکپارچه‌ی Tier‌بندی‌شده

### Tier 0 — گیت‌های الزامی ۱.۰ (هر دو استاد؛ بدون این‌ها «محصول» کامل نیست)
| آیتم | منبع | وضعیت ما | اقدام |
|---|---|---|---|
| فیلم نصب تمیز ویندوز (۱۵–۲۰دقیقه + Doctor سبز) | هر دو | چک‌لیست آماده | نیاز به دستگاه مالک |
| چسباندن workflow رسمی | هر دو | `ce-app/ci/ce-workflow.yml` آماده | paste دستی توسط مالک |
| عدد واقعی NVENC (x264 vs NVENC: زمان + VMAF) | استاد ۲ | gpu.py دارد | نیاز به GPU واقعی |

### Tier 1 — P0 بیشترین ROI (هم‌راستای هر دو؛ بیش‌تر زیرساخت موجود)
| آیتم | استاد | وضعیت ما | لایسنس/وابستگی | یادداشت طراحی |
|---|---|---|---|---|
| **Transcript Editor ↔ Timeline** (کلیک=seek، حذف انتخاب=ripple، حذف filler) | ۱+۲ | کلمات+زمان+رنگ اعتماد داریم؛ UI دو‌طرفه جدید | بدون کتابخانه‌ی جدید | کارت جدید با توکن خنثی |
| **One-click Jump-cut** (سکوت+filler، آستانه تنظیم، preview+undo، duck حفظ) | ۱+۲ | «Remove silence» داریم؛ لایه‌ی filler/UX جدید | auto-editor=Unlicense (re-impl) | مودال Hybrid |
| **Hook Score badge + Emotional Arc chart** | ۱+۲ | objective + cueهای احساس (B2) داریم؛ نمایش جدید | recharts **از قبل shipped** → بدون وابستگی تازه | چارت با نئون‌های موجود |
| **ترجمه‌ی یک‌کلیک زیرنویس** | ۱ | endpoint `translate` داریم؛ یک‌کلیک UI + task=translate | faster-whisper MIT | دکمه در پنل زیرنویس |

### Tier 2 — P1 تمایز (جایی که «واوز» می‌آید)
| آیتم | استاد | وضعیت ما | لایسنس | 
|---|---|---|---|
| **Batch Clips Board** (کارت کلیپ با score+دلیل+trim+export گروهی) | ۱+۲ | highlight+recipes داریم؛ board جدید | — |
| **Hook Lab** (۵ واریانت ۰–۳ث + A/B) | ۲ | planner+race داریم؛ واریانت‌ساز جدید | — |
| **Sports/Gym Brain** (spike/rep markers، slow-mo روی contact، multicam روی dig) | ۲ | pose+crowd+whoosh+multicam داریم؛ markers جدید | MediaPipe Apache ✅ |
| **Voice Isolation UI** (جداسازی صدا/موزیک) | ۱ | demucs on-demand داریم؛ UI جدید | MIT ✅ |
| **Export Pack** (MP4+SRT/ASS+thumb+description.md+OTIO+meta.json) | ۲ | صف خروجی+interchange داریم؛ بسته جدید | OTIO Apache ✅ |
| **Cut Inspector** («چرا این برش؟» از ۱۰ ترم objective) | ۲ | ترم‌ها داریم؛ UI جدید | — |

### Tier 3 — P2 agent-native / خلاقانه
| آیتم | استاد | وضعیت ما | لایسنس |
|---|---|---|---|
| **MCP Server محلی** روی FastAPI (map کردن ۱۷ tool مغز) | ۲ | editor_brain آماده | kinocut Apache الگو |
| **ویرایش با دستور زبانی** (NL→action JSON) | ۱ | Ollama+brain داریم | — |
| **Intensity Slider «Make it more TikTok»** + dry-run | ۲ | وزن‌های objective داریم | — |
| **Plan Diff View** (rule vs Ollama vs user، apply per-segment) | ۲ | B10 داریم؛ گسترش | — |
| **Style DNA / Style Memory** عمیق‌تر | ۱+۲ | taste prior داریم | — |

### Tier 4 — P3 رشد / پرریسک (دست‌نخورده مگر با تصمیم صریح)
| آیتم | چرا عقب | 
|---|---|
| تصحیح نگاه (Eye Contact) | مدل با provenance تمیز کم است؛Best-effort optional |
| انتشار مستقیم (آپلود واقعی) | توکن/API؛ فقط metadata+copy+open-browser درون‌برنامه، آپلود=plugin |
| Speaker diarization UI (رنگ گوینده) | pyannote gated؛ on-demand + رضایت |
| Motion tracking متن/استیکر | نیاز به tracker لایسنس‌تمیز؛ بعد از reframe |
| macOS / cloud worker / TTS / bg-remove | اکوسیستم، بعد از ۱.۰ |

---

## ۳) مسیر انتشار پیشنهادی (ادغام هر دو استاد)

| رلیز | محتوا |
|---|---|
| v0.9.39 | Tier 0 (فیلم + workflow + عدد NVENC) |
| v0.9.40 | Transcript↔Timeline + One-click Jump-cut + ترجمه یک‌کلیک |
| v0.9.41 | Hook Score badge + Emotional Arc + Hook Lab + Batch Board |
| v0.9.42 | Sports/Gym markers + Voice Isolation UI |
| v0.9.43 | Cut Inspector + Intensity slider + Export Pack |
| v1.0.0 | MCP local + freeze API + فیلم نهایی |

**قانون هر آیتم (حرف استاد ۲):** هر قابلیت = `test + endpoint + یک کارت UI` تا انضباط فعلی
و انطباق Hybrid خراب نشود؛ هیچ کارت جدیدی glow/شیشه‌ی تازه اضافه نمی‌کند.

---

## ۴) تصمیم‌هایی که از مالک لازم است
1. کدام Tier اول ساخته شود؟ (پیشنهاد: Tier 1 کامل، بعد Tier 2.)
2. آیا MCP (لایه agent) جزء «نمره» است یا بعد از ۱.۰؟
3. گیت‌های Tier 0 (فیلم/GPU) چه زمانی توسط مالک انجام می‌شود؟
