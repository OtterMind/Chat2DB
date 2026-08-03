# macOS Keychain HOME isolation fix

Approved scope for the local macOS preview repair:

1. Keep `CODEX_HOME` pointed at the supervisor-owned isolated configuration directory.
2. Preserve the real OS-user `HOME` in the sanitized app-server environment so
   Security.framework can resolve the user's default Keychain.
3. Keep the credential store pinned to `keyring`; do not permit `auth.json` or any
   file credential fallback.
4. Change the macOS Keyring preflight from an executable-presence check to a
   bounded, output-discarding `security default-keychain -d user` lookup that
   fails closed on nonzero exit, timeout, interruption, or launch failure.
5. Prove the old behavior fails with regression tests, rebuild the Community
   renderer and Java payload, and replace only the local Subscription Preview App.

The previous OAuth callback did not persist credentials. A fresh manual login is
required after installation to validate the provider-controlled write/read flow.
