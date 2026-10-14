#!/usr/bin/env python3
"""
Stationary-frame skip, lab mirror of the build-57 logic. Numpy only.

WHAT THIS MIRRORS (Kotlin, build 57 = commit d478dca)
  app/src/main/java/app/vocr/core/Dedup.kt
    StripBuilder.push()   the test on line ~92:
        Hash.similarity(hash, prevHash) > 0.95  AND  meanAbsDiff(g, prev) < 2.0   ->  Skip ("nothing changed")
    Hash.phash()          64-bit hash: 32x32 gray -> low 8x8 DCT block -> bits vs its median
    Hash.similarity()     1 - popcount(a xor b) / 64
    meanAbsDiff()         mean(|a - b|) over all pixels, gray 0..255
  app/src/main/java/app/vocr/core/Frames.kt  (runDecoded)   sampling and sizes:
    fps = clamp(700 / seconds, 1, 3); analysis frame 360 px wide gray; pHash input = 32x32 block average
  app/src/main/java/app/vocr/core/Yuv.kt  gray() and shrink()  (average pooling)

  Reference frame: the last frame that was NOT skipped (on a skip, "prev" is kept, so slow drift accumulates
  until it crosses the threshold). In the app, a frame that is not skipped then goes on to scroll matching
  (detectScroll) and strip cutting. That part is NOT mirrored here: this script only answers "which sampled
  frames would the stationary test skip?" It is a lab approximation, not the app code, and the numbers are
  x86 lab, not S24.

ENGINE-SPEC CHANGES (proposed, not in any build)  -- enabled with flags below
  --region T B   test only rows [T,B) of the frame (fractions 0..1), i.e. the area that is NOT video
                 (cover, or mask v2 supplies this rectangle; here you pass it by hand)
  --blocks N     block-wise test: also call a frame "changed" if any NxN-pixel block (analysis scale)
                 has mean abs diff above --block-th. Catches a small real change (a new comment line)
                 that a whole-frame mean would hide.
  --block-th X   per-block threshold (default 12 gray levels)

INPUT: raw RGB frames file (w*h*3 bytes per frame), like the lab's ffmpeg dump:
   ffmpeg -i clip.mp4 -vf fps=3,scale=720:1560 -f rawvideo -pix_fmt rgb24 frames.rgb
   python3 stationary_skip.py frames.rgb --w 720 --h 1560
OUTPUT: per frame K(ept)/S(kipped), and a summary (skip %). Use --csv to write per-frame values.
"""
import argparse, sys
import numpy as np

def gray_pool(rgb, dw, dh):
    """BT.601 luma, average-pooled to dw x dh (the app pools Y from YUV; this is the closest RGB equivalent)."""
    y = (rgb[..., 0] * 299 + rgb[..., 1] * 587 + rgb[..., 2] * 114) / 1000.0
    h, w = y.shape
    ys = np.linspace(0, h, dh + 1).astype(int); xs = np.linspace(0, w, dw + 1).astype(int)
    out = np.add.reduceat(np.add.reduceat(y, ys[:-1], axis=0), xs[:-1], axis=1)
    cnt = np.outer(np.diff(ys), np.diff(xs))
    return (out / cnt).astype(np.float32)

def shrink(g, ow, oh):
    h, w = g.shape
    ys = np.linspace(0, h, oh + 1).astype(int); xs = np.linspace(0, w, ow + 1).astype(int)
    out = np.add.reduceat(np.add.reduceat(g, ys[:-1], axis=0), xs[:-1], axis=1)
    return out / np.outer(np.diff(ys), np.diff(xs))

_N = 32
_COS = np.array([[np.cos((2 * x + 1) * u * np.pi / 64) for x in range(_N)] for u in range(8)], dtype=np.float64)

def phash(g32):
    """64-bit pHash of a 32x32 gray image: low 8x8 of the 2-D DCT vs its median (same as Hash.phash)."""
    dct = _COS @ g32.astype(np.float64) @ _COS.T            # 8x8, rows = v, cols = u
    flat = dct.reshape(-1)
    s = np.sort(flat); med = (s[31] + s[32]) / 2.0
    return flat > med

def similarity(a, b):
    return 1.0 - np.count_nonzero(a != b) / 64.0

def block_max_diff(a, b, n):
    h, w = a.shape
    h2, w2 = h // n * n, w // n * n
    d = np.abs(a[:h2, :w2] - b[:h2, :w2]).reshape(h2 // n, n, w2 // n, n).mean(axis=(1, 3))
    return float(d.max())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('rgb'); ap.add_argument('--w', type=int, default=720); ap.add_argument('--h', type=int, default=1560)
    ap.add_argument('--sim', type=float, default=0.95)      # StripBuilder.simThreshold
    ap.add_argument('--mad', type=float, default=2.0)       # meanAbsDiff limit in push()
    ap.add_argument('--dw', type=int, default=360)          # Frames.DET_W
    ap.add_argument('--stride', type=int, default=1, help='use every Nth frame of the file (e.g. file at 50 fps, stride 17 = 3 fps)')
    ap.add_argument('--region', type=float, nargs=2, metavar=('T', 'B'), help='ENGINE-SPEC: test only rows T..B (fractions)')
    ap.add_argument('--blocks', type=int, default=0, help='ENGINE-SPEC: block size (analysis px) for the block-wise test; 0 = off')
    ap.add_argument('--block-th', type=float, default=12.0)
    ap.add_argument('--csv')
    a = ap.parse_args()
    fs = a.w * a.h * 3
    mm = np.memmap(a.rgb, dtype=np.uint8, mode='r'); n = len(mm) // fs
    dh = int(a.h * a.dw / a.w)
    prev = None; prev_hash = None; kept = skipped = 0; seq = []; rows = []
    for i in range(0, n, a.stride):
        rgb = np.asarray(mm[i * fs:(i + 1) * fs]).reshape(a.h, a.w, 3).astype(np.float32)
        g = gray_pool(rgb, a.dw, dh)
        if a.region:
            t, b = int(a.region[0] * dh), int(a.region[1] * dh); g = g[t:b]
        hsh = phash(shrink(g, 32, 32))
        skip = False; sim = mad = blk = float('nan')
        if prev is not None:
            sim = similarity(hsh, prev_hash); mad = float(np.mean(np.abs(g - prev)))
            skip = sim > a.sim and mad < a.mad
            if skip and a.blocks:
                blk = block_max_diff(g, prev, a.blocks)
                if blk > a.block_th: skip = False          # a small local change: do not skip
        if skip:
            skipped += 1
        else:
            kept += 1; prev = g; prev_hash = hsh           # reference moves only when a frame is kept
        seq.append('S' if skip else 'K'); rows.append((i, sim, mad, blk, 'S' if skip else 'K'))
    tot = kept + skipped
    print(''.join(seq))
    print(f'frames sampled {tot}  kept {kept}  skipped {skipped}  ({100 * skipped / max(1, tot):.1f}% skipped)')
    if a.csv:
        with open(a.csv, 'w') as f:
            f.write('frame,similarity,mean_abs_diff,max_block_diff,decision\n')
            for r in rows: f.write('%d,%.4f,%.3f,%.3f,%s\n' % r)

if __name__ == '__main__':
    main()
