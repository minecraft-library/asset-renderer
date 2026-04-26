"""Generates the Euler rotation reference diagram embedded in the
{@link lib.minecraft.renderer.geometry.EulerRotation} javadoc.

Run this script (no arguments) to regenerate the SVG; output is written next
to the EulerRotation source under
{@code src/main/java/lib/minecraft/renderer/geometry/doc-files/euler_reference.svg},
which is the conventional javadoc location for embedded image assets - the
{@code javadoc} tool copies any {@code doc-files/} subdirectory through to the
generated HTML unchanged.

Visual notes:
  - Iso pose matches the renderer's standard block-icon camera
    {@code [pitch=30, yaw=225, roll=0]}.
  - Arcs are drawn for VISUAL clarity rather than 3D-projection accuracy:
    each arc is laid out in screen space as a 2D ellipse with major axis
    perpendicular to the rotation-axis line in the rendered image, and minor
    axis foreshortened along the axis line.
  - Rotation directions follow the right-hand rule:
        +pitch (X-axis): +Y -> +Z
        +yaw   (Y-axis): +Z -> +X
        +roll  (Z-axis): +X -> +Y
"""

import math
import numpy as np
from pathlib import Path

W, H = 1070, 530
CENTER = (830, 330)
SCALE = 95


def Rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, s], [0, -s, c]])


def Ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])


def Rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, s, 0], [-s, c, 0], [0, 0, 1]])


M = Rz(math.radians(0)) @ Ry(math.radians(225)) @ Rx(math.radians(30))


def cam(v):
    return np.array(v, dtype=float) @ M


def proj(v):
    p = cam(v)
    return (CENTER[0] + p[0] * SCALE, CENTER[1] - p[1] * SCALE)


# ----------------------------------------------------------------------------
# SVG emitter helpers
# ----------------------------------------------------------------------------

svg_parts = []


def emit(s):
    svg_parts.append(s)


def hex_color(rgb):
    return f"#{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}"


def emit_line(p0, p1, color, width=2):
    emit(
        f'<line x1="{p0[0]:.2f}" y1="{p0[1]:.2f}" x2="{p1[0]:.2f}" y2="{p1[1]:.2f}" '
        f'stroke="{hex_color(color)}" stroke-width="{width}" stroke-linecap="round"/>'
    )


def emit_polyline(points, color, width=2):
    pts = " ".join(f"{p[0]:.2f},{p[1]:.2f}" for p in points)
    emit(
        f'<polyline points="{pts}" fill="none" '
        f'stroke="{hex_color(color)}" stroke-width="{width}" '
        f'stroke-linecap="round" stroke-linejoin="round"/>'
    )


def emit_polygon(points, fill, stroke=None, stroke_width=2):
    pts = " ".join(f"{p[0]:.2f},{p[1]:.2f}" for p in points)
    s = f'<polygon points="{pts}" fill="{hex_color(fill)}"'
    if stroke is not None:
        s += (f' stroke="{hex_color(stroke)}" stroke-width="{stroke_width}" '
              f'stroke-linejoin="round"')
    s += "/>"
    emit(s)


# PIL anchor 2-letter code -> SVG (text-anchor, dominant-baseline)
_ANCHOR_MAP = {
    "lt": ("start",  "hanging"),
    "lm": ("start",  "central"),
    "ls": ("start",  "alphabetic"),
    "lb": ("start",  "text-after-edge"),
    "mt": ("middle", "hanging"),
    "mm": ("middle", "central"),
    "ms": ("middle", "alphabetic"),
    "mb": ("middle", "text-after-edge"),
    "rt": ("end",    "hanging"),
    "rm": ("end",    "central"),
    "rs": ("end",    "alphabetic"),
    "rb": ("end",    "text-after-edge"),
}


def emit_text(x, y, text, color, size, weight="normal",
              family="Arial, Helvetica, sans-serif", anchor="lt"):
    text_anchor, baseline = _ANCHOR_MAP.get(anchor, ("start", "alphabetic"))
    text_escaped = (text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;"))
    emit(
        f'<text x="{x:.2f}" y="{y:.2f}" fill="{hex_color(color)}" '
        f'font-family="{family}" font-size="{size}" font-weight="{weight}" '
        f'text-anchor="{text_anchor}" dominant-baseline="{baseline}">'
        f'{text_escaped}</text>'
    )


def arrowhead(p_tip, p_back, color, length=18):
    dx = p_tip[0] - p_back[0]
    dy = p_tip[1] - p_back[1]
    L = max(math.hypot(dx, dy), 1e-6)
    ux, uy = dx / L, dy / L
    nx, ny = -uy, ux
    a = (p_tip[0] - ux * length + nx * length * 0.55,
         p_tip[1] - uy * length + ny * length * 0.55)
    b = (p_tip[0] - ux * length - nx * length * 0.55,
         p_tip[1] - uy * length - ny * length * 0.55)
    emit_polygon([p_tip, a, b], color)


def trim_polyline_end(pts, trim_length):
    """Returns a copy of pts with its trailing segment(s) shortened by
    `trim_length` along the curve. Used to stop a polyline at the base of an
    arrowhead so the line doesn't visibly extend into the triangle."""
    if len(pts) < 2 or trim_length <= 0:
        return list(pts)
    out = list(pts)
    remaining = trim_length
    while len(out) >= 2:
        p1 = out[-1]
        p0 = out[-2]
        dx = p1[0] - p0[0]
        dy = p1[1] - p0[1]
        seg_len = math.hypot(dx, dy)
        if seg_len <= remaining:
            out.pop()
            remaining -= seg_len
            if remaining <= 0:
                break
        else:
            t = remaining / seg_len
            out[-1] = (p1[0] - dx * t, p1[1] - dy * t)
            break
    return out


# ----------------------------------------------------------------------------
# SVG document
# ----------------------------------------------------------------------------

emit('<?xml version="1.0" encoding="UTF-8" standalone="no"?>')
emit(
    f'<svg xmlns="http://www.w3.org/2000/svg" '
    f'width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
    f'shape-rendering="geometricPrecision" text-rendering="geometricPrecision">'
)
emit(f'<rect width="{W}" height="{H}" fill="rgb(252,252,250)"/>')


# Cube faces (back-to-front so closer faces overdraw)
cube = [(x, y, z) for x in (-1, 1) for y in (-1, 1) for z in (-1, 1)]
edges = [(0, 1), (0, 2), (0, 4), (1, 3), (1, 5), (2, 3),
         (2, 6), (3, 7), (4, 5), (4, 6), (5, 7), (6, 7)]
faces = [
    ([0, 1, 3, 2], (-1, 0, 0), (215, 215, 215)),
    ([4, 5, 7, 6], (1, 0, 0), (228, 218, 200)),
    ([0, 1, 5, 4], (0, -1, 0), (190, 190, 190)),
    ([2, 3, 7, 6], (0, 1, 0), (252, 246, 230)),
    ([0, 2, 6, 4], (0, 0, -1), (235, 230, 215)),
    ([1, 3, 7, 5], (0, 0, 1), (210, 210, 210)),
]
visible = []
for verts, normal, color in faces:
    cn = cam(normal)
    if cn[2] <= 0:
        continue
    centroid = np.mean([cube[i] for i in verts], axis=0)
    visible.append((cam(centroid)[2], verts, color))

visible.sort(key=lambda x: x[0])
for _, verts, color in visible:
    pts = [proj(cube[i]) for i in verts]
    emit_polygon(pts, color, stroke=(70, 70, 70), stroke_width=2)

for a, b in edges:
    emit_line(proj(cube[a]), proj(cube[b]), (60, 60, 60), width=2)


def axis_arrow(direction, color, label, length=2.6, label_offset=2.75,
               label_dx=-12, label_dy=-32):
    arrow_len = 20
    end = [c * length for c in direction]
    arrow_back = [c * (length - 0.18) for c in direction]
    tip = proj(end)
    origin = proj((0, 0, 0))
    dx = tip[0] - origin[0]
    dy = tip[1] - origin[1]
    L = max(math.hypot(dx, dy), 1e-6)
    line_end = (tip[0] - dx / L * arrow_len, tip[1] - dy / L * arrow_len)
    emit_line(origin, line_end, color, width=4)
    arrowhead(tip, proj(arrow_back), color, length=arrow_len)
    lp = proj([c * label_offset for c in direction])
    emit_text(lp[0] + label_dx, lp[1] + label_dy, label, color,
              size=28, weight="bold", anchor="lt")


axis_arrow((1, 0, 0), (200, 30, 30), "+X", label_dx=-50, label_dy=10)
axis_arrow((0, 1, 0), (30, 150, 30), "+Y", label_dx=-18, label_dy=-32)
axis_arrow((0, 0, 1), (30, 60, 200), "+Z", label_dx=-60, label_dy=-30)


def perspective_arc(axis_3d, color, label,
                    sweep_deg=200,
                    label_t=0.5, label_dx=0, label_dy=0,
                    label_anchor="lt",
                    radius=2.0, minor_factor=0.45,
                    flip_sweep=False,
                    reverse=False):
    """Arc drawn in 2D screen space, perpendicular to the projection of axis_3d.
    Major axis = perpendicular to axis-projection in screen.
    Minor axis = along axis-projection, foreshortened for a 3D feel.

    reverse=True flips the rotation sense (CW <-> CCW in math y-up). Use this
    for axes that point toward the viewer (e.g., +Z in this iso pose), where
    right-hand-rule positive rotation appears CW in screen rather than CCW."""
    a_cam = np.array(axis_3d, dtype=float) @ M
    a_screen = np.array([a_cam[0], a_cam[1]])
    a_len = np.linalg.norm(a_screen)
    if a_len < 1e-6:
        a_n = np.array([1.0, 0.0])
    else:
        a_n = a_screen / a_len

    p_n = np.array([-a_n[1], a_n[0]])
    if flip_sweep:
        p_n = -p_n

    dir_basis = a_n if reverse else -a_n

    sweep_rad = math.radians(sweep_deg)
    n = 80
    pts = []
    for i in range(n + 1):
        theta = (i / n) * sweep_rad
        offset = (math.cos(theta) * radius * p_n
                  + math.sin(theta) * radius * minor_factor * dir_basis)
        sx = CENTER[0] + offset[0] * SCALE
        sy = CENTER[1] - offset[1] * SCALE
        pts.append((sx, sy))

    arrow_len = 22
    emit_polyline(trim_polyline_end(pts, arrow_len), color, width=5)
    arrowhead(pts[-1], pts[-3], color, length=arrow_len)

    label_idx = int(label_t * n)
    lp = pts[label_idx]
    emit_text(lp[0] + label_dx, lp[1] + label_dy, label, color,
              size=24, weight="bold", anchor=label_anchor)


perspective_arc((0, 1, 0), (30, 150, 30), "+yaw",
                sweep_deg=200, label_t=0.5, label_dx=0, label_dy=18,
                label_anchor="mt")
perspective_arc((1, 0, 0), (200, 30, 30), "+pitch",
                sweep_deg=200, label_t=0.5, label_dx=12, label_dy=-22,
                label_anchor="lm")
perspective_arc((0, 0, 1), (30, 60, 200), "+roll",
                sweep_deg=200, label_t=0.5, label_dx=-12, label_dy=-22,
                label_anchor="rm",
                reverse=True)


# Title and legend
legend_x = 20
title_y  = 18
legend_y = 75
LINE_H   = 26

title = "Euler rotation reference - asset-renderer iso pose [pitch=30, yaw=225, roll=0]"
emit_text(legend_x, title_y, title, (20, 20, 20),
          size=28, weight="bold", anchor="lt")

ARIAL = "Arial, Helvetica, sans-serif"
MONO  = "Consolas, 'Courier New', monospace"

lines = [
    ("Right-hand rule for positive rotation",                 (20, 20, 20),  24, "bold",   ARIAL),
    ("",                                                      None,          0,  "",       ""),
    ("+pitch (X-axis):  +Y rotates toward +Z",                (200, 30, 30), 17, "normal", ARIAL),
    ("                  the object's top tips toward +Z",     (90, 90, 90),  17, "normal", ARIAL),
    ("+yaw   (Y-axis):  +Z rotates toward +X",                (30, 150, 30), 17, "normal", ARIAL),
    ("                  CCW spin viewed from above (+Y)",     (90, 90, 90),  17, "normal", ARIAL),
    ("+roll  (Z-axis):  +X rotates toward +Y",                (30, 60, 200), 17, "normal", ARIAL),
    ("                  the object's +X side rises",          (90, 90, 90),  17, "normal", ARIAL),
    ("",                                                      None,          0,  "",       ""),
    ("Visible faces in this pose",                            (20, 20, 20),  24, "bold",   ARIAL),
    ("",                                                      None,          0,  "",       ""),
    ("  top face       = +Y",                                 (90, 90, 90),  16, "normal", MONO),
    ("  lower-left     = +X (east)",                          (90, 90, 90),  16, "normal", MONO),
    ("  lower-right    = -Z (north)",                         (90, 90, 90),  16, "normal", MONO),
    ("",                                                      None,          0,  "",       ""),
    ("Note: arcs are visually corrected for screen perpendicularity,",
                                                              (90, 90, 90),  17, "normal", ARIAL),
    ("not 3D-projection-accurate.",                           (90, 90, 90),  17, "normal", ARIAL),
]
y = legend_y
for text, color, size, weight, family in lines:
    if text:
        emit_text(legend_x, y, text, color,
                  size=size, weight=weight, family=family, anchor="lt")
    y += LINE_H


emit("</svg>")


# Output is written to the doc-files folder next to EulerRotation.java so
# javadoc picks it up automatically. Path is computed relative to this script
# so it works regardless of the current working directory.
script_dir = Path(__file__).resolve().parent
out_path = (script_dir
            / ".." / ".." / ".." / ".."
            / "java" / "lib" / "minecraft" / "renderer" / "geometry"
            / "doc-files" / "euler_reference.svg").resolve()

out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text("\n".join(svg_parts), encoding="utf-8")

print(f"Wrote {out_path}")
