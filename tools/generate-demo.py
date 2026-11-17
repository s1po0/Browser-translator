"""Generate original OCR fixtures. Optional developer tool: Python + Pillow + DejaVu font."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

DEST = Path(__file__).resolve().parents[1] / 'app/src/main/assets/demo'
FONTS = Path('/usr/share/fonts/truetype/dejavu')

def font(size, bold=False):
    return ImageFont.truetype(str(FONTS / ('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf')), size)

im = Image.new('RGB', (960, 820), '#dae4db')
d = ImageDraw.Draw(im)
d.ellipse((730, 64, 853, 187), fill='#ffc99d')
for pts, color in [([(0,470),(180,225),(370,390),(630,195),(960,445),(960,820),(0,820)],'#719483'),
                   ([(0,619),(289,360),(486,519),(726,338),(960,581),(960,820),(0,820)],'#4a7063'),
                   ([(0,738),(252,620),(458,691),(717,585),(960,729),(960,820),(0,820)],'#25483f')]:
    d.polygon(pts, fill=color)
d.polygon([(307,820),(511,611),(598,592),(478,820)],fill='#a9b8aa')
for x,y,c in [(403,689,'#ffc482'),(550,631,'#e79772')]:
    d.ellipse((x-31,y-75,x+31,y-13),fill='#242d2c')
    d.rounded_rectangle((x-25,y-25,x+25,y+52), radius=14, fill=c)
    d.line((x-11,y+50,x-20,y+71),fill='#222d2b',width=12)
    d.line((x+11,y+50,x+17,y+70),fill='#222d2b',width=12)
for box, tail, text in [((45,44,621,228),[(338,221),(349,275),(391,215)],'Do you see that light?'),
                         ((339,300,904,481),[(585,473),(561,529),(635,472)],'We are almost home.')]:
    d.polygon(tail, fill='#fffdf7')
    d.rounded_rectangle(box, radius=42, fill='#fffdf7', outline='#202d27', width=3)
    d.text(((box[0]+box[2])/2,(box[1]+box[3])/2), text, font=font(39, True), anchor='mm', fill='#202d27')
im.save(DEST / 'chapter.png', optimize=True)

im = Image.new('RGB', (960, 560), '#fff9ec')
d = ImageDraw.Draw(im)
d.rounded_rectangle((24,24,936,534), radius=25, outline='#2f5747', width=4)
d.text((480,90), 'Mountain Cafe', font=font(54, True), anchor='mm', fill='#213f36')
d.line((85,156,875,156), fill='#7a927c', width=3)
for i, (name, price) in enumerate([('Fresh mint tea','$3.50'),('Warm honey cake','$6.00'),('Lentil soup','$7.25'),('Roasted vegetables','$8.50')]):
    y = 215 + i * 73
    d.text((88,y), name, font=font(35), anchor='lm', fill='#252d26')
    d.text((870,y), price, font=font(35), anchor='rm', fill='#252d26')
im.save(DEST / 'menu.png', optimize=True)

# Optionally reproduce the Japanese/Korean emulator fixtures. Fonts are used only to rasterize fixture text.
if __name__ == '__main__':
    from os import environ
    target = DEST.parents[2] / 'androidTest/assets'
    for code, lines in [('jp', ['こんにちは！', '山へ行こう。']), ('kr', ['안녕하세요!', '집에 가요.'])]:
        face = environ.get(f'NOTO_{code.upper()}_FONT')
        if not face:
            continue
        target.mkdir(parents=True, exist_ok=True)
        board = Image.new('RGB', (880, 520), '#2c6055')
        draw = ImageDraw.Draw(board)
        typeface = ImageFont.truetype(face, 72)
        for i, text in enumerate(lines):
            x, y = 68 + i * 145, 45 + i * 226
            draw.rounded_rectangle((x, y, x + 610, y + 180), radius=40, fill='#fffef8')
            draw.polygon([(x + 196, y + 176), (x + 231, y + 211), (x + 259, y + 174)], fill='#fffef8')
            draw.text((x + 305, y + 90), text, font=typeface, anchor='mm', fill='#101414')
        board.save(target / f'{code}-comic.png', optimize=True)
