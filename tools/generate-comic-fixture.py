"""Generate a license-clean English webtoon OCR and typesetting test fixture (Python + Pillow/DejaVu)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1]
board = Image.new('RGB', (900, 720), '#647386')
d = ImageDraw.Draw(board)
d.rectangle((0, 0, 899, 270), fill='#97a9bc')
d.polygon([(0, 600), (220, 380), (360, 590), (680, 350), (899, 540), (899, 719), (0, 719)], fill='#454d64')
d.ellipse((540, 440, 850, 760), fill='#71859a')
d.rounded_rectangle((48, 72, 520, 360), radius=105, fill='#fffef8', outline='#242832', width=4)
d.polygon([(180, 354), (214, 403), (250, 354)], fill='#fffef8')
d.rounded_rectangle((523, 282, 876, 472), radius=72, fill='#fffef8', outline='#242832', width=4)
d.polygon([(656, 466), (620, 506), (700, 468)], fill='#fffef8')
d.rounded_rectangle((74, 551, 372, 648), radius=20, fill='#fffef8', outline='#242832', width=3)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 31)
for i, line in enumerate(('MY AIM WAS OFF,', 'AND I LOST FOCUS', 'AFTER I TOOK', 'A LONG BREAK.')):
    d.text((284, 153 + 42 * i), line, font=font, anchor='mm', fill='#151618')
for i, line in enumerate(('YOU CAN TRY', 'AGAIN TOMORROW.')):
    d.text((699, 354 + 43 * i), line, font=font, anchor='mm', fill='#151618')
d.text((223, 600), '$7.25', font=font, anchor='mm', fill='#151618')
out = root / 'app/src/androidTest/assets/en-comic.png'
board.save(out, optimize=True)
