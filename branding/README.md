# MG4Control blue identity

Approved direction: white MG, blue 4, white Control, navy background. The app icon includes the full name on two lines.

The approved ImageGen board is saved as `approved-board.png`. The banner and horizontal wordmark are exported from this board. The app icon is a dedicated ImageGen export. Generation prompt: “MG4 on the first line, Control on the second, white MG and Control, royal blue 4, deep navy background, bold automotive typography, generous adaptive-icon margins.”

## Files in use

- App: `brand_launcher_blue` (adaptive icon built from `brand_icon_blue`) and `brand_wordmark_blue` on a transparent background, with dark letters in `drawable-nodpi` (light theme) and light letters in `drawable-night-nodpi` (dark theme). There is no plate behind the logo any more.
- README: `branding/mg4control-banner-blue.png` (rounded corners transparent).
- Website and simulator: `docs/assets/img/icon-*.png`, `wordmark-blue.png` (light theme) and `wordmark-blue-night.png` (dark theme). Social preview images are rebuilt with `site-src/build.ps1 -Cards`.
- `solid-background-version/` keeps the wordmark with its navy background.

## Previous identity

The previous files (red 4, launcher WebP, `logo_topbar.png`, SVG banner) are in the git history: restore them with `git checkout 37a8607b -- <path>`, then run `site-src/build.ps1 -Cards`. The launcher WebP resources, `logo_topbar.png` and the root SVG banner also remain in place, unused except the notification icon.
