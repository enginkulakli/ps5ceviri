import os
import uuid
import pytest
import requests

BASE_URL = os.environ.get("EXPO_PUBLIC_BACKEND_URL").rstrip("/")
URL = f"{BASE_URL}/api/translate"


@pytest.fixture(scope="module")
def s():
    sess = requests.Session()
    sess.headers.update({"Content-Type": "application/json"})
    return sess


# Quality modes: best (default) and fast
class TestQuality:
    def test_default_is_best_boat(self, s):
        r = s.post(URL, json={"text": "Get to the boat, quickly!", "save_history": False}, timeout=60)
        assert r.status_code == 200, r.text
        d = r.json()
        print("BEST boat:", d)
        assert "sonnet" in d["engine"]
        assert "Tekneye" in d["translated_text"]
        assert "çabuk" in d["translated_text"].lower()

    def test_fast_boat(self, s):
        r = s.post(URL, json={"text": "Get to the boat, quickly!", "quality": "fast", "save_history": False}, timeout=60)
        assert r.status_code == 200, r.text
        d = r.json()
        print("FAST boat:", d)
        assert "haiku" in d["engine"]
        assert "tekneye" in d["translated_text"].lower()

    def test_invalid_quality_422(self, s):
        r = s.post(URL, json={"text": "Hello", "quality": "ultra", "save_history": False}, timeout=30)
        assert r.status_code == 422

    def test_glossary_apostrophe_suffix(self, s):
        r = s.post(URL, json={"text": "Kratos, we need to go to Midgard.", "glossary": ["Kratos", "Midgard"],
                              "save_history": False}, timeout=60)
        assert r.status_code == 200, r.text
        out = r.json()["translated_text"]
        print("GLOSSARY best:", out)
        assert "Kratos" in out
        assert "Midgard'a" in out or "Midgard’a" in out

    def test_glossary_apostrophe_fast(self, s):
        r = s.post(URL, json={"text": "Atreus, wait for me at Midgard.", "glossary": ["Atreus", "Midgard"],
                              "quality": "fast", "save_history": False}, timeout=60)
        assert r.status_code == 200, r.text
        out = r.json()["translated_text"]
        print("GLOSSARY fast:", out)
        assert "Atreus" in out and "Midgard" in out

    def test_repeat_cached_per_quality(self, s):
        txt = f"TEST_ Hold the line, soldier {uuid.uuid4().hex[:5]}!"
        for q in ("best", "fast"):
            r1 = s.post(URL, json={"text": txt, "quality": q, "save_history": False}, timeout=60)
            assert r1.status_code == 200, r1.text
            assert r1.json()["cached"] is False
            r2 = s.post(URL, json={"text": txt, "quality": q, "save_history": False}, timeout=60)
            assert r2.json()["cached"] is True
            assert r2.json()["translated_text"] == r1.json()["translated_text"]
            print(q, r1.json()["latency_ms"], "ms ->", r2.json()["latency_ms"], "ms")

    def test_empty_400(self, s):
        r = s.post(URL, json={"text": "  "})
        assert r.status_code == 400
        assert r.json()["detail"]

    def test_too_long_422(self, s):
        r = s.post(URL, json={"text": "a" * 601})
        assert r.status_code == 422

    def test_history_list(self, s):
        r = s.get(f"{BASE_URL}/api/history?limit=5")
        assert r.status_code == 200
        data = r.json()
        assert isinstance(data, list)
        for h in data:
            assert "_id" not in h and "id" in h and "translated_text" in h
