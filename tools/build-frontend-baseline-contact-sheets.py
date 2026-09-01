from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
BASELINE_ROOT = REPOSITORY_ROOT / "docs" / "assets" / "frontend-baseline" / "2026-08-31"
MANIFEST_PATH = BASELINE_ROOT / "manifest.json"
OUTPUT_ROOT = BASELINE_ROOT / "contact-sheets"
TILE_WIDTH = 260
TILE_HEIGHT = 500
PREVIEW_WIDTH = 220
SEGMENT_HEIGHT = 136
GAP = 8
COLUMNS = 5


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = (
        Path("C:/Windows/Fonts/consola.ttf"),
        Path("C:/Windows/Fonts/arial.ttf"),
    )
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def page_preview(source: Image.Image) -> Image.Image:
    page = source.convert("RGB")
    scaled_height = max(1, round(page.height * PREVIEW_WIDTH / page.width))
    scaled = page.resize((PREVIEW_WIDTH, scaled_height), Image.Resampling.LANCZOS)
    available_height = TILE_HEIGHT - 58

    if scaled.height <= available_height:
        return ImageOps.pad(
            scaled,
            (PREVIEW_WIDTH, available_height),
            method=Image.Resampling.LANCZOS,
            color=(244, 242, 235),
            centering=(0.5, 0.0),
        )

    starts = (
        0,
        max(0, (scaled.height - SEGMENT_HEIGHT) // 2),
        max(0, scaled.height - SEGMENT_HEIGHT),
    )
    preview = Image.new(
        "RGB",
        (PREVIEW_WIDTH, SEGMENT_HEIGHT * 3 + GAP * 2),
        (244, 242, 235),
    )
    for index, start in enumerate(starts):
        crop = scaled.crop((0, start, PREVIEW_WIDTH, start + SEGMENT_HEIGHT))
        preview.paste(crop, (0, index * (SEGMENT_HEIGHT + GAP)))
    return preview


def build_sheet(pages: list[dict[str, object]], output_path: Path) -> None:
    rows = math.ceil(len(pages) / COLUMNS)
    sheet = Image.new(
        "RGB",
        (COLUMNS * TILE_WIDTH, rows * TILE_HEIGHT),
        (231, 228, 219),
    )
    draw = ImageDraw.Draw(sheet)
    font = load_font(15)

    for index, page in enumerate(pages):
        column = index % COLUMNS
        row = index // COLUMNS
        x = column * TILE_WIDTH
        y = row * TILE_HEIGHT
        source_path = REPOSITORY_ROOT / str(page["file"])
        with Image.open(source_path) as source:
            preview = page_preview(source)
        draw.rectangle(
            (x + 8, y + 8, x + TILE_WIDTH - 8, y + TILE_HEIGHT - 8),
            fill=(250, 249, 245),
            outline=(169, 174, 160),
            width=1,
        )
        draw.text((x + 20, y + 18), str(page["id"]), fill=(33, 54, 49), font=font)
        sheet.paste(preview, (x + 20, y + 50))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output_path, "JPEG", quality=88, optimize=True)


def main() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    pages = manifest["pages"]
    for viewport in ("desktop", "mobile"):
        for entry in ("storefront", "admin"):
            selected = [
                page for page in pages
                if page["viewport"] == viewport and page["entry"] == entry
            ]
            build_sheet(selected, OUTPUT_ROOT / f"{viewport}-{entry}.jpg")
    print(f"Built 4 contact sheets in {OUTPUT_ROOT}")


if __name__ == "__main__":
    main()
