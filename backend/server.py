import asyncio
import hashlib
import logging
import os
import re
import uuid
from contextlib import suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, List, Optional

from bson import ObjectId
from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI, HTTPException, Query
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel, BeforeValidator, ConfigDict, Field
from pymongo import ASCENDING, DESCENDING, IndexModel
from starlette.middleware.cors import CORSMiddleware

ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / ".env")

from emergentintegrations.llm.chat import LlmChat, UserMessage  # noqa: E402

mongo_url = os.environ["MONGO_URL"]
client = AsyncIOMotorClient(mongo_url)
db = client[os.environ["DB_NAME"]]

EMERGENT_LLM_KEY = os.environ["EMERGENT_LLM_KEY"]
TRANSLATE_PROVIDER = os.environ["TRANSLATE_PROVIDER"]
TRANSLATE_MODEL = os.environ["TRANSLATE_MODEL"]

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

INDEXES: dict[str, list[IndexModel]] = {
    "translation_cache": [IndexModel([("key", ASCENDING)], name="key", unique=True)],
    "subtitle_history": [IndexModel([("created_at", DESCENDING)], name="created_desc")],
}


async def ensure_indexes() -> None:
    for collection, models in INDEXES.items():
        for model in models:
            try:
                await db[collection].create_indexes([model])
            except Exception as exc:
                logger.error("ensure_indexes(%s.%s): %s", collection, model.document["name"], exc)


# ---------- Mongo models ----------
PyObjectId = Annotated[str, BeforeValidator(lambda v: str(v) if isinstance(v, ObjectId) else v)]


class BaseDocument(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    id: Optional[PyObjectId] = Field(default=None, alias="_id")

    def to_mongo(self) -> dict:
        data = self.model_dump(by_alias=True, exclude_none=True)
        data.pop("_id", None)
        return data

    @classmethod
    def from_mongo(cls, doc: dict):
        return cls.model_validate(doc)


class CacheEntry(BaseDocument):
    key: str
    source_text: str
    translated_text: str
    model: str
    created_at: str


class HistoryEntry(BaseDocument):
    source_text: str
    translated_text: str
    source_lang: str
    target_lang: str
    cached: bool
    latency_ms: int
    created_at: str


class HistoryOut(BaseModel):
    id: str
    source_text: str
    translated_text: str
    source_lang: str
    target_lang: str
    cached: bool
    latency_ms: int
    created_at: str


class TranslateIn(BaseModel):
    text: str = Field(min_length=1, max_length=600)
    source_lang: str = "en"
    target_lang: str = "tr"
    glossary: List[str] = Field(default_factory=list, max_length=200)
    save_history: bool = True


class TranslateOut(BaseModel):
    source_text: str
    translated_text: str
    cached: bool
    latency_ms: int
    engine: str
    history_id: Optional[str] = None


LANG_NAMES = {"en": "English", "tr": "Turkish", "de": "German", "fr": "French", "es": "Spanish", "ja": "Japanese"}


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def build_system_prompt(src: str, tgt: str, glossary: List[str]) -> str:
    glossary_line = (
        "Never translate these protected names/terms, keep them exactly as written: " + ", ".join(glossary) + "."
        if glossary
        else ""
    )
    return (
        f"You translate video game subtitles from {LANG_NAMES.get(src, src)} to {LANG_NAMES.get(tgt, tgt)}. "
        "Rules: output ONLY the translation, no quotes, no notes. Keep the sentence whole and natural, "
        "use correct target-language punctuation. Keep character names, place names, proper nouns and game-specific "
        "terms untranslated. If a line starts with a speaker label like 'NAME:' keep the label unchanged. "
        "If the input is OCR noise with no meaning, output exactly an empty string. "
        + glossary_line
    )


# ---------- App ----------
app = FastAPI()


@app.get("/health", include_in_schema=False)
@app.head("/health", include_in_schema=False)
async def health():
    return {"status": "ok"}


api_router = APIRouter(prefix="/api")


@api_router.get("/")
async def root():
    return {"service": "ps5-subtitle-translator", "engine": f"{TRANSLATE_PROVIDER}/{TRANSLATE_MODEL}"}


@api_router.post("/translate", response_model=TranslateOut)
async def translate(payload: TranslateIn):
    text = normalize(payload.text)
    if not text:
        raise HTTPException(status_code=400, detail="Boş metin çevrilemez.")
    glossary = sorted({g.strip() for g in payload.glossary if g.strip()})
    key = hashlib.sha256(
        f"{payload.source_lang}|{payload.target_lang}|{'|'.join(glossary)}|{text.lower()}".encode()
    ).hexdigest()
    started = datetime.now(timezone.utc)

    cached_doc = await db.translation_cache.find_one({"key": key})
    cached = cached_doc is not None
    if cached:
        translated = CacheEntry.from_mongo(cached_doc).translated_text
    else:
        chat = LlmChat(
            api_key=EMERGENT_LLM_KEY,
            session_id=f"tr-{uuid.uuid4()}",
            system_message=build_system_prompt(payload.source_lang, payload.target_lang, glossary),
        ).with_model(TRANSLATE_PROVIDER, TRANSLATE_MODEL)
        try:
            # Non-streaming on purpose: the TV protocol needs one complete subtitle string per message.
            translated = normalize(str(await asyncio.wait_for(chat.send_message(UserMessage(text=text)), timeout=15)))
        except asyncio.TimeoutError:
            raise HTTPException(status_code=504, detail="Çeviri servisi zaman aşımına uğradı (15 sn).")
        except Exception as exc:
            logger.exception("translation failed")
            raise HTTPException(status_code=502, detail=f"Çeviri servisi hatası: {exc}")
        translated = translated.strip('"“”')
        entry = CacheEntry(
            key=key,
            source_text=text,
            translated_text=translated,
            model=TRANSLATE_MODEL,
            created_at=datetime.now(timezone.utc).isoformat(),
        )
        with suppress(Exception):
            await db.translation_cache.insert_one(entry.to_mongo())

    latency_ms = int((datetime.now(timezone.utc) - started).total_seconds() * 1000)
    history_id = None
    last = await db.subtitle_history.find_one({}, sort=[("created_at", -1)])
    duplicate = last is not None and HistoryEntry.from_mongo(last).source_text.lower() == text.lower()
    if payload.save_history and translated and not duplicate:
        hist = HistoryEntry(
            source_text=text,
            translated_text=translated,
            source_lang=payload.source_lang,
            target_lang=payload.target_lang,
            cached=cached,
            latency_ms=latency_ms,
            created_at=datetime.now(timezone.utc).isoformat(),
        )
        res = await db.subtitle_history.insert_one(hist.to_mongo())
        history_id = str(res.inserted_id)

    return TranslateOut(
        source_text=text,
        translated_text=translated,
        cached=cached,
        latency_ms=latency_ms,
        engine=f"{TRANSLATE_PROVIDER}/{TRANSLATE_MODEL}",
        history_id=history_id,
    )


@api_router.get("/history", response_model=List[HistoryOut])
async def get_history(limit: int = Query(30, ge=1, le=200)):
    docs = await db.subtitle_history.find({}).sort("created_at", -1).limit(limit).to_list(limit)
    out = []
    for d in docs:
        h = HistoryEntry.from_mongo(d)
        out.append(HistoryOut(**h.model_dump(exclude={"id"}), id=h.id))
    return out


@api_router.delete("/history")
async def clear_history():
    res = await db.subtitle_history.delete_many({})
    return {"deleted": res.deleted_count}


app.include_router(api_router)

app.add_middleware(
    CORSMiddleware,
    allow_credentials=True,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
async def start_index_build():
    app.state.index_task = asyncio.create_task(ensure_indexes())


@app.on_event("shutdown")
async def shutdown_db_client():
    app.state.index_task.cancel()
    with suppress(asyncio.CancelledError):
        await app.state.index_task
    client.close()
