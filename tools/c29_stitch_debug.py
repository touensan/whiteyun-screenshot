import argparse
import itertools
import json
import math
import os
from dataclasses import dataclass

import numpy as np
from PIL import Image

STATIC_EDGE_SCORE_THRESHOLD = 14
STATIC_EDGE_MIN_PX = 48
STATIC_EDGE_MAX_PX = 420
STATIC_EDGE_SCAN_STEP_PX = 4
MIN_NEW_CONTENT_AFTER_OVERLAP = 96


@dataclass
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def height(self):
        return self.bottom - self.top

    def as_text(self):
        return f"{self.left},{self.top}-{self.right},{self.bottom}"


@dataclass
class Frame:
    path: str
    image: Image.Image
    gray: np.ndarray
    edge: np.ndarray
    content_rect: Rect


@dataclass
class Match:
    previous: int
    next: int
    overlap: int
    score: float
    consensus: float
    support_blocks: int
    support_ratio: float
    mean_diff: float
    no_movement: bool = False


def load_frame(path, crop_top, crop_bottom):
    image = Image.open(path).convert("RGB")
    bottom = image.height - crop_bottom
    if crop_top < 0 or crop_bottom < 0 or bottom <= crop_top:
        raise ValueError(f"bad crop for {path}")
    image = image.crop((0, crop_top, image.width, bottom))
    rgb = np.asarray(image).astype(np.float32)
    gray = rgb[:, :, 0] * 0.30 + rgb[:, :, 1] * 0.59 + rgb[:, :, 2] * 0.11
    dx = np.zeros_like(gray)
    dy = np.zeros_like(gray)
    dx[:, 1:] = np.abs(gray[:, 1:] - gray[:, :-1])
    dy[1:, :] = np.abs(gray[1:, :] - gray[:-1, :])
    edge = dx + dy
    return Frame(
        path=path,
        image=image,
        gray=gray,
        edge=edge,
        content_rect=Rect(0, 0, image.width, image.height),
    )


def block_consensus(previous, current, overlap, x_margin):
    width = min(previous.image.width, current.image.width)
    x0 = min(x_margin, width // 4)
    x1 = max(x0 + 1, width - x0)
    prev_top = previous.content_rect.bottom - overlap
    cur_top = current.content_rect.top
    prev_gray = previous.gray[prev_top:previous.content_rect.bottom, x0:x1]
    cur_gray = current.gray[cur_top:cur_top + overlap, x0:x1]
    prev_edge = previous.edge[prev_top:previous.content_rect.bottom, x0:x1]
    cur_edge = current.edge[cur_top:cur_top + overlap, x0:x1]

    block_h = 48
    block_w = 64
    weighted_diff = 0.0
    weighted_vote = 0.0
    total_weight = 0.0
    support_blocks = 0
    all_blocks = 0
    for y in range(0, overlap - block_h + 1, block_h):
        for x in range(0, x1 - x0 - block_w + 1, block_w):
            all_blocks += 1
            pg = prev_gray[y:y + block_h, x:x + block_w]
            cg = cur_gray[y:y + block_h, x:x + block_w]
            pe = prev_edge[y:y + block_h, x:x + block_w]
            ce = cur_edge[y:y + block_h, x:x + block_w]
            texture = float(np.mean(np.maximum(pe, ce)) + 0.35 * max(np.std(pg), np.std(cg)))
            if texture < 2.2:
                continue
            edge_diff = float(np.mean(np.abs(pe - ce)))
            gray_diff = float(np.mean(np.abs(pg - cg)))
            diff = edge_diff * 0.62 + gray_diff * 0.38
            weight = min(texture, 42.0)
            # ponytail: a smooth likelihood is enough for developer diagnostics; OpenCV/NCC can replace it if needed.
            vote = math.exp(-((diff / 18.0) ** 2))
            weighted_diff += diff * weight
            weighted_vote += vote * weight
            total_weight += weight
            support_blocks += 1

    if total_weight <= 0:
        return 999.0, 0.0, 0, 0.0, 999.0

    mean_diff = weighted_diff / total_weight
    consensus = weighted_vote / total_weight
    support_ratio = support_blocks / max(1, all_blocks)
    support_penalty = 0.0 if support_blocks >= 8 else (8 - support_blocks) * 8.0
    ratio_penalty = 0.0 if support_ratio >= 0.18 else (0.18 - support_ratio) * 80.0
    score = mean_diff - consensus * 18.0 + support_penalty + ratio_penalty
    return score, consensus, support_blocks, support_ratio, mean_diff


def find_match(frames, previous_index, current_index, min_overlap, max_overlap, x_margin):
    previous = frames[previous_index]
    current = frames[current_index]
    limit = min(previous.content_rect.height, current.content_rect.height)
    aligned = aligned_diff(previous, current)
    if aligned <= 9.0:
        return Match(
            previous=previous_index,
            next=current_index,
            overlap=limit,
            score=aligned,
            consensus=1.0,
            support_blocks=0,
            support_ratio=1.0,
            mean_diff=aligned,
            no_movement=True,
        )
    max_overlap = min(max_overlap, max(0, limit - MIN_NEW_CONTENT_AFTER_OVERLAP))
    min_overlap = min(min_overlap, max_overlap)
    best = None
    for overlap in range(min_overlap, max_overlap + 1, 8):
        score, consensus, support_blocks, support_ratio, mean_diff = block_consensus(
            previous,
            current,
            overlap,
            x_margin,
        )
        target = int(limit * 0.40)
        tolerance = max(160, int(limit * 0.22))
        prior = max(0, abs(overlap - target) - tolerance) / 160.0
        prior += max(0, int(limit * 0.20) - overlap) / 8.0
        score += prior
        match = Match(
            previous=previous_index,
            next=current_index,
            overlap=overlap,
            score=score,
            consensus=consensus,
            support_blocks=support_blocks,
            support_ratio=support_ratio,
            mean_diff=mean_diff,
        )
        if best is None or match.score < best.score:
            best = match

    refine_from = max(min_overlap, best.overlap - 16)
    refine_to = min(max_overlap, best.overlap + 16)
    for overlap in range(refine_from, refine_to + 1, 2):
        score, consensus, support_blocks, support_ratio, mean_diff = block_consensus(
            previous,
            current,
            overlap,
            x_margin,
        )
        target = int(limit * 0.40)
        tolerance = max(160, int(limit * 0.22))
        prior = max(0, abs(overlap - target) - tolerance) / 160.0
        prior += max(0, int(limit * 0.20) - overlap) / 8.0
        score += prior
        match = Match(
            previous=previous_index,
            next=current_index,
            overlap=overlap,
            score=score,
            consensus=consensus,
            support_blocks=support_blocks,
            support_ratio=support_ratio,
            mean_diff=mean_diff,
        )
        if match.score < best.score:
            best = match
    return best


def aligned_diff(previous, current):
    height = min(previous.content_rect.height, current.content_rect.height)
    width = min(previous.image.width, current.image.width)
    rows = 12
    columns = 12
    total = 0.0
    samples = 0
    for row in range(rows):
        y = (row + 1) * height // (rows + 1)
        for column in range(columns):
            x = (column + 1) * width // (columns + 1)
            py = previous.content_rect.top + y
            cy = current.content_rect.top + y
            total += abs(float(previous.gray[py, x]) - float(current.gray[cy, x]))
            samples += 1
    return total / max(1, samples)


def prepare_content_rects(frames, use_app_content_rects):
    if not frames:
        return
    width = min(frame.image.width for frame in frames)
    for frame in frames:
        frame.content_rect = Rect(0, 0, width, frame.image.height)
    if not use_app_content_rects or len(frames) < 2:
        return

    static_top = detect_static_edge(frames, width, True)
    static_bottom = detect_static_edge(frames, width, False)
    for index, frame in enumerate(frames):
        top = 0 if index == 0 else static_top
        bottom = frame.image.height if index == len(frames) - 1 else frame.image.height - static_bottom
        min_content = max(160, frame.image.height // 3)
        if bottom - top < min_content:
            top = 0
            bottom = frame.image.height
        frame.content_rect = Rect(0, top, width, bottom)


def detect_static_edge(frames, width, top):
    max_scan = STATIC_EDGE_MAX_PX
    for frame in frames:
        max_scan = min(max_scan, max(0, frame.image.height // 4))
    stable = 0
    for offset in range(0, max_scan, STATIC_EDGE_SCAN_STEP_PX):
        score = score_static_edge_row(frames, width, offset, top)
        if score > STATIC_EDGE_SCORE_THRESHOLD:
            break
        stable = offset + STATIC_EDGE_SCAN_STEP_PX
    if stable < STATIC_EDGE_MIN_PX or stable >= max_scan:
        return 0
    # ponytail: mirrors the APP's same-position fixed chrome scan; node-aware masks can replace it later.
    return min(stable, max_scan)


def score_static_edge_row(frames, width, offset, top):
    columns = 24
    total = 0.0
    samples = 0
    for i in range(1, len(frames)):
        previous = frames[i - 1]
        current = frames[i]
        previous_y = offset if top else previous.image.height - 1 - offset
        current_y = offset if top else current.image.height - 1 - offset
        if previous_y < 0 or current_y < 0:
            continue
        for column in range(columns):
            x = min(width - 1, (column + 1) * width // (columns + 1))
            total += abs(float(previous.gray[previous_y, x]) - float(current.gray[current_y, x]))
            samples += 1
    return total / max(1, samples)


def choose_order(frames, min_overlap, max_overlap, x_margin, mode):
    pair_matches = {}
    if mode == "given":
        order = list(range(len(frames)))
        for i in range(1, len(frames)):
            pair_matches[(i - 1, i)] = find_match(frames, i - 1, i, min_overlap, max_overlap, x_margin)
        matches = [pair_matches[(order[i - 1], order[i])] for i in range(1, len(order))]
        return order, matches, pair_matches

    for i in range(len(frames)):
        for j in range(len(frames)):
            if i == j:
                continue
            pair_matches[(i, j)] = find_match(frames, i, j, min_overlap, max_overlap, x_margin)

    best_order = None
    best_score = None
    for order in itertools.permutations(range(len(frames))):
        score = 0.0
        for i in range(1, len(order)):
            match = pair_matches[(order[i - 1], order[i])]
            score += match.score + (1.0 - match.consensus) * 12.0
        if best_score is None or score < best_score:
            best_score = score
            best_order = list(order)
    order = best_order

    matches = [pair_matches[(order[i - 1], order[i])] for i in range(1, len(order))]
    return order, matches, pair_matches


def stitch(frames, order, matches, output):
    width = min(frames[i].content_rect.right for i in order)
    heights = [frames[order[0]].content_rect.height]
    for i, match in enumerate(matches, start=1):
        heights.append(frames[order[i]].content_rect.height - match.overlap)
    total_height = sum(heights)
    result = Image.new("RGB", (width, total_height), (255, 255, 255))
    y = 0
    first_rect = frames[order[0]].content_rect
    first = frames[order[0]].image.crop((0, first_rect.top, width, first_rect.bottom))
    result.paste(first, (0, y))
    y += first.height
    for i, match in enumerate(matches, start=1):
        frame = frames[order[i]]
        rect = frame.content_rect
        crop = frame.image.crop((0, rect.top + match.overlap, width, rect.bottom))
        result.paste(crop, (0, y))
        y += crop.height
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    result.save(output)
    return result


def load_manifest_images(path):
    with open(path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
    manifest_dir = os.path.dirname(os.path.abspath(path))
    images = []
    for row in manifest.get("frames", []):
        remote = row.get("file", "")
        local = os.path.join(manifest_dir, os.path.basename(remote))
        if not os.path.exists(local) and os.path.exists(remote):
            local = remote
        if not os.path.exists(local):
            raise FileNotFoundError(f"missing frame for manifest row: {remote}")
        images.append(local)
    if not images:
        raise ValueError(f"manifest has no frames: {path}")
    return images


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="*")
    parser.add_argument("--manifest")
    parser.add_argument("--output", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--order", choices=["auto", "given"], default="auto")
    parser.add_argument("--crop-top", type=int, default=0)
    parser.add_argument("--crop-bottom", type=int, default=0)
    parser.add_argument("--min-overlap", type=int, default=160)
    parser.add_argument("--max-overlap", type=int, default=3000)
    parser.add_argument("--x-margin", type=int, default=42)
    parser.add_argument("--raw-rects", action="store_true")
    args = parser.parse_args()

    images = load_manifest_images(args.manifest) if args.manifest else args.images
    if not images:
        parser.error("provide images or --manifest")
    frames = [load_frame(path, args.crop_top, args.crop_bottom) for path in images]
    prepare_content_rects(frames, not args.raw_rects)
    order, matches, pair_matches = choose_order(
        frames,
        args.min_overlap,
        args.max_overlap,
        args.x_margin,
        args.order,
    )
    result = stitch(frames, order, matches, args.output)
    report = {
        "schema": "whiteyun-c29-stitch-debug-v1",
        "order": order,
        "orderedFiles": [frames[i].path for i in order],
        "output": os.path.abspath(args.output),
        "outputWidth": result.width,
        "outputHeight": result.height,
        "cropTop": args.crop_top,
        "cropBottom": args.crop_bottom,
        "contentRects": [frame.content_rect.as_text() for frame in frames],
        "matches": [
            {
                "previous": match.previous,
                "next": match.next,
                "previousFile": frames[match.previous].path,
                "nextFile": frames[match.next].path,
                "overlap": match.overlap,
                "score": round(match.score, 3),
                "consensus": round(match.consensus, 4),
                "supportBlocks": match.support_blocks,
                "supportRatio": round(match.support_ratio, 4),
                "meanDiff": round(match.mean_diff, 3),
                "noMovement": match.no_movement,
                "previousContentRect": frames[match.previous].content_rect.as_text(),
                "nextContentRect": frames[match.next].content_rect.as_text(),
            }
            for match in matches
        ],
        "allDirectedPairs": [
            {
                "previous": match.previous,
                "next": match.next,
                "overlap": match.overlap,
                "score": round(match.score, 3),
                "consensus": round(match.consensus, 4),
                "supportBlocks": match.support_blocks,
                "supportRatio": round(match.support_ratio, 4),
                "meanDiff": round(match.mean_diff, 3),
                "noMovement": match.no_movement,
            }
            for match in sorted(pair_matches.values(), key=lambda item: item.score)
        ],
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
