#!/usr/bin/env python3
"""Shrink the screenshots of scenario.sh into ../images/<lang>/: python3 process.py <outdir> <lang>

Screenshots are 1080x2400 from a Pixel 6 emulator. They are halved and reduced to 128 colors, and
01b_extra_keys.png is cut from the extra keys of 01_terminal_tabs.png at full size.
"""
import glob, os, sys
from PIL import Image

src, lang = sys.argv[1], sys.argv[2]
dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "images", lang)
os.makedirs(dst, exist_ok=True)
for f in sorted(glob.glob(os.path.join(src, "*.png"))):
    name = os.path.basename(f)
    im = Image.open(f).convert("RGB")
    im.resize((540, 1200), Image.LANCZOS).quantize(colors=128, dither=Image.Dither.NONE) \
        .save(os.path.join(dst, name), optimize=True)
    if name.startswith("01_"):
        im.crop((0, 2070, 1080, 2290)).quantize(colors=64, dither=Image.Dither.NONE) \
            .save(os.path.join(dst, "01b_extra_keys.png"), optimize=True)
