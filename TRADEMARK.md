# Loki Trademark & Branding Policy

**Copyright © 2025–2026 Trinadh Thatakula.**

Loki's source code is licensed under [`GPL-3.0-or-later`](LICENSE). This policy covers what the
GPL deliberately does **not**: the project's **name, logo, and icon** — its brand identity.

The point of having this written down is not to restrict your GPL freedoms — every one of them is
intact, and listed below. It is so that a user who installs something called "Loki" knows where it
came from. An app that reads other applications' logs through a root shell is exactly the kind of
app where a user needs to be able to tell the original from a repackaged build.

## What this policy protects

The following are trademarks and brand assets of **Trinadh Thatakula** ("the maintainer") and are
**not** licensed under the GPL:

- The name **"Loki"** and **"Loki - Logger"** as used to identify this application.
- The **Loki logo and launcher icon**, including `loki_animated`, `loki_black`, `loki_white`,
  `launch` / `launch_round`, `launch-playstore.png`, and all variants.
- The visual identity used to present Loki on app stores, in listing metadata, and within the app's
  own UI.

As expressly permitted by **GPL-3.0 §7(e)**, the license does **not** grant permission to use these
names, trademarks, service marks, or logos.

## What you may do (your GPL freedoms are unaffected)

- Use, study, run, and modify the source for any purpose.
- Distribute your own modified versions — **provided** you comply with the GPL: release the complete
  corresponding source, and preserve the copyright and license notices. Whether adding a
  closed-source component is permitted is a question about the GPL's combined-work rules and its
  exceptions (the System Library exception, additional permissions a copyright holder may grant),
  and it is not one this file can answer for your case. What this project asks for its own builds is
  stated under *[Note on ads, trackers, and proprietary SDKs](#note-on-ads-trackers-and-proprietary-sdks)*
  below. If you are planning to ship something combined, read the licence and take your own advice —
  none of this is legal advice.
- Refer to Loki by name in **truthful, descriptive, non-endorsing** ways — "a fork of Loki", "based
  on Loki", "compatible with Loki's export format".

## What you may NOT do

- Distribute a fork or rebuild **under the name "Loki" / "Loki - Logger"**, or using the Loki
  **logo/icon**. A fork must ship under a **different name and a different icon**.
- Present your build in a way that **implies it is the official Loki**, or that it is endorsed by,
  affiliated with, or maintained by the maintainer.
- Use the Loki name or logo on app-store listings, websites, or marketing for a fork.

Note that the package name `com.valhalla.loki` is an installation identity, not a brand asset — but
publishing a fork under it is worse than a trademark problem: Android treats same-package builds as
updates of one another, so it means a user's install can be silently replaced by code they did not
choose. Change the `applicationId` in your fork.

## A note on log data

Loki captures logcat, which routinely contains access tokens, URLs, account identifiers and other
applications' data. Nothing in the official Loki sends a captured log anywhere the user did not
explicitly choose.

The GPL does not forbid a fork from changing that. This policy does forbid such a build from calling
itself Loki — and the reason is the whole reason the policy exists. A build that exfiltrates logs
while wearing this project's name and icon would be trading on trust it did not earn, at the direct
expense of the people who extended it.

## Note on ads, trackers, and proprietary SDKs

Loki is intentionally **ad-free and tracker-free FOSS**. The GPL does not, by itself, forbid a fork
from adding ads — but two independent rules still bind such a build:

1. **License:** bundling a **proprietary ad SDK** (e.g. Google AdMob / `play-services-ads`) into a
   distributed build creates a combined work that **cannot** satisfy the GPL, and is a **license
   violation** — independent of this trademark policy.
2. **Trademark:** such a build may **not** use the Loki name or icon (this policy), and must still
   publish its complete corresponding source (the GPL).

## Contact

To ask about permitted use, or to report misuse of the Loki name/logo or a GPL violation:

- Repository: <https://github.com/trinadhthatakula/Loki>
- Maintainer: Trinadh Thatakula

The same policy, with the same wording, governs Loki's sibling project
[Thor](https://github.com/trinadhthatakula/Thor).
