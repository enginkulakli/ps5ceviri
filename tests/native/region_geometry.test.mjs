import assert from "node:assert/strict";
import test from "node:test";
import { containFrame, DEFAULT_SUBTITLE_REGION, dragRegion, MIN_REGION_SIDE, pixelCrop, projectCrop } from "../../frontend/src/native/regionGeometry.ts";

const selected = { x: .157, y: .723, w: .7, h: .215 };
const near = (actual, expected) => assert(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`);

test("moves in every direction and keeps the selected area within the source", () => {
  const inner = {x:.2,y:.3,w:.3,h:.4};
  assert.deepEqual(dragRegion(inner, "move", -5, -5), {...inner,x:0,y:0});
  assert.deepEqual(dragRegion(inner, "move", 5, 5), {...inner,x:.7,y:.6});
  const result = dragRegion(inner, "move", .1, -.1);
  near(result.x, .3);
  near(result.y, .2);
});

test("resizes using every edge and corner while keeping the opposite edges fixed", () => {
  const original = {x:.25,y:.25,w:.5,h:.5};
  for (const handle of ["n", "s", "e", "w", "ne", "nw", "se", "sw"]) {
    const next = dragRegion(original, handle, handle.includes("w") ? -.1 : .1, handle.includes("n") ? -.1 : .1);
    near(next.x, handle.includes("w") ? .15 : .25);
    near(next.y, handle.includes("n") ? .15 : .25);
    near(next.w, handle.includes("e") || handle.includes("w") ? .6 : .5);
    near(next.h, handle.includes("n") || handle.includes("s") ? .6 : .5);
  }
  const minimum = dragRegion(original, "nw", 5, 5);
  near(minimum.w, MIN_REGION_SIDE);
  near(minimum.h, MIN_REGION_SIDE);
  const expanded = dragRegion(original, "se", 5, 5);
  near(expanded.x + expanded.w, 1);
  near(expanded.y + expanded.h, 1);
});

test("matches the native OCR crop pixels after rotation and letterboxing", () => {
  const source = {w:1280,h:720}, expected = {x:200,y:520,w:896,h:154};
  assert.deepEqual(pixelCrop(selected, source.w, source.h), expected);
  for (const [w, h] of [[393, 780], [960, 480], [1024, 768]]) {
    const viewport = containFrame(w, h, source.w, source.h);
    const box = projectCrop(selected, viewport, source.w, source.h);
    near((box.x - viewport.x) / viewport.w * source.w, expected.x);
    near((box.y - viewport.y) / viewport.h * source.h, expected.y);
    near(box.w / viewport.w * source.w, expected.w);
    near(box.h / viewport.h * source.h, expected.h);
    near(viewport.w / viewport.h, source.w / source.h);
  }
});

test("supports independent draft coordinates, normalized serialization and the existing default", () => {
  const draft = dragRegion(selected, "n", 0, -.10);
  assert.notDeepEqual(draft, selected);
  assert.deepEqual(JSON.parse(JSON.stringify(draft)), draft);
  assert.deepEqual(selected, { x: .157, y: .723, w: .7, h: .215 });
  assert.deepEqual(pixelCrop(DEFAULT_SUBTITLE_REGION, 1280, 720), {x:0,y:518,w:1280,h:200});
});
