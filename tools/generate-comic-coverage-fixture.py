"""Draw original English comic lettering with small type, shaded bubbles and narrow dialogue."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
w, h = 1440, 2016
im = Image.new('RGB', (w, h), '#586374')
d = ImageDraw.Draw(im)
regular = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
bold = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
for y in range(0, h, 288):
    tint = ('#647989', '#374857', '#755c77')[y // 288 % 3]
    d.rectangle((0, y, w - 1, y + 279), fill=tint)
    d.polygon([(375, y + 279), (580, y + 88), (720, y + 240), (905, y + 164), (1230, y + 279)], fill='#303944')
    d.line((10, y + 278, w - 10, y + 278), fill='#20262d', width=5)


def balloon(bounds, lines, size, color='#fffdf5', ink='#12121c', font=bold, leading=None):
    l, t, r, b = bounds
    d.rounded_rectangle(bounds, radius=min((b - t) // 2, (r - l) // 3), fill=color, outline='#1b1921', width=5)
    face = ImageFont.truetype(font, size)
    spacing = leading or (size * 4 // 3)
    for index, line in enumerate(lines):
        d.text(((l + r) // 2, (b + t) // 2 + round((index - (len(lines) - 1) / 2) * spacing)),
               line, font=face, fill=ink, anchor='mm')


balloon((28, 28, 372, 178), ('I THINK WE', 'CAN STILL WIN.'), 22)
balloon((390, 38, 600, 128), ('YOU?',), 25)
balloon((512, 394, 810, 516), ('THE LIGHT WENT OUT.', 'CAN YOU SEE ME?'), 15, ink='#a3a09e', font=regular)
balloon((1125, 500, 1380, 615), ('WAIT!', 'I HEARD SOMETHING.'), 14)
balloon((396, 655, 574, 736), ('I WAS WRONG.',), 9, ink='#a6a4a0', font=regular)
balloon((40, 835, 340, 968), ('COME BACK,', 'I AM STILL HERE.'), 14, color='#313540', ink='#f3eef8', font=regular)
balloon((68, 970, 318, 1066), ('NOT YET.',), 18)
balloon((710, 1112, 960, 1240), ('I…', 'I MISSED YOU.'), 19, leading=34)
balloon((974, 1130, 1182, 1268), ('OPEN THE DOOR.', 'HURRY UP!'), 15, color='#faf3e3', ink='#2c282d')
balloon((80, 1480, 490, 1640), ('I WAS SCARED.', 'NOW I CAN FINALLY', 'TAKE A BREATH.'), 17)
balloon((970, 1680, 1280, 1780), ('DID YOU SEE IT?',), 15, ink='#ad9ca9')
balloon((560, 1810, 796, 1962), ('$7.25',), 24)
d.rectangle((890, 1870, 1220, 1960), fill='#fffdfa', outline='#303844', width=4)
d.text((1055, 1915), 'https://example.com', fill='#080b14', font=ImageFont.truetype(regular, 22), anchor='mm')

im.save(root / 'app/src/androidTest/assets/en-comic-coverage.png', optimize=True)
