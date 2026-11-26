"""Render Android snapshots with the TV client in desktop Chromium."""

import json
import os
from pathlib import Path
import subprocess
import sys

from playwright.sync_api import sync_playwright


def main(classpath, tv_root):
    output = subprocess.run(
        ["java", "-cp", classpath, "HarnessKt"],
        input="FIXMENU\nFIXPHONE\nQUIT\n", capture_output=True, text=True, check=True, timeout=10,
    )
    ready, menu, phone, bye = output.stdout.splitlines()
    assert (ready, bye) == ("READY", "BYE")
    fixtures = [json.loads(menu), json.loads(phone)]
    tv = Path(tv_root)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            executable_path=os.environ.get("CHROMIUM", "/usr/bin/chromium"),
            args=["--disable-dev-shm-usage"],
        )
        page = browser.new_page(viewport={"width": 1920, "height": 1080})
        page.set_content('<!doctype html><div id="textOverlay"></div>')
        page.add_style_tag(path=str(tv / "css/style.css"))
        page.add_script_tag(path=str(tv / "js/text-overlay.js"))
        rows = page.evaluate("""snapshots => {
          const container = document.getElementById("textOverlay");
          const overlay = new Ps5TextOverlay(container, window);
          return [26, 34, 46, 58].map(size => {
            overlay.appearance(size, "backplate", "contain");
            return snapshots.map(snapshot => {
              overlay.update(snapshot);
              const boxes = Array.from(container.children, node => {
                const b = node.getBoundingClientRect();
                const style = getComputedStyle(node);
                return {
                  x: b.left, y: b.top, w: b.width, h: b.height,
                  backplate: style.backgroundColor, text: node.textContent, visible: style.visibility === "visible",
                };
              });
              overlay.clear();
              return boxes;
            });
          });
        }""", fixtures)
        browser.close()

    for menu_boxes, phone_boxes in rows:
        assert len(menu_boxes) == 5 and len(phone_boxes) == 3
        assert next(box for box in menu_boxes if box["text"] == "Harita")["w"] < 192
        for boxes in (menu_boxes, phone_boxes):
            for i, box in enumerate(boxes):
                assert box["visible"], box
                assert box["backplate"].startswith("rgba(0, 0, 0")
                assert box["x"] >= 0 and box["x"] + box["w"] <= 1921
                assert box["y"] >= 0 and box["y"] + box["h"] <= 1081
                for other in boxes[i + 1:]:
                    width = min(box["x"] + box["w"], other["x"] + other["w"]) - max(box["x"], other["x"])
                    height = min(box["y"] + box["h"], other["y"] + other["h"]) - max(box["y"], other["y"])
                    assert width <= 1 or height <= 1, (box, other)
        assert next(box for box in phone_boxes if box["text"].startswith("Mandy"))["h"] > 35
    print("TV JavaScript/CSS in desktop Chromium: 2 scenes × 4 font sizes without backplate overlap")


if __name__ == "__main__":
    main(*sys.argv[1:])
