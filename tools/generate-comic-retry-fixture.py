"""Recreate the reported lettering without using any original manhwa artwork."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
image = Image.new('RGB', (832, 1016), '#45596a')
draw = ImageDraw.Draw(image)
font_path = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
large, medium, label = (ImageFont.truetype(font_path, size) for size in (37, 27, 24))
art = [(39, 65), (243, 138), (79, 298), (231, 240)]
draw.polygon(art, fill='#947182', outline='#292d3f', width=5)
draw.line([(608, 319), (780, 387), (606, 535), (783, 525)], fill='#b88481', width=12)
for bounds in ((302, 45, 790, 278), (44, 329, 557, 556), (36, 594, 471, 766), (532, 600, 801, 736)):
    draw.ellipse(bounds, fill='#fffcf5', outline='#222332', width=5)
draw.rounded_rectangle((20, 818, 812, 969), radius=20, fill='#faf3e3', outline='#222332', width=5)
for position, text, font in (
    ((546, 129), 'E-RANK', large),
    ((546, 179), 'HUNTER,', large),
    ((300, 413), 'THE HUNTER', large),
    ((300, 466), 'GUILD’S', large),
    ((253, 676), 'THE GATE IS CLOSED.', medium),
    ((666, 666), 'KEEP MOVING.', label),
    ((124, 892), '$7.25', label),
    ((417, 892), 'https://example.com', label),
    ((707, 892), 'REF42', label),
):
    draw.text(position, text, fill='#171821', font=font, anchor='mm')
image.save(root / 'app/src/androidTest/assets/en-comic-retry.png', optimize=True)
