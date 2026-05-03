"""
questions_divider.py  (v8 – real question numbers + page-range support)
========================================================================
Splits a question-bank PDF into per-question images.

KEY CHANGES in v8
-----------------
1. REAL QUESTION NUMBERS: The JSON now carries the actual number printed
   in the PDF (e.g. 10, 33, 39) in a new field "questionNumber".
   Sub-questions carry the same parent number: 10-i, 10-ii, 10-iii.
   File names (q_1.png, q_2.png …) stay sequential to avoid collisions.

2. PAGE RANGE SUPPORT: Two new optional CLI arguments --page-from and
   --page-to (1-based, inclusive).  When omitted the whole PDF is processed.

3. NUMBERING GUARANTEE: If two consecutive questions are detected as
   numbers that go BACKWARD (e.g. 33 → 28), we still keep the real numbers
   as-is — the teacher chose that ordering in the PDF.  We never silently
   re-sequence.

JSON entry for standalone question:
  {
    "id":             "q_3",
    "type":           "mcq",
    "image":          "output/q_3.png",
    "page":           2,
    "questionNumber": 11,   ← real number from PDF text
    "parentNumber":   0,
    "subLabel":       ""
  }

JSON entry for sub-question:
  {
    "id":             "q_5",
    "type":           "mcq",
    "image":          "output/q_5.png",
    "page":           3,
    "questionNumber": 10,   ← same as parentNumber
    "parentNumber":   10,
    "subLabel":       "ii"
  }

USAGE
-----
  # Full PDF
  python3 questions_divider.py input.pdf output/

  # Pages 3 to 7 only  (1-based, inclusive)
  python3 questions_divider.py input.pdf output/ --page-from 3 --page-to 7
"""

import argparse
import json
import re
import sys
import io
from pathlib import Path

from matplotlib import image

# ── Windows UTF-8 fix ────────────────────────────────────────────────────────
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

import fitz          # PyMuPDF
import cv2
import numpy as np

# ═══════════════════════════════════════════════════════════════════════
# DEBUG LOGGER
# ═══════════════════════════════════════════════════════════════════════

DEBUG = True
DEBUG_FILE = "debug_log.txt"

def debug_log(msg):
    if DEBUG:
        with open(DEBUG_FILE, "a", encoding="utf-8") as f:
            f.write(msg + "\n")

# ═══════════════════════════════════════════════════════════════════════
# PATTERNS
# ═══════════════════════════════════════════════════════════════════════

# Matches a block that starts with a question number: "10  If the …"
# Group 1 = the number.
QUESTION_PAT = re.compile(r'^\s*(\d{1,3})\s+[A-Za-z(]')

# Looser: matches any leading integer in a text block (used for boxes/bands)
LEADING_INT_PAT = re.compile(r'^\s*(\d{1,3})\b')

ROMAN_PAT = re.compile(
    r'^\s*(i{1,4}|iv|vi{0,3}|ix|I{1,4}|IV|VI{0,3}|IX)\s*[.)]',
    re.IGNORECASE | re.MULTILINE
)
SUBPART_PAT = re.compile(r'^\s*[a-dA-D]\s*\)', re.MULTILINE)
STEM_PAD_PT = 5

MCQ_CHOICE_UNICODE = re.compile(r'[\u24b6-\u24cf\u24d0-\u24e9]')
MCQ_PAREN_PAT      = re.compile(r'\(\s*[A-Da-d]\s*\)')
MCQ_SOLO_LINE_PAT  = re.compile(r'^\s*[A-D]\s*$')
MCQ_MIN_CHOICES    = 2

# ═══════════════════════════════════════════════════════════════════════
# CONFIGURATION
# ═══════════════════════════════════════════════════════════════════════

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
BOX_FORMAT_THRESH  = 10_000

GREEN_LINE_LO  = np.array([35,  40,  40])
GREEN_LINE_HI  = np.array([90, 255, 255])
GREEN_MIN_FRAC = 0.50
HOUGH_THRESH   = 100

DOT_MIN_AREA      = 1
DOT_MAX_AREA      = 120
DOT_MAX_DIM       = 14
DOT_ROW_MIN_DOTS  = 8
DOT_ROW_HEIGHT    = 10
DOT_MIN_ROWS      = 2
DOT_SCAN_TOP_FRAC = 0.45

# ═══════════════════════════════════════════════════════════════════════
# REAL NUMBER EXTRACTION HELPERS
# ═══════════════════════════════════════════════════════════════════════

def extract_leading_number(text: str):
    """Return the integer at the start of text, or None."""
    m = LEADING_INT_PAT.match(text.strip())
    return int(m.group(1)) if m else None


def extract_question_number_from_text(fitz_text: str):
    """
    Try to find the real question number from the fitz text of a crop/box.
    Tries QUESTION_PAT first (strict), then LEADING_INT_PAT (loose).
    Returns int or None.
    """
    for line in fitz_text.splitlines():
        m = QUESTION_PAT.match(line)
        if m:
            n = int(m.group(1))
            if 1 <= n <= 999:
                return n
    # Fallback: leading integer in first non-empty line
    for line in fitz_text.splitlines():
        line = line.strip()
        if line:
            n = extract_leading_number(line)
            if n and 1 <= n <= 999:
                return n
    return None

# ═══════════════════════════════════════════════════════════════════════
# PAGE → IMAGE
# ═══════════════════════════════════════════════════════════════════════

def page_to_image(page):
    mat = fitz.Matrix(ZOOM, ZOOM)
    pix = page.get_pixmap(matrix=mat, alpha=False)
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
        pix.height, pix.width, 3)
    return cv2.cvtColor(img, cv2.COLOR_RGB2BGR)


# ═══════════════════════════════════════════════════════════════════════
# BORDER MASK
# ═══════════════════════════════════════════════════════════════════════

def build_border_mask(image):
    hsv      = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    combined = np.zeros(image.shape[:2], dtype=np.uint8)
    for lo, hi, _ in BOX_BORDER_COLORS:
        combined = cv2.bitwise_or(combined, cv2.inRange(hsv, lo, hi))
    return combined


# ═══════════════════════════════════════════════════════════════════════
# COORDINATE CONVERSION
# ═══════════════════════════════════════════════════════════════════════

def px_rect_to_pt_rect(page, bx_px, by_px, bw_px, bh_px):
    mat  = fitz.Matrix(ZOOM, ZOOM)
    pix  = page.get_pixmap(matrix=mat, alpha=False)
    sx   = page.rect.width  / pix.width
    sy   = page.rect.height / pix.height
    return fitz.Rect(
        bx_px * sx,
        by_px * sy,
        (bx_px + bw_px) * sx,
        (by_px + bh_px) * sy,
    )


# ═══════════════════════════════════════════════════════════════════════
# SIGNAL 1 – DOT-LINE DETECTION
# ═══════════════════════════════════════════════════════════════════════

def has_dot_lines(crop):
    h = crop.shape[0]
    start = int(h * 0.55)
    region = crop[start:, :]
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    gray = cv2.equalizeHist(gray)
    _, binary = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY_INV)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (30, 3))
    lines = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    contours, _ = cv2.findContours(lines, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    line_count = sum(
        1 for cnt in contours
        if (lambda r: r[2] > crop.shape[1] * 0.3 and r[3] < 15)(cv2.boundingRect(cnt))
    )
    return line_count >= 2


# ═══════════════════════════════════════════════════════════════════════
# SIGNAL 2 – MCQ CHOICE DETECTION
# ═══════════════════════════════════════════════════════════════════════

def count_mcq_choices(text):
    score = 0
    score += len(MCQ_CHOICE_UNICODE.findall(text))
    score += len(MCQ_PAREN_PAT.findall(text))
    score += sum(1 for l in text.splitlines() if MCQ_SOLO_LINE_PAT.match(l))
    return score

def has_mcq_choices(text):
    return count_mcq_choices(text) >= MCQ_MIN_CHOICES


# ═══════════════════════════════════════════════════════════════════════
# CLASSIFICATION
# ═══════════════════════════════════════════════════════════════════════

def classify_question(crop, fitz_text):
    mcq_score = count_mcq_choices(fitz_text)
    dot_flag  = has_dot_lines(crop)

    # Strong MCQ detection
    if mcq_score >= 2:
        return "mcq"

    # Written detection (dots + no choices)
    if dot_flag and mcq_score == 0:
        return "written"

    # fallback (safer)
    if dot_flag:
        return "written"

    return "mcq"


# ═══════════════════════════════════════════════════════════════════════
# ROMAN SUB-QUESTION SPLITTING
# ═══════════════════════════════════════════════════════════════════════

def _normalise_roman(raw: str) -> str:
    return re.sub(r'[.)\\s]+$', '', raw.strip()).lower()


def find_roman_splits_fitz(page, box_rect_pt):
    blocks = page.get_text("blocks", clip=fitz.Rect(box_rect_pt), sort=True)
    splits = []
    for b in blocks:
        y0_pt = b[1]
        for line in b[4].splitlines():
            line_stripped = line.strip()
            m = ROMAN_PAT.match(line_stripped)
            if m and line_stripped:
                label = _normalise_roman(m.group(1))
                splits.append((y0_pt, label))
                break
    return splits


def split_box_by_roman_fitz(page, box_rect_pt, box_img):
    roman_splits = find_roman_splits_fitz(page, box_rect_pt)
    if not roman_splits:
        return None

    box_top_pt = box_rect_pt.y0
    box_bot_pt = box_rect_pt.y1
    box_h_pt   = box_bot_pt - box_top_pt
    box_h_px   = box_img.shape[0]
    scale      = box_h_px / box_h_pt if box_h_pt > 0 else ZOOM

    stem_bot_pt = max(box_top_pt, roman_splits[0][0] - STEM_PAD_PT)
    stem_bot_px = int((stem_bot_pt - box_top_pt) * scale)
    stem_crop   = box_img[:stem_bot_px, :]

    boundaries_pt = [y for y, _ in roman_splits] + [box_bot_pt]
    result = []
    for i in range(len(roman_splits)):
        top_px = max(0, int((roman_splits[i][0]   - box_top_pt) * scale))
        bot_px = min(box_h_px, int((boundaries_pt[i+1] - box_top_pt) * scale))
        sub    = box_img[top_px:bot_px, :]
        if sub.shape[0] < 20:
            continue
        combined = np.vstack([stem_crop, sub]) if stem_crop.shape[0] > 0 else sub
        label    = roman_splits[i][1]
        result.append((combined, label))

    return result if result else None


# ═══════════════════════════════════════════════════════════════════════
# WRITTEN: REMOVE ANSWER REGION
# ═══════════════════════════════════════════════════════════════════════

def _find_dot_region_top(crop):
    h     = crop.shape[0]
    start = int(h * DOT_SCAN_TOP_FRAC)
    region = crop[start:, :]
    gray   = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    _, binary = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY_INV)
    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    dot_ys = []
    for cnt in contours:
        area = cv2.contourArea(cnt)
        if DOT_MIN_AREA <= area <= DOT_MAX_AREA:
            x, y, cw, ch = cv2.boundingRect(cnt)
            if cw <= DOT_MAX_DIM and ch <= DOT_MAX_DIM:
                dot_ys.append(y)
    if len(dot_ys) < DOT_ROW_MIN_DOTS:
        return None
    y_arr = np.array(dot_ys)
    rh    = region.shape[0]
    for y0 in range(0, rh, DOT_ROW_HEIGHT):
        if int(np.sum((y_arr >= y0) & (y_arr < y0 + DOT_ROW_HEIGHT))) >= DOT_ROW_MIN_DOTS:
            return start + max(0, y0 - 5)
    return None


def remove_answer_region(crop):
    h       = crop.shape[0]
    dot_top = _find_dot_region_top(crop)
    cut     = dot_top if dot_top is not None else int(h * 0.70)
    result  = crop.copy()
    result[cut:, :] = 255
    return result


def count_written_subparts(text):
    return max(len(SUBPART_PAT.findall(text)), 1)


# ═══════════════════════════════════════════════════════════════════════
# SAVE HELPER
# v8: accepts real_question_number separately from file sequence index
# ═══════════════════════════════════════════════════════════════════════

def _save(crop, file_idx, q_type, out_dir, results, page_num,
          real_q_num=0, parent_number=0, sub_label=""):
    """
    file_idx        – sequential file counter (q_1.png, q_2.png …)
    real_q_num      – actual question number from PDF (7, 10, 33 …)
    parent_number   – for sub-questions: the parent's real number; 0 for standalone
    sub_label       – "i", "ii", … or "" for standalone
    """
    filename = f"q_{file_idx}.png"
    out_path  = out_dir / filename
    cv2.imwrite(str(out_path), crop)
    entry = {
        "id":             f"q_{file_idx}",
        "type":           q_type,
        "image":          str(out_path),
        "page":           page_num + 1,
        "questionNumber": real_q_num,    # ← NEW: real number from PDF
        "parentNumber":   parent_number,
        "subLabel":       sub_label,
    }
    results.append(entry)
    sub_info = f" [{parent_number}-{sub_label}]" if parent_number > 0 else f" [Q{real_q_num}]"
    print(f"  Saved {filename}  ({q_type}){sub_info}")
    debug_log(f"[SAVE] page={page_num+1} file=q_{file_idx} num={real_q_num}")


# ═══════════════════════════════════════════════════════════════════════
# CORE CROP PROCESSOR
# v8: real_q_num is passed in (extracted before this call)
# ═══════════════════════════════════════════════════════════════════════

def process_question_crop(crop, page, box_rect_pt, page_num,
                          file_idx, out_dir, results,
                          real_q_num=0):
    """
    Process one question crop.
    real_q_num: the real question number already extracted from the PDF text.
                0 means "unknown" — we will try to extract it from box text.
    Returns updated file_idx.
    """
    if crop is None or crop.size == 0 or crop.shape[0] < 30:
        return file_idx

    fitz_text = ""
    if box_rect_pt is not None:
        fitz_text = page.get_text("text", clip=fitz.Rect(box_rect_pt)).strip()

    # If real number not provided, try to extract from the box text
    if real_q_num == 0:
        real_q_num = extract_question_number_from_text(fitz_text) or 0

    q_type = classify_question(crop, fitz_text)

    if q_type == "written":
        clean = remove_answer_region(crop)
        n_sub = count_written_subparts(fitz_text)
        for _ in range(n_sub):
            _save(clean, file_idx, "written", out_dir, results, page_num,
                  real_q_num=real_q_num, parent_number=0, sub_label="")
            file_idx += 1
    else:
        # Try roman numeral sub-questions
        sub_crops = None
        if box_rect_pt is not None:
            sub_crops = split_box_by_roman_fitz(page, box_rect_pt, crop)

        if sub_crops:
            # Parent number = the real question number of this box
            parent_num = real_q_num
            for sub_crop, sub_label in sub_crops:
                _save(sub_crop, file_idx, "mcq", out_dir, results, page_num,
                      real_q_num=parent_num, parent_number=parent_num,
                      sub_label=sub_label)
                file_idx += 1
        else:
            _save(crop, file_idx, "mcq", out_dir, results, page_num,
                  real_q_num=real_q_num, parent_number=0, sub_label="")
            file_idx += 1

    return file_idx


# ═══════════════════════════════════════════════════════════════════════
# FORMAT DETECTION
# ═══════════════════════════════════════════════════════════════════════

def is_box_format(image):
    mask = build_border_mask(image)
    return int(mask.sum()) // 255 > BOX_FORMAT_THRESH


def dedup(vals, tol=15):
    out = []
    for v in sorted(vals):
        if not out or abs(v - out[-1]) > tol:
            out.append(v)
    return out


# ═══════════════════════════════════════════════════════════════════════
# VALIDATION
# ═══════════════════════════════════════════════════════════════════════

def is_valid_question_block(text, y_pos, page_height):
    text = text.strip()
    if not text or len(text) < 12:
        return False
    if re.fullmatch(r'[\d\s:]+', text):
        return False
    if not re.search(r'[A-Za-z]{3,}', text):
        return False
    # Removed top‑boundary check to include first question on page
    if y_pos > page_height * 0.95:
        return False
    bad_words = ["exam", "revision", "ahmed", "final"]
    if any(w in text.lower() for w in bad_words):
        return False
    if re.search(r'\b\d+\s+\d+\s+\d+', text):
        return False
    return True


def validate_segments(segments, image):
    if not segments:
        return None
    img_h = image.shape[0]
    valid = [s for s in segments if img_h * 0.04 < s[0].shape[0] < img_h * 0.98]
    if not valid:
        return None
    heights = [c.shape[0] for c, *_ in valid]
    avg = sum(heights) / len(heights)
    return valid if all(h <= avg * 3 for h in heights) else None


# ═══════════════════════════════════════════════════════════════════════
# TEXT-BASED SPLIT  (v8: extracts real_q_num per segment)
# ═══════════════════════════════════════════════════════════════════════

def split_by_question_numbers(page, image):
    """
    Returns list of (crop, rect, real_q_num) triples.
    real_q_num is the actual integer from the PDF (e.g. 10, 33, 39).
    """
    debug_log("\n--- TEXT SPLIT CHECK ---")
    blocks = page.get_text("blocks")
    questions = []

    for b in blocks:
        text  = b[4].strip()
        y_pos = b[1]
        debug_log(f"[BLOCK TEXT]\n{text[:100]}")
        m = QUESTION_PAT.match(text)
        if m and is_valid_question_block(text, y_pos, page.rect.height):
            real_num = int(m.group(1))
            debug_log(f"[MATCHED Q] {real_num}")
            questions.append((b, real_num))

    if len(questions) < 2:
        return None

    questions.sort(key=lambda x: x[0][1])  # sort by y position

    ys = [q[0][1] for q in questions]
    distances = [ys[i+1] - ys[i] for i in range(len(ys)-1)]
    if distances:
        avg_dist = sum(distances) / len(distances)
        if avg_dist < page.rect.height * 0.05:
            return None

    segments = []
    img_h = image.shape[0]
    prev_bottom_px = 0
    shift = int(image.shape[0] * 0.08)

    for i, (b, real_num) in enumerate(questions):
        top    = b[1]
        bottom = questions[i+1][0][1] if i < len(questions)-1 else page.rect.height

        top_px    = max(prev_bottom_px, int(top * ZOOM) - shift)
        bottom_px = min(img_h, int(bottom * ZOOM) + 20)

        if bottom_px - top_px < 40:
            continue

        crop = image[top_px:bottom_px, :]
        rect = fitz.Rect(0, top, page.rect.width, bottom)

        if crop.shape[0] > image.shape[0] * 0.04:
            segments.append((crop, rect, real_num))

        prev_bottom_px = bottom_px

    return segments if segments else None


# ═══════════════════════════════════════════════════════════════════════
# LINE-BASED SPLIT  (no real numbers — fallback)
# ═══════════════════════════════════════════════════════════════════════

def split_by_horizontal_lines(image, page):
    gray  = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150)
    lines = cv2.HoughLinesP(edges, 1, np.pi/180, threshold=120,
                            minLineLength=image.shape[1]*0.7, maxLineGap=10)
    if lines is None:
        return None
    ys = sorted(set(
        y1 for x1,y1,x2,y2 in lines[:,0] if abs(y1-y2) < 5
    ))
    if len(ys) < 2:
        return None
    segments = []
    for i in range(len(ys)-1):
        top, bottom = ys[i], ys[i+1]
        if bottom - top < 60:
            continue
        crop = image[top:bottom, :]
        rect = px_rect_to_pt_rect(page, 0, top, image.shape[1], bottom-top)
        segments.append((crop, rect, 0))   # real_q_num=0, will be extracted later
    return segments


# ═══════════════════════════════════════════════════════════════════════
# BADGE DETECTION HELPER
# ═══════════════════════════════════════════════════════════════════════

def has_badge(text):
    text = text.strip().lower()

    # short header-like lines only
    if len(text) < 20 and (
        "exp" in text or
        "hlt" in text or
        re.match(r'^\w{2,5}\s*\d*$', text)
    ):
        return True

    return False


# ═══════════════════════════════════════════════════════════════════════
# BROKEN TOP QUESTION DETECTION
# ═══════════════════════════════════════════════════════════════════════

def detect_broken_top_questions(page):
    blocks = page.get_text("blocks")

    top_limit = page.rect.height * 0.30

    numbers = []
    texts = []

    for b in blocks:
        text = b[4].strip()
        y = b[1]

        if y > top_limit:
            continue

        # detect standalone numbers
        if re.fullmatch(r'\d{1,3}', text):
            numbers.append((int(text), y))

        # 🔥 IMPORTANT: relax condition
        elif len(text) > 20 and re.search(r'[A-Za-z]', text):
            texts.append((text, y))

    # 🔥 NEW LOGIC
    if len(numbers) >= 2:
        return True

    return False

# ═══════════════════════════════════════════════════════════════════════
# BOX FORMAT
# ═══════════════════════════════════════════════════════════════════════

def find_question_boxes(image):
    h, w = image.shape[:2]
    mask = build_border_mask(image)
    kh     = cv2.getStructuringElement(cv2.MORPH_RECT, (80, 1))
    kv     = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 20))
    closed = cv2.morphologyEx(mask,   cv2.MORPH_CLOSE, kh)
    closed = cv2.morphologyEx(closed, cv2.MORPH_CLOSE, kv)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
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


def process_box_page(image, page, page_num, file_idx, out_dir, results, page_has_oval=False):
    h    = image.shape[0]
    boxes = find_question_boxes(image)
    content_end = int(h * CONTENT_FOOT)

    if not boxes:
        return process_green_page(image, page, page_num, file_idx, out_dir, results)

    for bx, by, bw, bh in boxes:
        debug_log(f"\n[BOX] x={bx}, y={by}, w={bw}, h={bh}")

        # 🔧 Reduced top padding (safer for first question)
        box_top  = max(0, by - 10)
        box_bot  = min(by + bh, content_end)

        if box_bot - box_top < 30:
            continue

        box_crop    = image[box_top:box_bot, bx:bx+bw]
        box_rect_pt = px_rect_to_pt_rect(page, bx, box_top, bw, box_bot-box_top)

        if box_crop.size == 0:
            continue

        # ── Extract full box text (before any modification)
        box_text = page.get_text("text", clip=box_rect_pt).strip()
        debug_log(f"[BOX TEXT]\n{box_text[:200]}")

        # ── Badge detection on TOP REGION ONLY
        top_region_height = int(box_crop.shape[0] * 0.15)
        top_rect_pt = px_rect_to_pt_rect(page, bx, box_top, bw, top_region_height)
        top_text = page.get_text("text", clip=top_rect_pt).strip()
        debug_log(f"[TOP TEXT]\n{top_text}")

        # ── SAFE badge removal (FIXED)
        badge_flag = has_badge(top_text)
        debug_log(f"[BADGE DETECTED] {badge_flag}")

        if badge_flag or page_has_oval:
            shift = int(box_crop.shape[0] * 0.08)

            # 🔍 Check if cutting is safe (very important)
            test_rect = px_rect_to_pt_rect(page, bx, box_top + shift, bw, 60)
            test_text = page.get_text("text", clip=test_rect).strip()
            debug_log(f"[SHIFT TEST TEXT]\n{test_text}")

            test_num = extract_question_number_from_text(test_text)
            debug_log(f"[SHIFT SAFE?] {test_num is not None}")

            # Only cut if question number still exists after cut
            if test_num:
                debug_log(f"[APPLY SHIFT] {shift}px")
                box_crop = box_crop[shift:, :]
            else:
                debug_log("[SKIP SHIFT]")

        # ── Extract real question number (from original full text)
        real_q_num = extract_question_number_from_text(box_text) or 0
        debug_log(f"[EXTRACTED NUMBER] {real_q_num}")

        # ── Process normally (unchanged logic)
        file_idx = process_question_crop(
            box_crop, page, box_rect_pt, page_num,
            file_idx, out_dir, results,
            real_q_num=real_q_num
        )

    return file_idx

# ═══════════════════════════════════════════════════════════════════════
# TOP QUESTION RECOVERY
# ═══════════════════════════════════════════════════════════════════════

def recover_top_question(page, image, page_num, file_idx, out_dir, results):
    h, w = image.shape[:2]

    # scan top 25% of page
    scan_h = int(h * 0.25)
    rect = px_rect_to_pt_rect(page, 0, 0, w, scan_h)
    text = page.get_text("text", clip=rect).strip()

    debug_log(f"[TOP RECOVERY TEXT]\n{text[:200]}")

    # check if number 1 exists in top
    has_one = re.search(r'^\s*1\b', text, re.MULTILINE)

    if not has_one:
        return file_idx  # nothing to recover

    # check if already saved
    for r in results:
        if r["page"] == page_num + 1 and r["questionNumber"] == 1:
            return file_idx  # already exists

    debug_log("[RECOVERY] Missing Q1 detected")

    # crop top region safely
    crop = image[0:scan_h, :]

    # try to refine crop: cut bottom where next number appears
    blocks = page.get_text("blocks")
    y_positions = []

    for b in blocks:
        t = b[4].strip()
        if re.fullmatch(r'\d{1,3}', t):
            y_positions.append(b[1])

    if len(y_positions) > 1:
        y_positions.sort()
        second_y = y_positions[1]
        cut_px = int(second_y * ZOOM)
        crop = image[0:cut_px, :]

    # process normally
    file_idx = process_question_crop(
        crop, page, rect, page_num,
        file_idx, out_dir, results,
        real_q_num=1
    )

    debug_log("[RECOVERY DONE] Q1 inserted")

    return file_idx

# ═══════════════════════════════════════════════════════════════════════
# GREEN-LINE FORMAT
# ═══════════════════════════════════════════════════════════════════════

def detect_green_lines(image):
    hsv   = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask  = cv2.inRange(hsv, GREEN_LINE_LO, GREEN_LINE_HI)
    edges = cv2.Canny(mask, 50, 150)
    w     = image.shape[1]
    lines = cv2.HoughLinesP(edges, 1, np.pi/180, threshold=HOUGH_THRESH,
                            minLineLength=w*GREEN_MIN_FRAC, maxLineGap=20)
    ys = []
    if lines is not None:
        for l in lines:
            x1,y1,x2,y2 = l[0]
            if abs(y1-y2) < 5:
                ys.append((y1+y2)//2)
    return dedup(ys)


def bands_from_green(ys, height):
    if not ys:
        return [(0, height)]
    ys = sorted(ys)
    bands = []
    for i in range(len(ys)-1):
        if ys[i+1] - ys[i] > 30:
            bands.append((int(ys[i]), int(ys[i+1])))
    bands.append((int(ys[-1]), height))
    return bands


def process_green_page(image, page, page_num, file_idx, out_dir, results):
    h     = image.shape[0]
    ys    = detect_green_lines(image)
    bands = bands_from_green(ys, h)
    for top, bot in bands:
        crop = image[top:bot, :]
        if crop.shape[0] < 30:
            continue
        band_rect_pt = px_rect_to_pt_rect(page, 0, top, image.shape[1], bot-top)
        band_text    = page.get_text("text", clip=band_rect_pt).strip()
        real_q_num   = extract_question_number_from_text(band_text) or 0
        file_idx = process_question_crop(
            crop, page, band_rect_pt, page_num,
            file_idx, out_dir, results, real_q_num=real_q_num)
    return file_idx


# ═══════════════════════════════════════════════════════════════════════
# SKIP DETECTION
# ═══════════════════════════════════════════════════════════════════════

def is_skip_page(image):
    h, w        = image.shape[:2]
    gray        = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    dark_ratio  = int(np.sum(gray < 200)) / (h * w)
    border_mask = build_border_mask(image)
    has_border  = int(border_mask.sum()) // 255 > 50_000
    hsv         = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    green_mask  = cv2.inRange(hsv, GREEN_LINE_LO, GREEN_LINE_HI)
    has_green   = int(green_mask.sum()) // 255 > 50_000
    return dark_ratio < 0.02 and not has_border and not has_green


# ═══════════════════════════════════════════════════════════════════════
# STAGE 2 — TOP QUESTION CROPPER
# Extracts only the first question from each page into a separate image.
# ═══════════════════════════════════════════════════════════════════════

def extract_top_question_only(page, image, page_num, out_dir):
    h, w = image.shape[:2]

    scan_h = int(h * 0.25)
    crop = image[0:scan_h, :]

    rect = px_rect_to_pt_rect(page, 0, 0, w, scan_h)
    text = page.get_text("text", clip=rect)

    # find first number
    first_num = None
    for line in text.splitlines():
        line = line.strip()
        if re.fullmatch(r'\d{1,3}', line):
            first_num = int(line)
            break

    if first_num is None:
        return None

    # cut until next number
    blocks = page.get_text("blocks")
    ys = []

    for b in blocks:
        t = b[4].strip()
        if re.fullmatch(r'\d{1,3}', t):
            ys.append(b[1])

    ys.sort()

    if len(ys) >= 2:
        second_y = ys[1]
        cut_px = int(second_y * ZOOM)
        crop = image[0:cut_px, :]

    filename = f"top_{page_num+1}.png"
    path = out_dir / filename
    cv2.imwrite(str(path), crop)

    debug_log(f"[TOP CROP] page={page_num+1} firstNum={first_num} saved={filename}")

    return {
        "page": page_num + 1,
        "questionNumber": first_num,
        "image": str(path)
    }


# ═══════════════════════════════════════════════════════════════════════
# STAGE 3 — MERGE ENGINE
# Compares results_main and results_top; inserts any missing first
# question at the correct position, re-sequencing IDs as needed.
# ═══════════════════════════════════════════════════════════════════════

def merge_first_questions(results_main, results_top):
    """
    ONLY:
    - Ensure top question is FIRST in its page
    - No duplicates
    - Keep everything else unchanged
    """

    # group main results by page
    pages = {}
    for q in results_main:
        pages.setdefault(q["page"], []).append(q)

    # process each page
    for top_q in results_top:
        page = top_q["page"]
        qnum = top_q["questionNumber"]

        page_list = pages.setdefault(page, [])

        # check if already exists (same questionNumber)
        exists = any(q["questionNumber"] == qnum for q in page_list)

        if exists:
            continue

        # 🔥 JUST ADD IT (no shifting, no ID tricks)
        page_list.append({
            "id": "",  # temporary
            "type": "mcq",
            "image": top_q["image"],
            "page": page,
            "questionNumber": qnum,
            "parentNumber": 0,
            "subLabel": ""
        })

    # 🔥 NOW FIX ORDER (THIS IS THE KEY PART)
    final = []

    for page in sorted(pages.keys()):
        page_qs = pages[page]

        # sort by vertical position (image names reflect order already)
        page_qs.sort(key=lambda x: x["image"])

        # 🔥 FORCE top question to be FIRST if exists
        top_candidates = [q for q in page_qs if "top_" in q["image"]]

        if top_candidates:
            top_q = top_candidates[0]
            page_qs.remove(top_q)
            page_qs.insert(0, top_q)

        final.extend(page_qs)

    # 🔥 rebuild IDs cleanly
    for i, q in enumerate(final, start=1):
        q["id"] = f"q_{i}"

    return final
# ═══════════════════════════════════════════════════════════════════════
# OVAL RECOVERY (unchanged)
# ═══════════════════════════════════════════════════════════════════════

def recover_oval_first_question(page, image, page_num, file_idx, out_dir, results):
    """
    Called ONLY when page_has_oval=True and text-split already ran.
    Checks if the first question on this page is missing, recovers it.
    """
    page_results = [r for r in results if r["page"] == page_num + 1]
    if not page_results:
        return file_idx

    saved_nums = sorted(r["questionNumber"] for r in page_results if r["questionNumber"] > 0)
    if not saved_nums:
        return file_idx

    expected_first = saved_nums[0] - 1
    if expected_first < 1:
        return file_idx

    debug_log(f"[OVAL RECOVERY] Looking for Q{expected_first} on page {page_num+1}")

    blocks = page.get_text("blocks", sort=True)
    page_h = page.rect.height

    # Find the standalone number block for expected_first
    num_block = None
    for b in blocks:
        text = b[4].strip()
        if re.fullmatch(r'\d{1,3}', text) and int(text) == expected_first:
            num_block = b
            break

    if num_block is None:
        debug_log(f"[OVAL RECOVERY] Number block {expected_first} not found")
        return file_idx

    num_y0 = num_block[1]

    # Find nearest valid text block within 80pt
    best_text_block = None
    best_dist = 999
    for b in blocks:
        text = b[4].strip()
        if not is_valid_question_block(text, b[1], page_h):
            continue
        dist = abs(b[1] - num_y0)
        if dist < best_dist and dist < 80:
            best_dist = dist
            best_text_block = b

    if best_text_block is None:
        debug_log(f"[OVAL RECOVERY] No text block found near Q{expected_first}")
        return file_idx

    anchor_y = min(num_y0, best_text_block[1])

    # Bottom = y of the first saved question's number block
    first_saved_block_y = None
    for b in blocks:
        text = b[4].strip()
        if re.fullmatch(r'\d{1,3}', text) and int(text) == saved_nums[0]:
            first_saved_block_y = b[1]
            break
    if first_saved_block_y is None:
        first_saved_block_y = anchor_y + page_h * 0.25

    img_h = image.shape[0]
    top_px = max(0, int(anchor_y * ZOOM) - int(img_h * 0.02))
    bot_px = min(img_h, int(first_saved_block_y * ZOOM) + 10)

    if bot_px - top_px < 40:
        debug_log(f"[OVAL RECOVERY] Crop too small, skip")
        return file_idx

    crop = image[top_px:bot_px, :]
    rect = fitz.Rect(0, anchor_y, page.rect.width, first_saved_block_y)

    debug_log(f"[OVAL RECOVERY] Recovering Q{expected_first} top={top_px} bot={bot_px}")

    file_idx = process_question_crop(
        crop, page, rect, page_num,
        file_idx, out_dir, results,
        real_q_num=expected_first
    )
    return file_idx

# ═══════════════════════════════════════════════════════════════════════
# MAIN PROCESSING LOOP
# v8: page_from / page_to filter (0-based internally)
# ═══════════════════════════════════════════════════════════════════════

def process_pdf(pdf_path, out_dir, page_from=None, page_to=None):
    """
    page_from, page_to: 1-based inclusive page numbers (None = process all).
    """
    pdf_path = Path(pdf_path)
    out_dir  = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    doc     = fitz.open(str(pdf_path))
    total   = len(doc)
    results = []
    file_idx = 1   # sequential file counter — always 1, 2, 3, …

    # Convert to 0-based
    p_from = (page_from - 1) if page_from else 0
    p_to   = (page_to   - 1) if page_to   else (total - 1)
    p_from = max(0, min(p_from, total - 1))
    p_to   = max(0, min(p_to,   total - 1))

    print(f"\nProcessing pages {p_from+1}–{p_to+1} of {total} …\n")

    # Clear debug log at start
    open(DEBUG_FILE, "w").close()

    # ── STAGE 2: build top_results (one entry per page, first Q only) ──
    top_results = []
    for page_num in range(p_from, p_to + 1):
        page = doc[page_num]
        img  = page_to_image(page)
        if is_skip_page(img):
            continue
        top_q = extract_top_question_only(page, img, page_num, out_dir)
        if top_q:
            top_results.append(top_q)

    # ── STAGE 1: main pipeline (unchanged) ─────────────────────────────
    for page_num in range(p_from, p_to + 1):
        page = doc[page_num]
        print(f"─── Page {page_num + 1} ───────────────────────")
        debug_log(f"\n=== PAGE {page_num+1} ===")

        img = page_to_image(page)

        page_has_oval = detect_broken_top_questions(page)
        debug_log(f"[BROKEN TOP DETECTED] {page_has_oval}")

        if is_skip_page(img):
            print("  Skipped")
            continue

        # ── Try text-based split first (best: gives real numbers) ──
        segments = split_by_question_numbers(page, img)
        # validate_segments now handles 3-tuples
        if segments:
            valid = [s for s in segments if s[0].shape[0] > img.shape[0] * 0.04]
            segments = valid if valid else None

        if segments:
            debug_log(f"[TEXT SPLIT] Found {len(segments)} segments")
            print(f"  → text-based split ({len(segments)} questions)")
            for crop, rect, real_q_num in segments:
                if crop.shape[0] < 40:
                    continue
                file_idx = process_question_crop(
                    crop, page, rect, page_num,
                    file_idx, out_dir, results,
                    real_q_num=real_q_num)

            if page_has_oval:
                file_idx = recover_oval_first_question(
                    page, img, page_num, file_idx, out_dir, results)
            continue

        # ── Try line-based split ───────────────────────────────────
        line_segs = split_by_horizontal_lines(img, page)
        if line_segs:
            valid = [s for s in line_segs if s[0].shape[0] > img.shape[0] * 0.04]
            line_segs = valid if valid else None

        if line_segs:
            print(f"  → line-based split ({len(line_segs)} segments)")
            for crop, rect, real_q_num in line_segs:
                if crop.shape[0] < 40:
                    continue
                file_idx = process_question_crop(
                    crop, page, rect, page_num,
                    file_idx, out_dir, results,
                    real_q_num=real_q_num)
            continue

        # ── Fallback: box or green ─────────────────────────────────
        debug_log("[FALLBACK] Using BOX or GREEN")
        if is_box_format(img):
            print("  → box fallback")
            file_idx = process_box_page(img, page, page_num, file_idx, out_dir, results, page_has_oval)
        else:
            print("  → green fallback")
            file_idx = process_green_page(img, page, page_num, file_idx, out_dir, results)

        file_idx = recover_top_question(
            page, img, page_num,
            file_idx, out_dir, results
        )

    # ── STAGE 3: merge — insert any missing first questions ────────────
    print(f"\n── Merging top-question pass ({len(top_results)} entries) …")
    results = merge_first_questions(results, top_results)

    print(f"\n✓ Done — {len(results)} question images saved\n")
    return results


# ═══════════════════════════════════════════════════════════════════════
# ENTRY POINT
# ═══════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Split a question-bank PDF into per-question images."
    )
    parser.add_argument("pdf",  help="Input PDF path")
    parser.add_argument("out",  help="Output folder")
    parser.add_argument("--page-from", type=int, default=None,
                        help="First page to process (1-based, inclusive)")
    parser.add_argument("--page-to",   type=int, default=None,
                        help="Last page to process (1-based, inclusive)")
    args = parser.parse_args()

    results = process_pdf(
        args.pdf, args.out,
        page_from=args.page_from,
        page_to=args.page_to
    )

    output_json_path = Path(args.out) / "output.json"
    with open(output_json_path, "w") as f:
        json.dump(results, f, indent=2)

    print(f"JSON manifest saved → {output_json_path}")