import os
import uuid
import pytest
import requests

BASE_URL = os.environ.get("EXPO_PUBLIC_BACKEND_URL").rstrip("/")


@pytest.fixture(scope="module")
def s():
    sess = requests.Session()
    sess.headers.update({"Content-Type": "application/json"})
    return sess


# Health/root
def test_root(s):
    r = s.get(f"{BASE_URL}/api/")
    assert r.status_code == 200
    assert "engine" in r.json()


# Translate: basic + cache
class TestTranslate:
    def test_translate_and_cache(self, s):
        txt = f"Where is the hidden key, my friend? {uuid.uuid4().hex[:4]}"
        r = s.post(f"{BASE_URL}/api/translate", json={"text": txt, "source_lang": "en", "target_lang": "tr"}, timeout=60)
        assert r.status_code == 200, r.text
        d = r.json()
        print("TR:", d)
        assert d["translated_text"] and d["translated_text"] != txt
        assert d["cached"] is False
        assert isinstance(d["latency_ms"], int)
        r2 = s.post(f"{BASE_URL}/api/translate", json={"text": txt, "source_lang": "en", "target_lang": "tr"}, timeout=60)
        assert r2.status_code == 200
        assert r2.json()["cached"] is True
        assert r2.json()["translated_text"] == d["translated_text"]

    def test_whitespace_400(self, s):
        r = s.post(f"{BASE_URL}/api/translate", json={"text": "   \n "})
        assert r.status_code == 400

    def test_empty_string(self, s):
        r = s.post(f"{BASE_URL}/api/translate", json={"text": ""})
        print("empty status", r.status_code)
        assert r.status_code in (400, 422)

    def test_glossary_preserved(self, s):
        txt = "Kratos and Atreus must travel to Midgard before nightfall."
        r = s.post(f"{BASE_URL}/api/translate",
                   json={"text": txt, "glossary": ["Kratos", "Atreus", "Midgard"], "save_history": False}, timeout=60)
        assert r.status_code == 200, r.text
        out = r.json()["translated_text"]
        print("GLOSSARY:", out)
        for n in ["Kratos", "Atreus", "Midgard"]:
            assert n in out
        assert r.json()["history_id"] is None

    def test_speaker_label(self, s):
        r = s.post(f"{BASE_URL}/api/translate", json={"text": "ELLIE: We need to keep moving.", "save_history": False}, timeout=60)
        assert r.status_code == 200
        print("LABEL:", r.json()["translated_text"])
        assert r.json()["translated_text"].startswith("ELLIE:")


# History list (DELETE /api/history intentionally NOT run: bulk delete of user data)
def test_history_no_id(s):
    r = s.get(f"{BASE_URL}/api/history?limit=5")
    assert r.status_code == 200
    items = r.json()
    assert isinstance(items, list) and len(items) >= 1
    for it in items:
        assert "_id" not in it and "id" in it and "translated_text" in it


def test_history_limit_validation(s):
    assert s.get(f"{BASE_URL}/api/history?limit=0").status_code == 422


# Iteration 2: history dedupe of consecutive identical translations
class TestHistoryDedupe:
    def test_repeat_not_duplicated(self, s):
        txt = f"TEST_ The bridge is collapsing, run now {uuid.uuid4().hex[:5]}"
        r1 = s.post(f"{BASE_URL}/api/translate", json={"text": txt}, timeout=60)
        assert r1.status_code == 200, r1.text
        assert r1.json()["history_id"] is not None
        r2 = s.post(f"{BASE_URL}/api/translate", json={"text": txt}, timeout=60)
        assert r2.status_code == 200
        assert r2.json()["cached"] is True
        assert r2.json()["history_id"] is None
        # case-insensitive repeat also deduped
        r3 = s.post(f"{BASE_URL}/api/translate", json={"text": txt.upper()}, timeout=60)
        assert r3.status_code == 200
        assert r3.json()["history_id"] is None
        items = s.get(f"{BASE_URL}/api/history?limit=5").json()
        matches = [i for i in items if i["source_text"].lower() == txt.lower()]
        assert len(matches) == 1, matches

    def test_too_long_422(self, s):
        r = s.post(f"{BASE_URL}/api/translate", json={"text": "a" * 700})
        assert r.status_code == 422
