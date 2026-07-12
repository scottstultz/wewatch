#!/usr/bin/env bash
#
# Regenerates the PWA PNG icons in public/ from the SVG sources.
#
# The PNGs are committed, so this only needs re-running when a source SVG changes.
# Rendering goes through headless Chrome because the SVGs are the authoritative art and
# nothing else on a stock macOS box rasterises SVG (no ImageMagick / rsvg-convert / cairosvg).
#
# Usage: ./scripts/render-icons.sh
set -euo pipefail

CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "Chrome not found at: $CHROME (override with \$CHROME)" >&2; exit 1; }

cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# The SVG is inlined into a page rather than loaded via <img src="file://...">, which Chrome
# blocks. CSS beats the SVG's width/height presentation attributes, so the viewBox scales to
# whatever size we ask for.
render() {
  local src="$1" size="$2" out="$3"
  {
    printf '<style>html,body{margin:0;padding:0;background:transparent}svg{display:block;width:%spx;height:%spx}</style>\n' "$size" "$size"
    cat "$src"
  } > "$tmp/page.html"

  "$CHROME" --headless --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=1 --default-background-color=00000000 \
    --window-size="$size,$size" --screenshot="$out" "file://$tmp/page.html" 2>/dev/null

  echo "  $out (${size}x${size})"
}

echo "Rendering icons..."
render public/favicon.svg          192 public/icon-192.png
render public/favicon.svg          512 public/icon-512.png
render scripts/icon-maskable.svg   192 public/icon-maskable-192.png
render scripts/icon-maskable.svg   512 public/icon-maskable-512.png
render scripts/icon-apple.svg      180 public/apple-touch-icon.png
echo "Done."
