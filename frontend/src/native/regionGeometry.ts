import type { OcrRegion } from "./bridge";

export const DEFAULT_SUBTITLE_REGION: OcrRegion = { x: 0, y: .72, w: 1, h: .28 };
export const MIN_REGION_SIDE = .05;
export type RegionHandle = "move" | "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

export function containFrame(width: number, height: number, sourceWidth: number, sourceHeight: number): OcrRegion {
  const fit = Math.min(width / sourceWidth, height / sourceHeight);
  const w = sourceWidth * fit;
  const h = sourceHeight * fit;
  return { x: (width - w) / 2, y: (height - h) / 2, w, h };
}

export function dragRegion(region: OcrRegion, handle: RegionHandle, dx: number, dy: number): OcrRegion {
  if (handle === "move") {
    return { ...region, x: clamp(region.x + dx, 0, 1 - region.w), y: clamp(region.y + dy, 0, 1 - region.h) };
  }
  const left = handle.includes("w") ? clamp(region.x + dx, 0, region.x + region.w - MIN_REGION_SIDE) : region.x;
  const right = handle.includes("e") ? clamp(region.x + region.w + dx, region.x + MIN_REGION_SIDE, 1) : region.x + region.w;
  const top = handle.includes("n") ? clamp(region.y + dy, 0, region.y + region.h - MIN_REGION_SIDE) : region.y;
  const bottom = handle.includes("s") ? clamp(region.y + region.h + dy, region.y + MIN_REGION_SIDE, 1) : region.y + region.h;
  return { x: left, y: top, w: Math.max(MIN_REGION_SIDE, right - left), h: Math.max(MIN_REGION_SIDE, bottom - top) };
}

// Match FrameRect.pixelCrop in the native decoder. The NV21 crop has even dimensions.
export function pixelCrop(region: OcrRegion, width: number, height: number): OcrRegion {
  const x = clamp(Math.trunc(width * region.x), 0, width - 2);
  const y = clamp(Math.trunc(height * region.y), 0, height - 2);
  const w = Math.min(Math.max(2, Math.trunc(width * region.w)), width - x) & -2;
  const h = Math.min(Math.max(2, Math.trunc(height * region.h)), height - y) & -2;
  return { x, y, w, h };
}

export function projectCrop(region: OcrRegion, viewport: OcrRegion, sourceWidth: number, sourceHeight: number): OcrRegion {
  const pixels = pixelCrop(region, sourceWidth, sourceHeight);
  return {
    x: viewport.x + pixels.x / sourceWidth * viewport.w,
    y: viewport.y + pixels.y / sourceHeight * viewport.h,
    w: pixels.w / sourceWidth * viewport.w,
    h: pixels.h / sourceHeight * viewport.h,
  };
}

export function projectExclusion(
  exclusion: OcrRegion, viewport: OcrRegion, selection: OcrRegion, sourceWidth: number, sourceHeight: number,
): OcrRegion {
  const toX = (fraction: number) => viewport.x + Math.trunc(fraction * sourceWidth) / sourceWidth * viewport.w;
  const toY = (fraction: number) => viewport.y + Math.trunc(fraction * sourceHeight) / sourceHeight * viewport.h;
  const x = Math.max(selection.x, toX(exclusion.x));
  const y = Math.max(selection.y, toY(exclusion.y));
  const w = Math.max(0, Math.min(selection.x + selection.w, toX(exclusion.x + exclusion.w)) - x);
  const h = Math.max(0, Math.min(selection.y + selection.h, toY(exclusion.y + exclusion.h)) - y);
  return { x, y, w, h };
}
