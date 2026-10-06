#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

LOGO=logo.png
RES=app/src/main/res
BG="#3A3A3A"

if [[ ! -f $LOGO ]]; then
  echo "error: $LOGO not found in $(pwd)" >&2
  exit 1
fi

MAGICK=magick
if ! command -v magick >/dev/null 2>&1; then
  if command -v convert >/dev/null 2>&1; then
    MAGICK=convert
  else
    echo "error: ImageMagick is not installed (need 'magick' or 'convert')" >&2
    exit 1
  fi
fi

echo "Using $MAGICK with $LOGO"

# Legacy launcher icons (API 25 and below): logo covers the whole square (crop-to-fill)
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  n=${d%%:*}; s=${d##*:}
  "$MAGICK" "$LOGO" -resize ${s}x${s}^ -background "$BG" -gravity center -extent ${s}x${s} \
    "$RES/mipmap-$n/ic_launcher.webp"
  cp "$RES/mipmap-$n/ic_launcher.webp" "$RES/mipmap-$n/ic_launcher_round.webp"
  echo "  mipmap-$n/ic_launcher{,_round}.webp (${s}x${s})"
done

# Adaptive icon foreground layers (API 26+): 108dp layer, logo covers the central
# 72dp = the entire visible area for any launcher mask (circle/squircle/square)
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  n=${d%%:*}; s=${d##*:}
  inner=$((s * 72 / 108))
  "$MAGICK" -size ${s}x${s} xc:"$BG" \
    \( "$LOGO" -resize ${inner}x${inner}^ -background "$BG" -gravity center -extent ${inner}x${inner} \) \
    -gravity center -composite \
    "$RES/mipmap-$n/ic_launcher_foreground.webp"
  echo "  mipmap-$n/ic_launcher_foreground.webp (${s}x${s}, logo ${inner}px)"
done

# The default template vector drawables are no longer referenced
rm -f "$RES/drawable/ic_launcher_background.xml" "$RES/drawable/ic_launcher_foreground.xml"

echo "Done. Rebuild and reinstall the app to see the new icon."
