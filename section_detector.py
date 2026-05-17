import fitz
import json
import re


# ==============================
# CONFIG (you can tune later)
# ==============================

HEADER_KEYWORDS = [
    "black body",
    "compton",
    "photoelectric",
    "photo electric",
    "wave particle",
]


# ==============================
# TEXT CLEANING
# ==============================

def normalize(text):
    text = text.lower()
    text = re.sub(r'\s+', ' ', text)
    return text.strip()


# ==============================
# HEADER DETECTION
# ==============================

def is_header(text):
    t = normalize(text)

    # ignore junk
    if len(t) < 5:
        return False

    # strong match
    for k in HEADER_KEYWORDS:
        if k in t:
            return True

    # fallback rule (short title-like)
    words = t.split()
    if 2 <= len(words) <= 5 and len(t) < 50:
        return True

    return False


# ==============================
# GET HEADER FROM PAGE
# ==============================

def detect_header(page):
    blocks = page.get_text("blocks")

    page_height = page.rect.height
    top_limit = page_height * 0.25   # only top area

    candidates = []

    for b in blocks:
        x0, y0, x1, y1, text, *_ = b

        if y0 > top_limit:
            continue

        text = text.strip()
        if not text:
            continue

        if is_header(text):
            candidates.append((y0, text))

    if not candidates:
        return None

    candidates.sort(key=lambda x: x[0])  # top-most
    return candidates[0][1]


# ==============================
# MAIN SECTION EXTRACTION
# ==============================

def extract_sections(pdf_path):
    doc = fitz.open(pdf_path)

    sections = []
    current = None

    for i, page in enumerate(doc):
        header = detect_header(page)

        if header:
            if current:
                current["end_page"] = i

            current = {
                "section": header,
                "start_page": i + 1,
                "end_page": i + 1
            }
            sections.append(current)

    # close last
    if current:
        current["end_page"] = len(doc)

    return sections


# ==============================
# SAVE
# ==============================

def save(sections, path="sections.json"):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(sections, f, indent=2, ensure_ascii=False)


# ==============================
# RUN
# ==============================

if __name__ == "__main__":
    pdf = "input.pdf"

    sections = extract_sections(pdf)
    save(sections)

    print("\nDetected Sections:")
    for s in sections:
        print(s)