"""Exercise the Android editor's React UI in a local Expo Web preview."""
import os
from playwright.sync_api import sync_playwright

with sync_playwright() as api:
    browser = api.chromium.launch(executable_path=os.environ.get('CHROMIUM', '/usr/bin/chromium'), args=['--disable-dev-shm-usage'])
    page = browser.new_page(viewport={'width':800, 'height':960})
    page.on("pageerror", lambda error: print("Browser runtime:", str(error)[:270], flush=True))
    base = os.environ.get('PS5_EDITOR_TEST_URL', 'http://127.0.0.1:19097').rstrip('/')
    page.goto(base + '/ocr-region', wait_until='domcontentloaded', timeout=90_000)
    edit = page.get_by_test_id('edit-remote-region')
    edit.wait_for(timeout=90_000)
    edit.click()
    selection = page.get_by_test_id('region-editor-selection')
    selection.wait_for()
    def rect(locator=selection):
        return locator.bounding_box()
    def drag(locator, dx, dy):
        box = rect(locator)
        x = box['x'] + box['width']*.45
        y = box['y'] + box['height']*.47
        page.mouse.move(x,y)
        page.mouse.down()
        page.mouse.move(x+dx,y+dy,steps=11)
        page.mouse.up()
    def width():
        return page.get_by_test_id('region-width-value').inner_text()
    def wait_closed():
        selection.wait_for(state='hidden')
    def normalized():
        box=rect()
        stage=page.get_by_test_id('region-editor-stage').bounding_box()
        frame_w=min(stage['width'],stage['height']*16/9)
        frame_h=frame_w*9/16
        return ((box['x']-stage['x']-(stage['width']-frame_w)/2)/frame_w,
                (box['y']-stage['y']-(stage['height']-frame_h)/2)/frame_h,
                box['width']/frame_w,box['height']/frame_h)
    initial = rect()
    drag(selection, 0, -55)
    assert rect()['y'] < initial['y'] - 20, (initial, rect())
    page.get_by_text('İptal', exact=True).click()
    edit.click()
    assert abs(rect()['y'] - initial['y']) < 2, (initial, rect())
    drag(page.get_by_test_id('region-editor-handle-ne'), -100, -35)
    saved_box = rect()
    assert saved_box['width'] < initial['width'] - 40 and saved_box['height'] > initial['height'] + 12, (initial,saved_box)
    saved_normalized = normalized()
    page.get_by_test_id('save-remote-region').click()
    wait_closed()
    assert '%100' not in width()
    page.set_viewport_size({'width':1000,'height':590})
    page.goto(base + '/ocr-region?edit=1', wait_until='domcontentloaded')
    selection.wait_for(timeout=60_000)
    assert all(abs(a-b)<.007 for a,b in zip(normalized(), saved_normalized)), (normalized(),saved_normalized)
    page.get_by_text('Varsayılana Sıfırla', exact=True).click()
    page.get_by_text('İptal', exact=True).click()
    wait_closed()
    assert '%100' not in width()
    edit.click()
    page.get_by_text('Varsayılana Sıfırla', exact=True).click()
    page.get_by_test_id('save-remote-region').click()
    wait_closed()
    assert '%100' in width()
    print("Chromium RN Web editor: pointer move, corner resize, Cancel, Save, rotation and reset passed")
    browser.close()
