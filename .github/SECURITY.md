# Security Policy

## Supported versions

Only the latest release of Loki receives security fixes. Please update before reporting.

| Version        | Supported |
|----------------|-----------|
| Latest release | ✅        |
| Older versions | ❌        |

Loki is early-stage software. There are no long-term-support branches and no backports — a fix
ships in the next release from `master`. See [`docs/branching-and-releases.md`](../docs/branching-and-releases.md).

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.**

Report privately with GitHub's **Report a vulnerability** button on the
[Security → Advisories page](https://github.com/trinadhthatakula/Loki/security/advisories/new).
That opens an advisory visible only to you and the maintainer.

Loki's core function is reading **other applications'** logcat output, which needs
`android.permission.READ_LOGS` — a `signature|privileged` permission a normal app cannot be granted.
Loki reaches it through a **root shell** (Odin) or through **Shizuku**. That shapes what a useful
report looks like:

- The **privilege mode** (Root or Shizuku) and the **Loki version**.
- Steps to reproduce, and the **impact** — for example: command injection into the privileged
  shell, log data reaching a destination the user did not choose, another app reading Loki's
  captures, or a privilege escalation beyond what the user granted.
- Any proof of concept, the affected code path if you have it, and the device and Android version.

If your report includes a captured log, **redact it first.** Logcat routinely contains access
tokens, URLs, account identifiers and third parties' personal data, and a private advisory is
private, not encrypted-at-your-threat-model.

### What is in scope

- Anything under `app/src/` — in particular the privileged surface:
  `model/PermissionManager.kt`, `model/LogcatCapture.kt`, and `services/`.
- The release and CI machinery in `.github/`, where a flaw could mean a compromised published APK.
- Loki's handling, storage and export of captured logs.

### What is not

- Loki needing root or Shizuku to work at all. That is the design; `READ_LOGS` is not grantable to
  a normal app.
- A user with a root shell being able to read logs on their own device. That capability is the
  point of the app and predates it.
- Vulnerabilities in Magisk, KernelSU, APatch, Shizuku or Android itself. Please report those
  upstream — though do tell us if Loki uses one of them in a way that makes it worse.
- Findings from an automated scanner with no described impact.

## What to expect

- Acknowledgement as soon as reasonably possible. This is a small, volunteer-maintained project;
  please allow for that.
- An assessment, and if the report is valid, a fix in a subsequent release — with credit, unless
  you would rather stay anonymous.

Please give reasonable time for a fix to ship before public disclosure. Thank you for helping keep
Loki and its users safe.
