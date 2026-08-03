from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps, features


FRONTEND_ROOT = Path(__file__).resolve().parents[1]
MAX_VARIANT_BYTES = 256 * 1024


@dataclass(frozen=True)
class ImageTask:
    source: str
    expected_size: tuple[int, int]
    widths: tuple[int, ...]


TASKS = (
    ImageTask(
        "storefront-web/public/images/catalog/canvas-commuter-tote.png",
        (1122, 1402),
        (480, 800, 1122),
    ),
    ImageTask(
        "storefront-web/public/images/catalog/mist-blue-notebook.png",
        (1122, 1402),
        (480, 800, 1122),
    ),
    ImageTask(
        "storefront-web/src/assets/fulfillment/qinghe-parcel-route.png",
        (1672, 941),
        (640, 1024, 1672),
    ),
)


def variant_path(source: Path, width: int, extension: str) -> Path:
    return source.with_name(f"{source.stem}-{width}.{extension}")


def expected_height(size: tuple[int, int], width: int) -> int:
    return round(size[1] * width / size[0])


def require_encoders() -> None:
    missing = [
        name
        for name in ("webp", "avif")
        if not features.check(name)
    ]
    if missing:
        raise RuntimeError(
            "Pillow is missing required encoders: " + ", ".join(missing)
        )


def generate() -> None:
    require_encoders()

    for task in TASKS:
        source = FRONTEND_ROOT / task.source
        with Image.open(source) as opened:
            image = ImageOps.exif_transpose(opened).convert("RGB")
            if image.size != task.expected_size:
                raise RuntimeError(
                    f"{task.source} expected {task.expected_size}, got {image.size}"
                )

            for width in task.widths:
                height = expected_height(task.expected_size, width)
                resized = (
                    image.copy()
                    if image.size == (width, height)
                    else image.resize((width, height), Image.Resampling.LANCZOS)
                )
                resized.save(
                    variant_path(source, width, "avif"),
                    format="AVIF",
                    quality=64,
                    speed=6,
                )
                resized.save(
                    variant_path(source, width, "webp"),
                    format="WEBP",
                    quality=84,
                    method=6,
                )


def check() -> None:
    require_encoders()
    total_bytes = 0

    for task in TASKS:
        source = FRONTEND_ROOT / task.source
        with Image.open(source) as original:
            if original.size != task.expected_size:
                raise RuntimeError(
                    f"{task.source} expected {task.expected_size}, got {original.size}"
                )

        for width in task.widths:
            size = (width, expected_height(task.expected_size, width))
            for extension, expected_format in (
                ("avif", "AVIF"),
                ("webp", "WEBP"),
            ):
                target = variant_path(source, width, extension)
                if not target.is_file():
                    raise RuntimeError(f"missing image variant: {target}")
                target_bytes = target.stat().st_size
                if target_bytes > MAX_VARIANT_BYTES:
                    raise RuntimeError(
                        f"{target} is {target_bytes} bytes; "
                        f"budget is {MAX_VARIANT_BYTES}"
                    )
                with Image.open(target) as variant:
                    if variant.size != size:
                        raise RuntimeError(
                            f"{target} expected {size}, got {variant.size}"
                        )
                    if variant.format != expected_format:
                        raise RuntimeError(
                            f"{target} expected {expected_format}, got {variant.format}"
                        )
                total_bytes += target_bytes
                print(
                    f"{target.relative_to(FRONTEND_ROOT).as_posix()} "
                    f"{size[0]}x{size[1]} {target_bytes / 1024:.1f} KiB"
                )

    print(f"responsive variants: {total_bytes / 1024:.1f} KiB")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate committed variants without rewriting them",
    )
    args = parser.parse_args()

    if args.check:
        check()
        return

    generate()
    check()


if __name__ == "__main__":
    main()
