"""The eight extension doors (webhook, provenance, DNA-RAG, autotag, fanout,
vault, chain, batch) — all local-first, all honest when a piece is absent."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel, Field

from core import extensions, workflows
from core.assistant import providers

router = APIRouter(prefix="/api/extend", tags=["extend"])


class WebhookTest(BaseModel):
    event: str = "test"
    payload: dict = {}


@router.post("/webhook/test")
def webhook_test(payload: WebhookTest) -> dict:
    return extensions.send_webhook(payload.event, payload.payload)


class ProvenanceRequest(BaseModel):
    edit: dict


@router.post("/provenance")
def provenance(payload: ProvenanceRequest) -> dict:
    return extensions.build_provenance(payload.edit)


class DnaSave(BaseModel):
    name: str
    dna: dict


@router.post("/dna/save")
def dna_save(payload: DnaSave) -> dict:
    return extensions.save_dna(payload.name, {**payload.dna, "name": payload.name})


class DnaMatch(BaseModel):
    dna: dict


@router.post("/dna/match")
def dna_match(payload: DnaMatch) -> dict:
    return extensions.match_dna(payload.dna) or {"match": None}


class AutoTag(BaseModel):
    signals: dict


@router.post("/autotag")
def autotag(payload: AutoTag) -> dict:
    return {"tags": extensions.autotag(payload.signals)}


class TagSearch(BaseModel):
    query: str
    tagged: list[dict] = []


@router.post("/autotag/search")
def tag_search(payload: TagSearch) -> dict:
    return {"results": extensions.search_tags(payload.query, payload.tagged)}


class Fanout(BaseModel):
    name: str
    platforms: list[str] = Field(default=["tiktok", "reels", "shorts"])


@router.post("/fanout")
def fanout(payload: Fanout) -> dict:
    return extensions.fanout(payload.name, payload.platforms)


class VaultSet(BaseModel):
    service: str
    value: str


@router.post("/vault/set")
def vault_set(payload: VaultSet) -> dict:
    return extensions.vault_set(payload.service, payload.value)


class VaultGet(BaseModel):
    service: str


@router.post("/vault/get")
def vault_get(payload: VaultGet) -> dict:
    return {"value": extensions.vault_get(payload.service)}


@router.get("/vault/list")
def vault_list() -> dict:
    return {"services": extensions.vault_list()}


class ChainRequest(BaseModel):
    text: str


@router.post("/chain")
def chain(payload: ChainRequest) -> dict:
    return providers.run_chain(payload.text)


class BatchRequest(BaseModel):
    dir: str
    preset: str = "shorts"


@router.post("/batch")
def batch(payload: BatchRequest) -> dict:
    return workflows.run_batch(payload.dir, payload.preset)
