"""Draws the mod icon: a copper chest with a small lightning rod and speed chevrons, as 32x32 pixel art.

Writes src/main/resources/assets/copper_golem_mod/icon.png (128 px, shown in mod lists) and docs/icon.png
(256 px, for the Modrinth page). Run from the repository root: python3 tools/draw_icon.py (needs Pillow).
"""
from PIL import Image
import os

C = {
    'bg': (31, 42, 48, 255), 'bg2': (40, 54, 61, 255),
    'out': (110, 50, 34, 255), 'sh': (178, 86, 58, 255), 'base': (226, 122, 84, 255), 'hi': (244, 166, 126, 255), 'hi2': (252, 205, 176, 255),
    'v1': (70, 168, 142, 255), 'v2': (44, 128, 110, 255),
    'eye': (32, 22, 18, 255), 'glow': (120, 230, 210, 255),
    'line': (150, 236, 218, 255), 'line2': (88, 170, 156, 255),
    'bolt': (255, 214, 82, 255), 'bolt2': (232, 160, 40, 255),
    'latch': (60, 60, 66, 255), 'latch2': (150, 150, 158, 255),
}

def canvas():
    im = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    px = im.load()
    r = 4  # corner radius in pixels
    for x in range(32):
        for y in range(32):
            dx = max(r - x, x - (31 - r), 0)
            dy = max(r - y, y - (31 - r), 0)
            if dx * dx + dy * dy <= r * r:
                px[x, y] = C['bg']
    return im

def rect(im, x0, y0, x1, y1, col):
    px = im.load()
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px[x, y] = C[col]

def dot(im, pts, col):
    px = im.load()
    for x, y in pts:
        px[x, y] = C[col]

def antenna(im, cx, top, bottom):
    rect(im, cx, top + 2, cx + 1, bottom, 'out')
    rect(im, cx, top + 2, cx, bottom, 'sh')
    rect(im, cx - 1, top, cx + 2, top + 2, 'out')
    rect(im, cx - 1, top, cx + 1, top + 1, 'hi')
    dot(im, [(cx - 1, top)], 'hi2')
    dot(im, [(cx + 2, top + 2)], 'sh')

def draw():
    im = canvas()
    # A copper chest with a tiny lightning rod, and speed chevrons.
    antenna(im, 11, 4, 9)
    rect(im, 3, 9, 21, 26, 'out')
    rect(im, 4, 10, 20, 15, 'base'); rect(im, 4, 10, 20, 10, 'hi'); rect(im, 4, 10, 4, 15, 'hi')
    rect(im, 4, 16, 20, 16, 'out')
    rect(im, 4, 17, 20, 25, 'base'); rect(im, 4, 24, 20, 25, 'sh'); rect(im, 20, 11, 20, 25, 'sh'); rect(im, 4, 17, 4, 23, 'hi')
    rect(im, 10, 14, 13, 18, 'latch'); rect(im, 11, 15, 12, 16, 'latch2')
    dot(im, [(4, 25), (5, 25), (4, 24), (20, 25), (19, 25), (20, 23)], 'v1')
    for x0 in (23, 26):
        for i in range(4):
            dot(im, [(x0 + i, 14 + i), (x0 + i, 21 - i)], 'line')
    return im


icon = draw()
for path, size in (('src/main/resources/assets/copper_golem_mod/icon.png', 128), ('docs/icon.png', 256)):
    icon.resize((size, size), Image.NEAREST).save(path)
    print('wrote', path)
