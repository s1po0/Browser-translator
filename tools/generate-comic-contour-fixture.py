"""Original dialogue fixture: wide leading, curved edges, and a protected price."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
image = Image.new('RGB', (640, 880), '#526477')
draw = ImageDraw.Draw(image)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 24)
for points in (
    [(84, 42), (229, 46), (108, 114)],
    [(412, 215), (532, 159), (566, 235)],
):
    draw.polygon(points, fill='#9b726e')
    draw.line([*points, points[0]], fill='#2f3441', width=4)
draw.ellipse((104, 45, 526, 241), fill='#fffdf7', outline='#1b1921', width=5)
draw.ellipse((112, 282, 372, 398), fill='#fffdf7', outline='#1b1921', width=5)
draw.rounded_rectangle((454, 282, 605, 369), radius=16, fill='#faf3e3', outline='#1b1921', width=4)
for position, text in [((315, 105), 'IF YOU CAN,'), ((315, 177), 'PLEASE STAY.'), ((242, 340), 'THANK YOU.'), ((529, 325), '$7.25')]:
    draw.text(position, text, fill='#14141a', font=font, anchor='mm')
image.save(root / 'app/src/androidTest/assets/en-comic-contour.png', optimize=True)
