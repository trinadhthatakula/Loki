## Highlights

**Loki grants itself `READ_LOGS` now.** If you have root or Shizuku, that is the whole setup — no
terminal, no ADB cable. Loki asks the device which of its own permissions a privileged shell is
allowed to change, takes them, and gets on with the job. Under root the grant is silent and Loki
restarts itself; under Shizuku it asks first, because taking the permission closes the app and
Shizuku cannot put it back.

**Saved logs are a place now, not a list.** A viewer with level filters, search and export; a file
browser with bulk delete and zip-share; and a DocumentsProvider, so Loki's logs turn up in the system
file picker for any other app.

**There is a Settings screen**, a real theme system — light, dark, AMOLED, Material You — and Thor's
Asgardian palette, so the two apps read as siblings.

## Added

- Self-granting of `READ_LOGS`, and of every other permission a privileged shell can change, from
  root or Shizuku at launch — with a status row and a "Tap to re-check" in Settings (#67)
- Settings screen: privilege status, appearance, saved-log housekeeping
- Saved-log viewer: level filters, search, export
- Logs explorer: browse, bulk-delete and zip-share saved logs without leaving the app
- A `DocumentsProvider` for saved logs, so they appear in the system file picker
- Persisted theme system: light / dark / AMOLED, dynamic colour, bundled fonts
- The Asgardian palette and Outfit's static weights

## Fixed

Security and privacy:

- Auto Backup is off, so a captured log cannot leave the device through a cloud backup
- The FileProvider no longer exposes a filesystem-wide root path
- Every document row the provider returns is confined to the logs directory

Capture:

- The root shell's exit code is checked before Loki claims it is running as root
- Logcat is filtered by uid rather than pid — an app cannot see another app's pid
- Captures are written straight to their destination
- The Shizuku binder listener is registered sticky, so the grant actually runs
- The log level is read positionally from threadtime lines
- Granting `READ_LOGS` no longer kills a capture that is already running; the grant is refused
  instead, and the button is still there when the capture stops

Viewer, lists and navigation:

- Dragging off the top of the log no longer stops auto-scroll dead
- Drag-to-select auto-scroll is gated on a drag, not a press
- A cancelled read is no longer shown as an error
- The app list stopped leaking log tails and re-sorting on every state change
- The live-log sheet opens fully expanded
- The directory watch survives a clear-all
- A duplicate push of the route already on top is ignored
- The explorer's long-press label announced the opposite action
- AMOLED section cards stay distinguishable from the page behind them
- A preference that cannot be persisted no longer crashes the app

## Changed

- The home screen is Navigation 3 with real routes, replacing the pager. The log viewer and the
  explorer are routes now, so they survive process death and cannot be swiped out from under an open
  capture.
- Root goes through Odin instead of libsu, and off the main thread.
- The theme picker is a connected button group rather than a bottom sheet.

## Internal

- Koin DSL wiring for the new model classes, and one DataStore delegate shared by `ThemeManager` and
  `SelfGrantStore` — a second instance over the same file throws at runtime
- CodeQL sees Kotlin now, and signing credentials are treated as one all-or-none set
- Dependency and Gradle toolchain updates

## Known limits

- The root path — the silent grant, the armed relaunch, root-first capture — is reasoned from the
  platform's sources and has **not** been exercised on a rooted device. Shizuku is what was actually
  tested. If the root path is wrong, the grant does not happen and Loki behaves as it did before.
- There is no way to hand a self-granted permission back from inside Loki. Use the system Settings,
  or `pm revoke` from a shell.
