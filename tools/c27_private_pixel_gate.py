import argparse
import io
import json
import subprocess
import sys

from PIL import Image, ImageChops, ImageStat


PKG = "com.whiteyun.screenshot"
TARGET_PKG = "com.tencent.mm"
STATIC_EDGE_SCORE_THRESHOLD = 14
STATIC_EDGE_MIN_PX = 48
STATIC_EDGE_MAX_PX = 420
STATIC_EDGE_SCAN_STEP_PX = 4


def adb_bytes(*args):
    result = subprocess.run(
        ["adb", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return result.stdout


def adb_text(*args):
    return adb_bytes(*args).decode("utf-8", "replace")


def app_cat(path):
    return adb_bytes("exec-out", "run-as", PKG, "cat", path)


def latest_manifest():
    try:
        listing = adb_text("shell", "run-as", PKG, "ls", "-1", "files/auto-scroll-evidence")
    except subprocess.CalledProcessError as exc:
        raise RuntimeError("no auto-scroll evidence directory") from exc
    sessions = sorted(
        line.strip()
        for line in listing.splitlines()
        if line.strip().startswith("auto_")
    )
    if not sessions:
        raise RuntimeError("no auto-scroll evidence session")
    session = sessions[-1]
    manifest_path = f"files/auto-scroll-evidence/{session}/manifest.json"
    manifest = json.loads(app_cat(manifest_path).decode("utf-8", "replace"))
    return session, manifest


def as_list(value):
    return value if isinstance(value, list) else []


def event_detail(events, event_type):
    for event in reversed(events):
        if event.get("type") == event_type:
            return event.get("detail", "")
    return ""


def load_private_image(path):
    return Image.open(io.BytesIO(app_cat(path))).convert("RGB")


def gray(pixel):
    return (pixel[0] * 30 + pixel[1] * 59 + pixel[2] * 11) // 100


def color_distance(first, second):
    return (abs(first[0] - second[0]) + abs(first[1] - second[1]) + abs(first[2] - second[2])) // 3


def score_static_edge_row(frames, width, offset, top):
    columns = 24
    total = 0
    samples = 0
    for index in range(1, len(frames)):
        previous = frames[index - 1]
        current = frames[index]
        previous_y = offset if top else previous.height - 1 - offset
        current_y = offset if top else current.height - 1 - offset
        if previous_y < 0 or current_y < 0:
            continue
        for column in range(columns):
            x = min(width - 1, (column + 1) * width // (columns + 1))
            total += color_distance(previous.getpixel((x, previous_y)), current.getpixel((x, current_y)))
            samples += 1
    return total // max(1, samples)


def detect_static_edge(frames, top):
    if len(frames) < 2:
        return 0
    width = min(frame.width for frame in frames)
    max_scan = min(STATIC_EDGE_MAX_PX, *(max(0, frame.height // 4) for frame in frames))
    stable = 0
    for offset in range(0, max_scan, STATIC_EDGE_SCAN_STEP_PX):
        if score_static_edge_row(frames, width, offset, top) > STATIC_EDGE_SCORE_THRESHOLD:
            break
        stable = offset + STATIC_EDGE_SCAN_STEP_PX
    if stable < STATIC_EDGE_MIN_PX or stable >= max_scan:
        return 0
    return min(stable, max_scan)


def resized_strip(image, y, height, width):
    crop = image.crop((0, y, width, y + height))
    sample_h = max(8, min(48, height // 4))
    return crop.resize((64, sample_h), Image.Resampling.BILINEAR)


def mean_distance(first, second):
    diff = ImageChops.difference(first, second)
    return sum(ImageStat.Stat(diff).mean) / 3.0


def grouped_positions(positions, gap):
    groups = []
    for position in sorted(positions):
        if not groups or position - groups[-1][-1] > gap:
            groups.append([position])
        else:
            groups[-1].append(position)
    return [group[len(group) // 2] for group in groups]


def find_strip_matches(preview, reference, strip_height, width, threshold):
    positions = []
    step = max(6, strip_height // 6)
    limit = max(0, preview.height - strip_height)
    for y in range(0, limit + 1, step):
        candidate = resized_strip(preview, y, strip_height, width)
        if mean_distance(reference, candidate) <= threshold:
            positions.append(y)
    return grouped_positions(positions, max(24, strip_height // 2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--min-accepted-frames", type=int, default=2)
    parser.add_argument("--target-package", default=TARGET_PKG)
    args = parser.parse_args()

    session, manifest = latest_manifest()
    events = as_list(manifest.get("events"))
    windows = as_list(manifest.get("windows"))
    frame_results = [event for event in events if event.get("type") == "frame_result"]
    accepted = [event for event in frame_results if event.get("accepted") is True]
    target_windows = [row for row in windows if row.get("packageName") == args.target_package]

    original_detail = event_detail(events, "original_frames")
    original_paths = [path for path in original_detail.split(";") if path]
    preview_path = event_detail(events, "stitch_success")

    if not original_paths:
        raise RuntimeError("missing original_frames event; rerun with the latest debug build")
    if not preview_path:
        raise RuntimeError("missing stitch_success preview path")
    if len(accepted) < args.min_accepted_frames:
        raise RuntimeError("not enough accepted frames")
    if not target_windows:
        raise RuntimeError("latest evidence does not target WeChat")

    frames = [load_private_image(path) for path in original_paths]
    preview = load_private_image(preview_path)
    width = min([preview.width] + [frame.width for frame in frames])

    static_top = detect_static_edge(frames, True)
    static_bottom = detect_static_edge(frames, False)
    top_positions = []
    bottom_positions = []
    if static_top > 0:
        top_ref = resized_strip(frames[0], 0, static_top, width)
        top_positions = find_strip_matches(preview, top_ref, static_top, width, 18.0)
    if static_bottom > 0:
        bottom_ref = resized_strip(frames[-1], frames[-1].height - static_bottom, static_bottom, width)
        bottom_positions = find_strip_matches(preview, bottom_ref, static_bottom, width, 18.0)

    top_ok = static_top > 0 and len(top_positions) == 1 and top_positions[0] <= max(24, static_top // 3)
    bottom_ok = (
        static_bottom > 0
        and len(bottom_positions) == 1
        and abs(bottom_positions[0] - (preview.height - static_bottom)) <= max(48, static_bottom)
    )

    result = {
        "session": session,
        "schema": manifest.get("schema"),
        "success": manifest.get("success"),
        "endReason": manifest.get("endReason"),
        "acceptedFrames": len(accepted),
        "windowTargetCount": len(target_windows),
        "previewWidth": preview.width,
        "previewHeight": preview.height,
        "staticTop": static_top,
        "staticBottom": static_bottom,
        "topMatchCount": len(top_positions),
        "topMatchPositions": top_positions,
        "bottomMatchCount": len(bottom_positions),
        "bottomMatchPositions": bottom_positions,
        "topOnce": top_ok,
        "bottomOnce": bottom_ok,
        "visualPass": top_ok and bottom_ok,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["visualPass"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(json.dumps({"visualPass": False, "error": str(exc)}, ensure_ascii=False, indent=2))
        raise SystemExit(1)
