# Clean-install check — the filmed run (1.0 gate)

The one 1.0 item a sandbox cannot do: watch the app be born on a real Windows
machine. This checklist is the script for that film. Record the screen from
step 0; every step must succeed **without** opening a console or editing a
file. Persian first — the owner's machine speaks fa.

## سناریوی فیلم‌برداری (فارسی)

0. ضبط صفحه را روشن کنید.
1. **حذف نصب قبلی** از Settings → Apps (پوشه‌ی `~/CuttingEdge` شامل runtime و
   پروژه‌ها باید بماند؛ فقط برنامه می‌رود).
2. نصب `Cutting-Edge-Setup-0.9.32.exe`؛ پنجره‌ی NSIS بدون خطا تمام شود.
3. اجرای اولین: صفحه‌ی اصلی با کاشی‌های چپ‌چین، وردماک وسط، **بدون هیچ خطایی**؛
   بنر تور اولین‌بار دیده شود.
4. کارت آپدیت: «به‌روز هستید» (یا پچ تفاضلی زیر ۵۰MB بگیرد و بدون دانلود کامل
   نصب شود — عدد دانلود را در فیلم نشان دهید).
5. **ویدیوی نو**: یک فایل ویدیویی با صدا → picker یک‌بار باز شود (باگ قدیمی
   دوتایی‌بودن نباید تکرار شود) → کلیپ روی تایم‌لاین با فیلم‌استریپ و موج صدا.
6. درگ موس روی نوارها و متن‌ها: **هیچ انتخاب آبی** دیده نشود.
7. Ctrl+K: پالت فرمان باز شود؛ «زیرنویس» را اجرا کند؛ کپشن‌ها با کارائوکه بنشینند.
8. استایل مچ: الگو → فوتیج → مغز روی صفحه سوال/جواب با عدد بدهد → یک گزینه →
   تدوین ساخته و در ادیتور باز شود.
9. خروجی: Export → فایل p9/1080p بدون خطا ذخیره شود و پخش شود.
10. Diagnostics: پوشه‌ی لاگ باز شود؛ `/api/health` سبز؛ بدون خطای قرمز.
11. بستن و بازکردن اپ: پروژه‌ی autosave پیشنهاد شود و بازیابی شود.

هر مرحله که لرزید، همان باگِgate است — شماره بزنید و برگردانید به
`arena/01a032fb-chat2db`.

## The filmed script (English, for the professors)

0. Start the screen capture.
1. Uninstall the previous version (Apps & features). `~/CuttingEdge` (runtime,
   projects) must survive; only the app folder goes.
2. Install `Cutting-Edge-Setup-0.9.32.exe`; NSIS finishes silently.
3. First launch: home renders (left-packed tiles, centred wordmark), tour
   banner once, **zero visible errors**.
4. Update card says up-to-date — or takes a differential patch and shows a
   download far below the full installer size (show the number on film).
5. "New video": exactly **one** file picker opens (the old double-Import bug
   must not reappear); clip lands with film strip + waveform.
6. Drag across rails/text: **no blue selection** anywhere.
7. Ctrl+K opens the palette; run "captions"; karaoke captions land.
8. Style Match: reference → footage → the brain's on-screen Q&A with numbers →
   pick an option → the edit opens in the editor.
9. Export a 9:16 1080p file; it plays.
10. Diagnostics: log folder opens; health green; no red lines.
11. Restart: the unfinished autosave is offered and restores.

A stumble at any step is the gate doing its job — file it, fix it on the
branch, re-film that step only.
