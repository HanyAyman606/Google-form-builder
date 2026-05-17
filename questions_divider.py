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


# ── Windows UTF-8 fix ────────────────────────────────────────────────────────
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

import fitz          # PyMuPDF
import cv2
import numpy as np

# ==============================
# SECTION DETECTOR (NEW)
# ==============================

USE_STRUCTURAL_PIPELINE = True
ENABLE_EMERGENCY_RECOVERY = False

def classify_page_layout(image):
    h, w = image.shape[:2]
    image_area = h * w
    
    border_mask = build_border_mask(image)
    box_density = int(border_mask.sum()) // 255
    
    green_lines = detect_green_lines(image)
    green_line_count = len(green_lines) if green_lines else 0
    
    dynamic_box_thresh = image_area * 0.0025
    
    is_box = box_density > dynamic_box_thresh
    is_green = green_line_count > 0
    
    if is_box and is_green:
        return "MIXED_LAYOUT"
    elif is_box:
        return "BOX_LAYOUT"
    elif is_green:
        return "GREEN_LAYOUT"
    else:
        return "UNKNOWN_LAYOUT"


def split_merged_container_if_needed(container, page_img, median_height, page):
    h_page, w_page = page_img.shape[:2]
    ch = container["h"]
    cw = container["w"]
    cx = container["x"]
    cy = container["y"]
    orig_conf = container.get("confidence", 1.0)
    
    if ch > median_height * 1.8 or ch > h_page * 0.45:
        debug_log(f"[MERGE CHECK] Suspicious container detected (h={ch}, median={median_height})")
        
        search_start = int(ch * 0.25)
        if search_start >= ch:
            return [container]
            
        lower_roi = page_img[cy + search_start : cy + ch, cx : cx + cw]
        search_h = min(int(ch * 0.35), 450)
        header_info = detect_header_assembly(lower_roi, page, cx, cy + search_start, override_roi_h=search_h)
        
        if header_info["oval_detected"] and header_info["circle_detected"]:
            # Rule A: Secondary header width
            sec_oval_x = header_info["oval_box"]["x"]
            sec_oval_w = header_info["oval_box"]["w"]
            sec_circle_x = header_info["circle_box"]["x"]
            sec_circle_w = header_info["circle_box"]["w"]
            sec_min_x = min(sec_oval_x, sec_circle_x)
            sec_max_x = max(sec_oval_x + sec_oval_w, sec_circle_x + sec_circle_w)
            secondary_header_width = sec_max_x - sec_min_x
            
            if secondary_header_width <= cw * 0.08:
                return [container]
                
            sec_oval_y = header_info["oval_box"]["y"]
            sec_circle_y = header_info["circle_box"]["y"]
            header_y = min(sec_oval_y, sec_circle_y) + search_start
            
            # Rule B: Not too close to bottom
            if header_y > ch * 0.90:
                return [container]
            
            # Check first question number
            top_roi = page_img[cy : cy + ch, cx : cx + cw]
            first_header_info = detect_header_assembly(top_roi, page, cx, cy)
            
            first_num = first_header_info["circle_box"]["text"] if first_header_info["circle_detected"] else "UNKNOWN1"
            second_num = header_info["circle_box"]["text"]
            
            if header_y > ch * 0.30 and first_num != second_num:
                debug_log(f"[SECONDARY HEADER FOUND] First: {first_num}, Second: {second_num}")
                
                split_y = max(0, header_y - 20)
                
                if 0 < split_y < ch:
                    # WHITESPACE VALIDATION
                    gray_top_roi = cv2.cvtColor(top_roi, cv2.COLOR_BGR2GRAY)
                    row_darkness = np.mean(gray_top_roi < 240, axis=1)
                    
                    scan_top = max(0, split_y - 20)
                    scan_bot = min(ch, split_y + 20)
                    
                    if scan_bot > scan_top:
                        region_darkness = row_darkness[scan_top:scan_bot]
                        clean_rows = np.sum(region_darkness < 0.03)
                        
                        if clean_rows < 3:
                            debug_log("[MERGE CHECK] Rejected split due to lack of whitespace band.")
                            return [container]
                            
                    upper_container = {
                        "x": cx,
                        "y": cy,
                        "w": cw,
                        "h": split_y,
                        "contour": None,
                        "confidence": orig_conf * 0.90,
                        "splitFromMerged": True
                    }
                    lower_container = {
                        "x": cx,
                        "y": cy + split_y,
                        "w": cw,
                        "h": ch - split_y,
                        "contour": None,
                        "confidence": orig_conf * 0.90,
                        "splitFromMerged": True
                    }
                    debug_log("[CONTAINER SPLIT] Successfully split merged container")
                    return [upper_container, lower_container]
                    
    return [container]


def extract_structural_containers(image, page):
    h, w = image.shape[:2]
    image_area = h * w
    mask = build_border_mask(image)
    
    kernel_h = (max(20, w // 80), 1)
    kernel_v = (1, max(6, h // 300))
    kh = cv2.getStructuringElement(cv2.MORPH_RECT, kernel_h)
    kv = cv2.getStructuringElement(cv2.MORPH_RECT, kernel_v)
    
    closed = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kh)
    closed = cv2.morphologyEx(closed, cv2.MORPH_CLOSE, kv)
    
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    containers = []
    for cnt in contours:
        area = cv2.contourArea(cnt)
        if area < image_area * 0.02:
            continue
            
        bx, by, bw, bh = cv2.boundingRect(cnt)
        
        if bw < w * 0.50:
            continue
            
        if not (0.05 < bh / h < 0.60):
            continue
            
        if bh > h * 0.75:
            continue
            
        containers.append({
            "x": bx,
            "y": by,
            "w": bw,
            "h": bh,
            "contour": cnt,
            "confidence": 1.0
        })
        
    containers.sort(key=lambda c: c["y"])
    
    if containers:
        heights = [c["h"] for c in containers]
        median_height = np.median(heights)
        
        final_containers = []
        for c in containers:
            splits = split_merged_container_if_needed(c, image, median_height, page)
            final_containers.extend(splits)
            
        final_containers.sort(key=lambda c: c["y"])
        return final_containers
        
    return containers


def detect_header_assembly(container_crop, page, bx_px, by_px, override_roi_h=None):
    h, w = container_crop.shape[:2]
    roi_h = override_roi_h if override_roi_h is not None else max(int(h * 0.18), 120)
    roi_w = max(int(w * 0.35), 220)
    
    if roi_h == 0 or roi_w == 0:
        return {"oval_detected": False, "circle_detected": False, "header_roi_h": int(h * 0.12), "header_roi_w": roi_w}
        
    roi_area = roi_h * roi_w
    header_roi = container_crop[0:roi_h, 0:roi_w]
    
    def extract_semantic_anchors(contours, log_prefix):
        oval_c = None
        circle_c = None
        best_o_area = 0
        best_c_area = 0
        
        for cnt in contours:
            area = cv2.contourArea(cnt)
            x, y, cw, ch = cv2.boundingRect(cnt)
            if ch == 0: continue
            aspect_ratio = cw / ch
            
            if 1.8 < aspect_ratio < 4.5 and area > roi_area * 0.01:
                oval_rect_pt = px_rect_to_pt_rect(page, bx_px + x, by_px + y, cw, ch)
                oval_text = page.get_text("text", clip=oval_rect_pt).strip().lower()
                valid_keywords = ["exp", "egypt", "test", "hw", "exam", "chapter", "lesson"]
                if any(kw in oval_text for kw in valid_keywords) or re.search(r'\bexp\b', oval_text):
                    digit_count = sum(c.isdigit() for c in oval_text)
                    symbol_count = len(re.findall(r'[^a-z0-9\s]', oval_text))
                    if digit_count <= 4 and symbol_count <= 3:
                        if area > best_o_area:
                            oval_c = {"x": x, "y": y, "w": cw, "h": ch, "text": oval_text}
                            best_o_area = area
                            debug_log(f"{log_prefix} OVAL FOUND: {oval_text}")
                
            if 0.75 < aspect_ratio < 1.25 and 15 <= cw <= 120:  
                circle_rect_pt = px_rect_to_pt_rect(page, bx_px + x, by_px + y, cw, ch)
                circle_text = page.get_text("text", clip=circle_rect_pt).strip()
                num_match = re.search(r'\d{1,3}', circle_text)
                if num_match:
                    parsed_num = int(num_match.group())
                    if 1 <= parsed_num <= 999:
                        if area > best_c_area:
                            circle_c = {"x": x, "y": y, "w": cw, "h": ch, "text": str(parsed_num)}
                            best_c_area = area
                            debug_log(f"{log_prefix} CIRCLE FOUND: {parsed_num}")
                            
        return oval_c, circle_c

    debug_log("[HSV HEADER DETECTION] Starting structural mask scan")
    hsv = cv2.cvtColor(header_roi, cv2.COLOR_BGR2HSV)
    combined_mask = np.zeros(header_roi.shape[:2], dtype=np.uint8)
    for lo, hi, _ in BOX_BORDER_COLORS:
        combined_mask = cv2.bitwise_or(combined_mask, cv2.inRange(hsv, lo, hi))
        
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2))
    clean_mask = cv2.morphologyEx(combined_mask, cv2.MORPH_OPEN, kernel)
    clean_mask = cv2.morphologyEx(clean_mask, cv2.MORPH_CLOSE, kernel)
    
    contours, _ = cv2.findContours(clean_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    oval_contour, number_circle_contour = extract_semantic_anchors(contours, "[HSV]")
    
    if not oval_contour and not number_circle_contour:
        debug_log("[HSV FALLBACK TO EDGE] No structural mask found, using old Canny detection")
        gray = cv2.cvtColor(header_roi, cv2.COLOR_BGR2GRAY)
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        edges = cv2.Canny(blurred, 50, 150)
        edges = cv2.dilate(edges, kernel, iterations=1)
        edge_contours, _ = cv2.findContours(edges, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
        
        oval_contour, number_circle_contour = extract_semantic_anchors(edge_contours, "[EDGE]")

    return {
        "oval_detected": oval_contour is not None,
        "circle_detected": number_circle_contour is not None,
        "oval_box": oval_contour,
        "circle_box": number_circle_contour,
        "header_roi_h": roi_h,
        "header_roi_w": roi_w
    }


def partition_regions(container_crop, header_info):
    h, w = container_crop.shape[:2]
    
    header_h = int(h * 0.15)
    if header_info["oval_detected"] and header_info["oval_box"]:
        oval_bottom = header_info["oval_box"]["y"] + header_info["oval_box"]["h"]
        padding = 10
        header_h = max(oval_bottom + padding, int(h * 0.15))
    elif header_info["circle_detected"] and header_info["circle_box"]:
        circle_bottom = header_info["circle_box"]["y"] + header_info["circle_box"]["h"]
        padding = 10
        header_h = max(circle_bottom + padding, int(h * 0.15))
        
    footer_start = int(h * 0.94)
    
    dot_top = _find_dot_region_top(container_crop)
    if dot_top is not None:
        answer_start = dot_top
    else:
        answer_start = int(h * 0.40)
        
    answer_start = max(header_h, min(answer_start, footer_start))
    
    return {
        "header": {"y0": 0, "y1": header_h},
        "body": {"y0": header_h, "y1": answer_start},
        "answer": {"y0": answer_start, "y1": footer_start},
        "footer": {"y0": footer_start, "y1": h}
    }




def process_structural_container(crop, page, rect_pt, rect_px, page_num, file_idx, out_dir, results, is_top_question=False, visual_order=1, override_q_num=0, is_continuation=False):
    global GLOBAL_VISUAL_ORDER
    header_info = detect_header_assembly(crop, page, rect_px["x"], rect_px["y"])
    regions = partition_regions(crop, header_info)
    
    # Calculate pt heights using ZOOM
    mat = fitz.Matrix(ZOOM, ZOOM)
    pix = page.get_pixmap(matrix=mat, alpha=False)
    sy = page.rect.height / pix.height
    
    header_pt_h = regions["header"]["y1"] * sy
    body_pt_h = (regions["body"]["y1"] - regions["header"]["y0"]) * sy
    
    answer_start_pt = rect_pt.y0 + regions["answer"]["y0"] * sy
    
    header_y1_pt = rect_pt.y0 + header_pt_h
    sub_crops = split_box_by_roman_fitz(page, rect_pt, crop, header_y1_pt, answer_start_pt)
    has_roman = sub_crops is not None
    
    header_rect = fitz.Rect(rect_pt.x0, rect_pt.y0, rect_pt.x1, rect_pt.y0 + header_pt_h)
    body_rect = fitz.Rect(rect_pt.x0, rect_pt.y0 + header_pt_h, rect_pt.x1, rect_pt.y0 + body_pt_h)
    
    if is_continuation:
        header_text = ""
        body_text = ""
    else:
        header_text = page.get_text("text", clip=header_rect).strip()
        body_text = page.get_text("text", clip=body_rect).strip()
    
    if override_q_num > 0:

        # Continuation pages inherit parent number
        real_q_num = override_q_num

    else:

        real_q_num = extract_question_number_from_text(header_text) or 0

        if real_q_num == 0:
            real_q_num = extract_question_number_from_text(body_text) or 0
        
    oval_text = header_info["oval_box"]["text"] if header_info["oval_detected"] else ""
        
    q_type = classify_question(crop, body_text)
    
    GLOBAL_VISUAL_ORDER += 1
    
    entry = {
        "id": f"q_{file_idx}",
        "page": page_num + 1,
        "visualOrder": GLOBAL_VISUAL_ORDER,
        "questionNumber": real_q_num,
        "layoutType": "oval_header_box" if header_info["oval_detected"] else "plain_box",
        "questionType": q_type,
        "hasOval": header_info["oval_detected"],
        "ovalText": oval_text,
        "isTopQuestion": is_top_question,
        "regions": {
            "questionBoxPx": {"x": rect_px["x"], "y": rect_px["y"], "w": rect_px["w"], "h": rect_px["h"]},
            "questionBoxPt": {"x": rect_pt.x0, "y": rect_pt.y0, "w": rect_pt.width, "h": rect_pt.height},
            "headerRegion": regions["header"],
            "bodyRegion": regions["body"],
            "answerRegion": regions["answer"]
        },
        "features": {
            "hasGraph": False,
            "hasTable": False,
            "hasRomanParts": has_roman,
            "hasChoices": q_type == "mcq"
        },
        "confidence": {
            "container": 0.95,
            "header": 0.90 if header_info["oval_detected"] else 0.50,
            "number": 0.95 if header_info["circle_detected"] else 0.50
        },
        "headerOCR": {
            "ovalText": oval_text,
            "numberText": header_info["circle_box"]["text"] if header_info["circle_detected"] else str(real_q_num)
        },
        "ocr": {
            "headerText": header_text,
            "bodyText": body_text,
            "answerText": ""
        },
        "subparts": [],
        "detectedBy": ["box_contour", "structural_pipeline"],
        "image": str(out_dir / f"q_{file_idx}.png"),
        "parentNumber": 0,
        "subLabel": "",
        "orderY": int(rect_pt.y0)
    }
    if has_roman:
        import copy

        for sub_index, (sub_crop, label, sub_rect_pt) in enumerate(sub_crops):

            child_entry = copy.deepcopy(entry)

            # ==================================================
            # Use direct PyMuPDF text extraction per sub-rectangle
            # NOT OCR — the PDF is digitally generated
            # ==================================================

            sub_text = page.get_text(
                "text",
                clip=sub_rect_pt
            ).strip()

            sub_lines = [
                ln.strip()
                for ln in sub_text.splitlines()
                if ln.strip()
            ]

            header_text = sub_lines[0] if sub_lines else ""

            body_text = "\n".join(sub_lines[1:]).strip()

            child_entry["ocr"] = {
                "headerText": header_text,
                "bodyText": body_text,
                "answerText": ""
            }

            # Roman children are not top-level containers
            child_entry["isTopQuestion"] = False

            # Preserve stable ordering
            GLOBAL_VISUAL_ORDER += 1
            child_entry["visualOrder"] = GLOBAL_VISUAL_ORDER

            child_entry["id"] = f"q_{file_idx}"

            # Parent ownership
            child_entry["parentNumber"] = real_q_num

            # Roman child keeps parent question number
            child_entry["questionNumber"] = real_q_num

            # Roman index (i / ii / iii / iv)
            child_entry["subLabel"] = label

            child_entry["image"] = str(out_dir / f"q_{file_idx}.png")
            child_entry["subparts"] = []
            
            out_path = out_dir / f"q_{file_idx}.png"
            cv2.imwrite(str(out_path), sub_crop)
            
            results.append(child_entry)
            debug_log(f"[STRUCTURAL SAVE ROMAN] Q{real_q_num} {label} on page {page_num+1} order={visual_order}")
            file_idx += 1
            
        return file_idx
    else:
        out_path = out_dir / f"q_{file_idx}.png"
        cv2.imwrite(str(out_path), crop)
        
        results.append(entry)
        debug_log(f"[STRUCTURAL SAVE] Q{real_q_num} on page {page_num+1} order={visual_order}")
        
        return file_idx + 1


def detect_section_header(page):
    blocks = page.get_text("dict")["blocks"]

    page_height = page.rect.height
    top_limit = page_height * 0.30

    candidates = []

    for b in blocks:
        if "lines" not in b:
            continue

        for line in b["lines"]:
            spans = line["spans"]
            if not spans:
                continue

            span = spans[0]

            text = span["text"].strip()
            size = span["size"]
            y0   = span["bbox"][1]

            # only top area
            if y0 > top_limit:
                continue

            if not text:
                continue

            # ignore junk
            t = text.lower()
            if "perfection" in t or "final revision" in t or "eng ahmed" in t:
                continue

            if len(text) < 5 or len(text) > 60:
                continue

            if any(c.isdigit() for c in text):
                continue

            words = text.split()
            if not (2 <= len(words) <= 6):
                continue

            candidates.append({
                "text": text,
                "size": size,
                "y": y0
            })

    if not candidates:
        return None

    # biggest font wins
    candidates.sort(key=lambda x: (-x["size"], x["y"]))
    return candidates[0]["text"]

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

ACTIVE_PARENT_QUESTION = None
GLOBAL_VISUAL_ORDER = 0

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


def detect_continuation_page(page):
    """
    Detect pages that begin with roman subquestions
    but do not begin with a new main question number.

    Example:
        ii. Holes Concentration

    without:
        27 ...
    """

    top_clip = fitz.Rect(
        0,
        0,
        page.rect.width,
        page.rect.height * 0.25
    )

    top_text = page.get_text("text", clip=top_clip)

    starts_with_roman = re.search(
        r'^\s*(i|ii|iii|iv|v|vi|vii|viii|ix|x)[.)\s]',
        top_text,
        re.I | re.M
    )

    has_main_question = re.search(
        r'^\s*\d{1,3}\s+[A-Za-z(]',
        top_text,
        re.M
    )

    return bool(starts_with_roman and not has_main_question)




def find_roman_splits_fitz(page, box_rect_pt):
    blocks = page.get_text("dict", clip=fitz.Rect(box_rect_pt))["blocks"]
    
    # Collect all lines with their bounding boxes first
    all_lines = []
    min_x = 9999
    max_x = 0
    
    for b in blocks:
        if "lines" not in b:
            continue
        for line in b["lines"]:
            text = "".join([s["text"] for s in line["spans"]]).strip()
            if not text:
                continue
            x0, y0, x1, y1 = line["bbox"]
            if x0 < min_x:
                min_x = x0
            if x1 > max_x:
                max_x = x1
            all_lines.append({
                "text": text,
                "x0": x0, "y0": y0, "x1": x1, "y1": y1
            })
    
    # Sort all lines vertically
    all_lines.sort(key=lambda ln: ln["y0"])
    
    candidates = []
    prev_line_bottom = box_rect_pt.y0  # default: box top
    
    for idx, ln in enumerate(all_lines):
        m = ROMAN_PAT.match(ln["text"])
        if m:
            label = _normalise_roman(m.group(1))

            relative_y = ln["y0"] - box_rect_pt.y0
            box_h = box_rect_pt.height

            if relative_y < box_h * 0.03:
                prev_line_bottom = ln["y1"]
                continue

            if relative_y > box_h * 0.92:
                continue

            candidates.append({
                "label": label,
                "x": ln["x0"],
                "y": ln["y0"],
                "stem_end_y": prev_line_bottom,
                "lineText": ln["text"],
                "wordCount": len(ln["text"].split())
            })
        else:
            # Non-roman line: update prev_line_bottom
            prev_line_bottom = ln["y1"]
                
    return candidates, min_x, max_x


def split_box_by_roman_fitz(page, box_rect_pt, box_img, header_y1_pt=0, answer_y0_pt=99999):
    res = find_roman_splits_fitz(page, box_rect_pt)
    if not res:
        return None
        
    candidates, min_x, max_x = res
    if not candidates:
        return None

    roman_splits = [
        (c["y"], c["label"])
        for c in candidates
    ]

    roman_splits.sort(key=lambda x: x[0])

    if not roman_splits:
        return None

    box_top_pt = box_rect_pt.y0
    box_bot_pt = box_rect_pt.y1
    box_h_pt   = box_bot_pt - box_top_pt
    box_h_px   = box_img.shape[0]
    scale      = box_h_px / box_h_pt if box_h_pt > 0 else ZOOM

    # Use the bottom of the last non-roman line before the first anchor
    first_roman = candidates[0]
    stem_end_y = first_roman.get("stem_end_y", box_top_pt)
    stem_bot_pt = max(box_top_pt, stem_end_y + 4)
    stem_bot_px = int((stem_bot_pt - box_top_pt) * scale)

    header_bottom_px = 0

    stem_crop = box_img[
        header_bottom_px:stem_bot_px,
        :
    ]

    boundaries_pt = [y for y, _ in roman_splits] + [box_bot_pt]
    result = []
    PAD = 4
    for i in range(len(roman_splits)):
        top_px = max(
            0,
            int((roman_splits[i][0] - box_top_pt) * scale)
        )

        if i == 0:
            top_px = max(0, top_px - 10)

        if i + 1 < len(roman_splits):
            bot_px = min(
                box_h_px,
                int((boundaries_pt[i+1] - box_top_pt) * scale) - PAD
            )
        else:
            bot_px = min(
                box_h_px,
                int((boundaries_pt[i+1] - box_top_pt) * scale)
            )

        sub = box_img[top_px:bot_px, :]
        if sub.shape[0] < 20:
            continue

        if i == 0:
            combined = (
                np.vstack([stem_crop, sub])
                if stem_crop.shape[0] > 0
                else sub
            )
        else:
            combined = sub

        # Compute the exact PDF rectangle for this roman child
        sub_top_pt = roman_splits[i][0]
        if i + 1 < len(roman_splits):
            sub_bot_pt = boundaries_pt[i + 1]
        else:
            sub_bot_pt = box_bot_pt

        LEFT_PAD_PT = 8
        RIGHT_PAD_PT = 12

        sub_rect_pt = fitz.Rect(
            max(box_rect_pt.x0, min_x - LEFT_PAD_PT),
            sub_top_pt,
            min(box_rect_pt.x1, max_x + RIGHT_PAD_PT),
            sub_bot_pt
        )

        label = roman_splits[i][1]
        result.append((combined, label, sub_rect_pt))

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
          real_q_num=0, parent_number=0, sub_label="", order_y=0):
    global GLOBAL_VISUAL_ORDER
    """
    file_idx        – sequential file counter (q_1.png, q_2.png …)
    real_q_num      – actual question number from PDF (7, 10, 33 …)
    parent_number   – for sub-questions: the parent's real number; 0 for standalone
    sub_label       – "i", "ii", … or "" for standalone
    order_y         – physical vertical position of the crop for sorting
    """
    filename = f"q_{file_idx}.png"
    out_path  = out_dir / filename
    cv2.imwrite(str(out_path), crop)
    GLOBAL_VISUAL_ORDER += 1

    entry = {
        "id":             f"q_{file_idx}",
        "type":           q_type,
        "image":          str(out_path),
        "page":           page_num + 1,
        "visualOrder":    GLOBAL_VISUAL_ORDER,
        "questionNumber": real_q_num,    # ← NEW: real number from PDF
        "parentNumber":   parent_number,
        "subLabel":       sub_label,
        "orderY":         order_y,
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

    order_y = int(box_rect_pt.y0) if box_rect_pt is not None else 0

    if q_type == "written":
        clean = remove_answer_region(crop)
        n_sub = count_written_subparts(fitz_text)
        for _ in range(n_sub):
            _save(clean, file_idx, "written", out_dir, results, page_num,
                  real_q_num=real_q_num, parent_number=0, sub_label="", order_y=order_y)
            file_idx += 1
    else:
        # Try roman numeral sub-questions
        sub_crops = None
        if box_rect_pt is not None:
            sub_crops = split_box_by_roman_fitz(page, box_rect_pt, crop)

        if sub_crops:
            # Parent number = the real question number of this box
            parent_num = real_q_num
            for sub_crop, sub_label, _ in sub_crops:
                _save(sub_crop, file_idx, "mcq", out_dir, results, page_num,
                      real_q_num=parent_num, parent_number=parent_num,
                      sub_label=sub_label, order_y=order_y)
                file_idx += 1
        else:
            _save(crop, file_idx, "mcq", out_dir, results, page_num,
                  real_q_num=real_q_num, parent_number=0, sub_label="", order_y=order_y)
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

        # ── SAFE: Never physically crop the header geometry anymore.
        # Destructive shift cropping has been removed.

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
        "id": "",
        "type": "mcq",
        "image": str(path),
        "page": page_num + 1,
        "questionNumber": first_num,
        "parentNumber": 0,
        "subLabel": "",
        "orderY": 0,
    }




# ═══════════════════════════════════════════════════════════════════════
# MAIN PROCESSING LOOP
# v8: page_from / page_to filter (0-based internally)
# ═══════════════════════════════════════════════════════════════════════

def process_pdf(pdf_path, out_dir, page_from=None, page_to=None):
    """
    page_from, page_to: 1-based inclusive page numbers (None = process all).
    """
    global ACTIVE_PARENT_QUESTION
    global GLOBAL_VISUAL_ORDER
    pdf_path = Path(pdf_path)
    out_dir  = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    doc     = fitz.open(str(pdf_path))
    total   = len(doc)
    results = []
    file_idx = 1   # sequential file counter — always 1, 2, 3, …
    sections = []
    current_section = None

    # Convert to 0-based
    p_from = (page_from - 1) if page_from else 0
    p_to   = (page_to   - 1) if page_to   else (total - 1)
    p_from = max(0, min(p_from, total - 1))
    p_to   = max(0, min(p_to,   total - 1))

    print(f"\nProcessing pages {p_from+1}–{p_to+1} of {total} …\n")

    # Clear debug log at start
    open(DEBUG_FILE, "w").close()

    # STAGE 2: Removed top_results global pass entirely.

    # ── STAGE 1: main pipeline (unchanged) ─────────────────────────────
    for page_num in range(p_from, p_to + 1):
        page = doc[page_num]

        # ===== SECTION DETECTION =====
        header = detect_section_header(page)

        if header:
            if current_section:
                current_section["end_page"] = page_num

            current_section = {
                "section": header,
                "start_page": page_num + 1,
                "end_page": page_num + 1
            }
            sections.append(current_section)

        print(f"─── Page {page_num + 1} ───────────────────────")
        debug_log(f"\n=== PAGE {page_num+1} ===")

        img = page_to_image(page)

        if is_skip_page(img):
            print("  Skipped")
            continue

        continuation_page = detect_continuation_page(page)
        if continuation_page:
            debug_log(f"[CONTINUATION] Page {page_num+1} detected as continuation")

        if USE_STRUCTURAL_PIPELINE:
            layout = classify_page_layout(img)
            debug_log(f"[LAYOUT] {layout}")
            
            containers = []
            if layout in ("BOX_LAYOUT", "MIXED_LAYOUT"):
                containers = extract_structural_containers(img, page)
                if containers:
                    # Handle continuation pages: inherit parent question number
                    cont_q_num = 0
                    if continuation_page:
                        if ACTIVE_PARENT_QUESTION is not None:
                            cont_q_num = ACTIVE_PARENT_QUESTION
                            debug_log(f"[CONTINUATION] Inheriting Q{cont_q_num} from active parent")

                    print(f"  → structural container split ({len(containers)} containers)")
                    for i, c in enumerate(containers):
                        crop = img[c["y"]:c["y"]+c["h"], c["x"]:c["x"]+c["w"]]
                        rect = px_rect_to_pt_rect(page, c["x"], c["y"], c["w"], c["h"])
                        file_idx = process_structural_container(
                            crop, page, rect, c, page_num,
                            file_idx, out_dir, results,
                            is_top_question=(i == 0),
                            override_q_num=cont_q_num if continuation_page else 0,
                            is_continuation=continuation_page)

                    continue
            elif layout == "GREEN_LAYOUT":
                print("  → green layout split")
                file_idx = process_green_page(img, page, page_num, file_idx, out_dir, results)
                continue

        # ── EMERGENCY FALLBACK: Try text-based split ──
        debug_log("[FALLBACK] Using text split")
        segments = split_by_question_numbers(page, img)
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


            continue

        # ── FINAL FALLBACK: Try line-based split ──
        debug_log("[FALLBACK] Using line split")
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
            
        if ENABLE_EMERGENCY_RECOVERY:
            file_idx = recover_top_question(
                page, img, page_num,
                file_idx, out_dir, results
            )

        # Update active parent question tracking (State tracking for continuation pages)
        if not continuation_page:
            if results:
                last = results[-1]
                if last.get("questionNumber"):
                    ACTIVE_PARENT_QUESTION = last["questionNumber"]

    # ── STAGE 3: removed top-question merge ────────────

    print(f"\n✓ Done — {len(results)} question images saved\n")
    # finalize last section
    if current_section:
        current_section["end_page"] = p_to + 1

    return results, sections


# ═══════════════════════════════════════════════════════════════════════
# OUTPUT FORMATTING
# ═══════════════════════════════════════════════════════════════════════

def finalize_results(results):
    seen = set()
    out = []

    for q in sorted(results, key=lambda x: (x["page"], x.get("orderY", 0), x["id"])):
        key = (q["image"], q["subLabel"], q["parentNumber"])

        # remove only true duplicates on the same page
        if key in seen:
            continue

        seen.add(key)
        out.append(q)

    for i, q in enumerate(out, start=1):
        q["id"] = f"q_{i}"

    return out

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

    results, sections = process_pdf(
        args.pdf, args.out,
        page_from=args.page_from,
        page_to=args.page_to
    )

    results = finalize_results(results)

    output_json_path = Path(args.out) / "output.json"
    with open(output_json_path, "w") as f:
        json.dump(results, f, indent=2)

    print(f"JSON manifest saved → {output_json_path}")

    sections_path = Path(args.out) / "sections.json"

    with open(sections_path, "w", encoding="utf-8") as f:
        json.dump(sections, f, indent=2, ensure_ascii=False)

    print(f"Sections saved → {sections_path}")