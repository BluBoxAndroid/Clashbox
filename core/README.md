# core/

Intentionally empty.

Upstream FlClash builds its desktop Go core from this directory through the
`setup` package's build hook. Clashbox is Android-only and does not ship a
desktop core: its core is the stock `libbox` AAR built from the official
sing-box sources (see the top-level README). The hook's `build_assets`
define is disabled in `pubspec.yaml`, and this directory exists only so the
hook's repository-layout check finds the tree it expects before skipping.
