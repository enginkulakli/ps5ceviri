"""Run the Android archive's real SQL against a host SQLite database."""
from pathlib import Path
import re
import sqlite3
import statistics
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "frontend/modules/ps5-translator/android/src/main/java/expo/modules/ps5translator/TranslationArchive.kt").read_text()
LOOKUP = re.search(r'"(SELECT translated FROM records WHERE .*?)"\s*\+', SOURCE)[1]
ENGINE_FILTER = re.search(r'else "( AND \(engine=\? OR engine=.*?\))"', SOURCE)[1]
ORDER = re.search(r'"( ORDER BY curated DESC, engine=\? DESC, id DESC LIMIT 1)"', SOURCE)[1]
SCHEMA = re.findall(r'db\.execSQL\("""(.*?)"""\.trimIndent\(\)\)|db\.execSQL\("((?:CREATE|INSERT).*?)"\)', SOURCE, re.S)


class ArchiveSqlTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.db = sqlite3.connect(Path(self.directory.name) / "archive.db")
        self.db.execute("PRAGMA foreign_keys=ON")
        for multiline, inline in SCHEMA:
            self.db.execute(multiline or inline)
        self.db.executemany("INSERT INTO games VALUES (?,?)", [("game-a", "A"), ("game-b", "B")])

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def insert(self, game, source, translation, engine="offline", version="v3", curated=0):
        return self.db.execute(
            "INSERT OR IGNORE INTO records(game,lang,source,normalized,translated,engine,created,version,curated)"
            "VALUES(?, 'en', ?, ?, ?, ?, 1234, ?, ?)",
            (game, source, source, translation, engine, version, curated),
        ).rowcount

    def lookup(self, game, source, engine="offline", version="v3", share=False):
        args = (game, source, version, engine) if share else (game, source, version, engine, engine)
        return self.db.execute(LOOKUP + ("" if share else ENGINE_FILTER) + ORDER, args).fetchone()

    def test_profiles_engine_rules_curated_and_duplicate_import(self):
        self.insert("game-a", "bank", "nehir kıyısı", "google_cloud")
        self.insert("game-b", "bank", "banka")
        self.insert("general", "bank", "set")
        self.assertIsNone(self.lookup("game-a", "bank"))
        self.assertEqual(self.lookup("game-a", "bank", share=True), ("nehir kıyısı",))
        self.assertEqual(self.lookup("game-b", "bank"), ("banka",))
        self.assertEqual(self.lookup("general", "bank"), ("set",))
        self.assertIsNone(self.lookup("game-a", "bank", "google_cloud", version="v4"))
        self.assertEqual(self.insert("game-b", "bank", "silme"), 0)
        self.insert("game-a", "bank", "Kıyı", "package", "editorial-1", 1)
        self.assertEqual(self.lookup("game-a", "bank"), ("Kıyı",))
        self.assertIsNone(self.lookup("game-b", "absent"))

    def test_invalid_game_rolls_back_transaction(self):
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.insert("game-a", "temporary", "Geçici")
                self.db.execute("INSERT INTO records(game,lang,source,normalized,translated,engine,created,version)"
                                "VALUES('absent','en','x','x','y','offline',1,'v3')")
        self.assertIsNone(self.lookup("game-a", "temporary"))

    def test_large_archive_uses_index(self):
        self.db.executemany("INSERT INTO records(game,lang,source,normalized,translated,engine,created,version)"
                            "VALUES('game-a','en',?,?,?,'offline',1,'v3')",
                            ((f"quest {i}", f"quest {i}", f"görev {i}") for i in range(50000)))
        samples = []
        for i in range(0, 50000, 137):
            started = time.perf_counter_ns()
            self.assertEqual(self.lookup("game-a", f"quest {i}"), (f"görev {i}",))
            samples.append((time.perf_counter_ns() - started) / 1e6)
        plan = self.db.execute("EXPLAIN QUERY PLAN " + LOOKUP + ENGINE_FILTER + ORDER,
                               ("game-a", "quest 5000", "v3", "offline", "offline")).fetchall()
        self.assertTrue(any("INDEX" in row[3] and "normalized" in row[3] for row in plan), plan)
        print(f"Host SQLite 50,000 rows: p50={statistics.median(samples):.3f} ms "
              f"p95={statistics.quantiles(samples, n=100)[94]:.3f} ms")


if __name__ == "__main__":
    unittest.main()
