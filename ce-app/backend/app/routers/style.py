"""Style templates: analyse a reference video, then rebuild footage in its shape.

Two long operations, both in worker threads: decoding and measuring a whole video
is seconds of CPU and the event loop has a WebSocket to keep answering.
"""
from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from core.engine import cancellation, intent as intent_model, style
from core.tasks import tasks

router = APIRouter(prefix="/api/style", tags=["style"])


class AnalyseRequest(BaseModel):
    path: str
    name: str | None = None
    save: bool = True


class ApplyRequest(BaseModel):
    path: str = Field(description="The user's own footage")
    template: str | None = Field(default=None, description="Saved template name")
    inline: dict | None = Field(default=None, description="A template document, instead of a saved one")
    name: str = "Styled edit"
    music: str | None = Field(default=None, description="Optional music bed of your own")
    captions: bool = Field(default=True, description="Transcribe and lay captions automatically")
    brain: bool = Field(default=True, description="Let a local model race the rule planner")
    model: str | None = Field(default=None, description="Ollama model to race with, when installed")
    use_plan: str | None = Field(default=None, description="B10: apply a specific planner's picks from the scoreboard instead of the winner")
    intensity: float = Field(default=0.5, ge=0.0, le=1.0, description="Tier 3: the 'more TikTok' dial — caption pop + zoom punch + cut rate")
    intent: dict | None = Field(
        default=None,
        description=(
            "What the video is for: kind, goal, focus, energy, keep/avoid phrases, "
            "target seconds. Every field is optional and neutral by default."
        ),
    )


@router.post("/analyze")
async def analyse(payload: AnalyseRequest) -> dict:
    loop = asyncio.get_running_loop()
    try:
        template = await loop.run_in_executor(None, style.analyse, payload.path, payload.name)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except Exception as error:  # noqa: BLE001 - surfaced to the user, not swallowed
        raise HTTPException(status_code=422, detail=str(error)) from error

    if payload.save:
        style.save_template(template)
    return template.as_dict()


@router.post("/analyze/start")
async def analyse_start(payload: AnalyseRequest) -> dict:
    """Begin an analysis and answer immediately with a task to watch.

    The synchronous `/analyze` above still exists — it is what the tests and any
    script use. What a *screen* must not do is hold a request open for a minute:
    the client's budget is 30 s, and this is precisely the shape of the failure
    the user reported in 0.5.3.
    """
    def work(reporter) -> dict:
        cancellation.bind(reporter.cancel_event)
        try:
            template = style.analyse(
                payload.path, payload.name,
                progress=lambda stage, fraction, label="": reporter.stage(stage, fraction, label),
            )
            if payload.save:
                style.save_template(template)
            return template.as_dict()
        finally:
            cancellation.bind(None)

    return tasks.start("style:analyze", work).as_dict()


@router.get("/questions")
def questions() -> dict:
    """The intake questionnaire itself, so the screen renders from one source.

    The answers are what the analysis cannot measure — what the video is, what it
    is for, what should survive the cut. Options live here rather than in the
    renderer because the *weights* behind them live in `core.engine.intent`, and
    a question whose effect is defined somewhere else will drift out of step with
    it the first time either changes.
    """
    return intent_model.options()


class BrainRequest(BaseModel):
    template: dict | None = Field(default=None, description="A measured reference template")
    footage: str | None = Field(default=None, description="The user's footage path, to measure")


@router.post("/brain")
async def brain(payload: BrainRequest) -> dict:
    """The brain interrogates itself, on screen.

    Reference in: every intake question the reference can answer, answered with
    the number behind it. Footage in: the same for the footage, plus a menu of
    genuinely different ways to start the edit. Decoding footage is seconds of
    FFmpeg, so it runs off the event loop.
    """
    from core.brain import intake  # noqa: PLC0415

    if payload.template is None and not payload.footage:
        raise HTTPException(status_code=422, detail="give the brain a template or footage")
    template = payload.template or {}

    def work() -> dict:
        ref_qa = intake.answer_reference(template) if template else []
        sig = intake.measure_footage(payload.footage) if payload.footage else None
        foot_qa = intake.answer_footage(template, sig) if sig else []
        options = intake.edit_options(template, sig) if template else []
        return {"reference_qa": ref_qa, "footage_qa": foot_qa,
                "footage_signals": sig, "options": options}

    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, work)


class ImportRequest(BaseModel):
    template: dict = Field(description="A template document, e.g. from an exported .cetemplate")
    name: str | None = Field(default=None, description="Optional rename on import")


@router.post("/templates/import")
def import_template(payload: ImportRequest) -> dict:
    """Save an outside template after checking it. 422 lists what is wrong."""
    try:
        path = style.import_template(payload.template, payload.name)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return {"saved": path.name, "name": payload.name or payload.template.get("name")}


@router.get("/starters")
def starters() -> dict:
    """Hand-authored rhythms so a fresh gallery is not empty."""
    return {"starters": style.starters()}


@router.get("/templates")
def templates() -> dict:
    return {"templates": style.list_templates()}


@router.get("/templates/{name}")
def read_template(name: str) -> dict:
    try:
        return style.load_template(name)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"No template called {name}") from error


@router.delete("/templates/{name}")
def remove_template(name: str) -> dict:
    style.delete_template(name)
    return {"deleted": name}


@router.post("/apply")
async def apply(payload: ApplyRequest) -> dict:
    """Cut the user's footage into the template and return an editor document."""
    document = payload.inline
    if document is None:
        if not payload.template:
            raise HTTPException(status_code=422, detail="Give a template name or an inline template")
        try:
            document = style.load_template(payload.template)
        except FileNotFoundError as error:
            raise HTTPException(status_code=404, detail=f"No template called {payload.template}") from error

    # Captions are part of "automatic": transcribe unless the model is missing,
    # in which case the edit is still produced and the omission is reported.
    cues: list[dict] | None = None
    loop = asyncio.get_running_loop()
    # The owner's answer outrules the reference's implication: a Persian audience
    # watching an English reference still wants Persian captions, and "no
    # captions" means a minute of Whisper is not spent.
    _choice = intent_model.Intent.from_dict(payload.intent).caption_preference()
    wants_captions = payload.captions and (
        _choice["wanted"] if _choice else bool((document.get("captions") or {}).get("wanted"))
    )
    if wants_captions:
        try:
            from core.engine.transcribe import transcribe_to_cues

            result = await loop.run_in_executor(None, transcribe_to_cues, payload.path)
            cues = result.get("cues") or []
        except Exception:  # noqa: BLE001 - unavailable model, or a file with no speech
            cues = None

    try:
        return await loop.run_in_executor(
            None, style.build_timeline, document, payload.path, payload.name,
            payload.music, cues, None, payload.brain, payload.model, payload.intent,
            None, payload.intensity,
        )
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail=f"File not found: {payload.path}") from error
    except Exception as error:  # noqa: BLE001
        raise HTTPException(status_code=422, detail=str(error)) from error


@router.post("/apply/start")
async def apply_start(payload: ApplyRequest) -> dict:
    """The same rebuild as `/apply`, as a task.

    This one can genuinely take minutes: when the template asks for captions the
    whole file goes through Whisper first. Transcription is reported as its own
    stage so the screen can say *why* it is waiting.
    """
    def work(reporter) -> dict:
        cancellation.bind(reporter.cancel_event)
        try:
            document = payload.inline
            if document is None:
                if not payload.template:
                    raise ValueError("Give a template name or an inline template")
                document = style.load_template(payload.template)

            cues: list[dict] | None = None
            _choice = intent_model.Intent.from_dict(payload.intent).caption_preference()
            wants_captions = payload.captions and (
                _choice["wanted"] if _choice
                else bool((document.get("captions") or {}).get("wanted"))
            )
            if wants_captions:
                reporter.stage("transcribe", 0.1, "Transcribing the speech")
                try:
                    from core.engine.transcribe import transcribe_to_cues

                    cues = (transcribe_to_cues(payload.path) or {}).get("cues") or []
                except cancellation.Cancelled:
                    raise
                except Exception:  # noqa: BLE001 - no model, or a file with no speech
                    cues = None

            return style.build_timeline(
                document, payload.path, payload.name, payload.music, cues,
                progress=lambda stage, fraction, label="": reporter.stage(
                    stage, 0.3 + 0.7 * fraction if wants_captions else fraction, label
                ),
                brain=payload.brain,
                model=payload.model,
                intent=payload.intent,
                use_plan=payload.use_plan,
                intensity=payload.intensity,
            )
        finally:
            cancellation.bind(None)

    return tasks.start("style:apply", work).as_dict()


class AiTransitionsRequest(BaseModel):
    timeline: dict
    bpm: float = 120.0


@router.get("/recipes")
def recipes() -> dict:
    return {"recipes": style.recipes()}


@router.post("/ai-transitions")
def ai_transitions(payload: AiTransitionsRequest) -> dict:
    """One music-sized transition per video junction; the editor applies them."""
    return {"transitions": style.suggest_transitions(payload.timeline, payload.bpm)}


class RecipeSaveRequest(BaseModel):
    name: str
    recipe: dict


@router.post("/recipes/save")
def recipe_save(payload: RecipeSaveRequest) -> dict:
    """B5: recipes live in ~/CuttingEdge/recipes as shareable JSON files."""
    import json
    import os
    import re
    from pathlib import Path as _Path

    safe = re.sub(r"[^A-Za-z0-9_\-一-ی ]", "", payload.name).strip() or "recipe"
    folder = _Path(os.path.expanduser("~/CuttingEdge/recipes"))
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / f"{safe}.json"
    dest.write_text(json.dumps(payload.recipe, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"path": str(dest)}


@router.get("/recipes/list")
def recipe_list() -> dict:
    import json
    import os
    from pathlib import Path as _Path

    folder = _Path(os.path.expanduser("~/CuttingEdge/recipes"))
    out = []
    if folder.exists():
        for f in sorted(folder.glob("*.json")):
            try:
                out.append({"name": f.stem, "recipe": json.loads(f.read_text(encoding="utf-8"))})
            except Exception:  # noqa: BLE001
                continue
    return {"recipes": out}
