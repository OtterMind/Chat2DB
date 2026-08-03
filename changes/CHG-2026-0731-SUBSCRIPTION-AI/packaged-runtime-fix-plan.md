# Packaged runtime manifest and safe startup diagnostics

Approved scope for the local macOS preview refresh:

1. Resolve a relative subscription runtime manifest from the jpackage macOS layout
   (`<App>.app/Contents/MacOS/<launcher>` to `<App>.app/Contents/app/<manifest>`),
   while rejecting traversal outside `Contents/app`.
2. Preserve existing absolute, working-directory, and code-source candidates for
   non-packaged and test launches.
3. Emit only a stable startup stage and safe reason code. Never include exception
   messages, paths, URLs, arguments, stderr, credentials, or provider payloads.
4. Prove the `/` working-directory packaged path and safe-code behavior with focused
   tests, then run the broader subscription regressions.
5. Rebuild, replace, locally sign, and relaunch only
   `/Applications/Chat2DB Subscription Preview.app`; leave the formal Community App
   untouched.
