"""
diagnose_pdf.py
───────────────
Run this on your PDF to see EXACTLY what fitz extracts from each question box.
This will reveal what choice-letter patterns fitz sees so we can fix classification.

Usage:
    python3 diagnose_pdf.py  <pdf_path>  [max_pages]
"""
import sys
import fitz
import cv2
import numpy as np
import re
from pathlib import Path

DPI  = 200
ZOOM = DPI / 72

BOX_BORDER_COLORS = [
    (np.array([85,  30,  30]), np.array([145, 255, 220]), "blue"),
    (np.array([125, 30,  30]), np.array([165, 255, 220]), "purple"),
    (np.array([0,   60,  60]), np.array([10,  255, 255]), "red_lo"),
    (np.array([165, 60,  60]), np.array([180, 255, 255]), "red_hi"),
    (np.array([80,  60,  60]), np.array([100, 255, 255]), "teal"),
    (np.array([36,  60,  60]), np.array([85,  255, 255]), "green_box"),
]
BOX_MIN_AREA_FRAC  = 0.03
BOX_MIN_WIDTH_FRAC = 0.50
BOX_PAD_TOP        = 20
CONTENT_FOOT       = 0.96


def page_to_image(page):
    mat = fitz.Matrix(ZOOM, ZOOM)
    pix = page.get_pixmap(matrix=mat, alpha=False)
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
        pix.height, pix.width, 3)
    return cv2.cvtColor(img, cv2.COLOR_RGB2BGR)


def build_border_mask(image):
    hsv      = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    combined = np.zeros(image.shape[:2], dtype=np.uint8)
    for lo, hi, _ in BOX_BORDER_COLORS:
        combined = cv2.bitwise_or(combined, cv2.inRange(hsv, lo, hi))
    return combined


def find_question_boxes(image):
    h, w = image.shape[:2]
    mask = build_border_mask(image)
    kh     = cv2.getStructuringElement(cv2.MORPH_RECT, (80, 1))
    kv     = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 20))
    closed = cv2.morphologyEx(mask,   cv2.MORPH_CLOSE, kh)
    closed = cv2.morphologyEx(closed, cv2.MORPH_CLOSE, kv)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL,
                                   cv2.CHAIN_APPROX_SIMPLE)
    min_area  = h * w * BOX_MIN_AREA_FRAC
    min_width = w * BOX_MIN_WIDTH_FRAC
    boxes = []
    for cnt in contours:
        if cv2.contourArea(cnt) < min_area:
            continue
        bx, by, bw, bh = cv2.boundingRect(cnt)
        if bw > min_width and bh > 30:
            boxes.append((bx, by, bw, bh))
    boxes.sort(key=lambda b: b[1])
    return boxes


def px_to_pt(page, bx_px, by_px, bw_px, bh_px):
    mat  = fitz.Matrix(ZOOM, ZOOM)
    pix  = page.get_pixmap(matrix=mat, alpha=False)
    pw_px, ph_px = pix.width, pix.height
    pw_pt, ph_pt = page.rect.width, page.rect.height
    sx, sy = pw_pt / pw_px, ph_pt / ph_px
    return fitz.Rect(bx_px*sx, by_px*sy, (bx_px+bw_px)*sx, (by_px+bh_px)*sy)


def main():
    pdf_path = sys.argv[1]
    max_pages = int(sys.argv[2]) if len(sys.argv) > 2 else 5

    doc = fitz.open(pdf_path)
    q_num = 0

    for page_num, page in enumerate(doc):
        if page_num >= max_pages:
            break
        print(f"\n{'='*60}")
        print(f"PAGE {page_num+1}")
        print(f"{'='*60}")

        img = page_to_image(page)
        boxes = find_question_boxes(img)
        h = img.shape[0]
        content_end = int(h * CONTENT_FOOT)

        if not boxes:
            print("  No boxes found on this page")
            continue

        for i, (bx, by, bw, bh) in enumerate(boxes):
            q_num += 1
            box_top = max(0, by - BOX_PAD_TOP)
            box_bot = min(by + bh, content_end)
            rect_pt = px_to_pt(page, bx, box_top, bw, box_bot - box_top)

            # Get ALL text from fitz in this rect
            text = page.get_text("text", clip=rect_pt).strip()

            # Also get blocks with positions
            blocks = page.get_text("blocks", clip=rect_pt, sort=True)

            print(f"\n  ── Box {i+1} (Q{q_num}) ──────────────────")
            print(f"  Rect (pt): {rect_pt}")
            print(f"  Pixel box: x={bx} y={box_top} w={bw} h={box_bot-box_top}")
            print(f"\n  RAW TEXT (repr):")
            print(f"  {repr(text[:500])}")
            print(f"\n  TEXT BLOCKS:")
            for b in blocks[:10]:  # first 10 blocks
                x0,y0,x1,y1,btxt = b[0],b[1],b[2],b[3],b[4]
                btxt = btxt.strip()
                if btxt:
                    print(f"    y={y0:.1f}-{y1:.1f} | {repr(btxt[:120])}")

            # Check for circled/special unicode characters
            unicode_chars = [(c, ord(c), hex(ord(c))) for c in text
                            if ord(c) > 127]
            if unicode_chars:
                print(f"\n  UNICODE CHARS found:")
                seen = set()
                for c, cp, hx in unicode_chars:
                    if hx not in seen:
                        seen.add(hx)
                        print(f"    '{c}' codepoint={cp} ({hx})")

    print(f"\n\nDone. Examined {q_num} boxes across {min(max_pages, len(doc))} pages.")


if __name__ == "__main__":
    main()
