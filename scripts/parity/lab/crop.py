"""The zoomed side-by-side ``vanilla | java | |delta|x4``.

The cheapest LOOK gauge in the corpus and the only thing in the package that emits an image. Its
root and subject are arguments; the original carried an absolute machine-pinned ``BASE``.

Its output is a **LOOK gauge**: a human looks at it, and its numbers - it has none - are never
evidence.
"""

from __future__ import annotations

from pathlib import Path

from parity import pixels

#: Nearest-neighbour, because the whole point is to see individual texels.
DEFAULT_ZOOM = 8

GUTTER = 12

BACKDROP = (24, 24, 28, 255)


def crop(vanilla: Path, java: Path, region: tuple[int, int, int, int], out: Path,
         zoom: int = DEFAULT_ZOOM) -> Path:
    numpy = pixels.numpy_module()
    image = pixels.image_module()
    van = pixels.load_rgba(vanilla)
    jav = pixels.load_rgba(java)

    x0, y0, x1, y1 = region
    width, height = x1 - x0 + 1, y1 - y0 + 1

    # x4 so a sub-LSB difference is visible at all; it saturates rather than wrapping.
    magnitude = numpy.abs(van - jav).max(axis=2)
    delta = numpy.zeros(van.shape, dtype=numpy.int32)
    delta[..., 3] = 255
    for channel in range(3):
        delta[..., channel] = numpy.clip(magnitude * 4, 0, 255)

    tiles = [_tile(image, numpy, array[y0:y1 + 1, x0:x1 + 1], width, height, zoom)
             for array in (van, jav, delta)]
    canvas = image.new("RGBA", (width * zoom * 3 + GUTTER * 2, height * zoom), BACKDROP)
    for index, tile in enumerate(tiles):
        canvas.paste(tile, (index * (width * zoom + GUTTER), 0))

    import io
    buffer = io.BytesIO()
    canvas.save(buffer, format="PNG")
    from parity.norm import write_bytes_raw
    return write_bytes_raw(out, buffer.getvalue())


def _tile(image, numpy, array, width: int, height: int, zoom: int):
    return image.fromarray(array.astype(numpy.uint8), "RGBA").resize(
        (width * zoom, height * zoom), image.NEAREST)
