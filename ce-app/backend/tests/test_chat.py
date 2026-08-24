"""The conversation: does it answer, and does it admit what it is?

The assistant was rebuilt as a chat, and a chat has two failure modes the old
one-shot command did not: it can pretend to be smarter than it is, and it can
break when no model is connected. Both are asserted here rather than trusted.

The rules under test are the ones `BRAIN_DESIGN.md` §7 and STATE.md §4.14/§4.51
were written about:

* nothing is applied without a dry run the user reads first;
* every answer names its source — `offline` is a source;
* a machine with no Ollama and no keys gets an answer, not a 500.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.main import app
from core.assistant import chat

client = TestClient(app)

TIMELINE = {
    "tracks": [{"id": "v1", "kind": "video"}, {"id": "a1", "kind": "audio"}],
    "clips": [
        {"id": "c1", "trackId": "v1", "start": 0.0, "duration": 6.0, "src": "a.mp4"},
        {"id": "c2", "trackId": "v1", "start": 6.0, "duration": 4.0, "src": "b.mp4"},
    ],
    "transitions": [],
}

PERSIAN = "ابپتثجچحخدرزژسشصضطظعغفقکگلمنوهی"


def test_a_question_is_answered_with_no_model_at_all():
    """The common machine: no Ollama running, no keys. This must not crash."""
    turn = chat.reply(
        [{"role": "user", "content": "which part of my video is the strongest?"}],
        TIMELINE, language="en", provider="off",
    )

    assert turn["reply"].strip()
    assert turn["provider"] == "offline"
    assert turn["plan"] is None
    assert turn["steps"], "a chat that cannot say what it did is a black box"


def test_an_editing_request_is_a_plan_the_user_reads_first():
    """The guarantee that makes 'ask it anything' safe."""
    turn = chat.reply(
        [{"role": "user", "content": "remove the silence"}],
        TIMELINE, language="en", provider="off",
    )

    assert turn["plan"] is not None, "the editing request produced no plan"
    assert turn["plan"]["ops"], "the plan has no operations"
    assert turn["plan"]["preview"], "the plan is not described in words"
    # Nothing has happened: the plan is the whole payload.
    assert "nothing has changed" in turn["reply"].lower()


def test_every_answer_says_where_it_came_from():
    for text in ("remove the silence", "how long is my timeline?"):
        turn = chat.reply([{"role": "user", "content": text}], TIMELINE, provider="off")
        assert turn["provider"], "an answer with no source is a rumour"


def test_a_persian_question_is_answered_in_persian():
    """The old offline answer switched to English mid-sentence."""
    turn = chat.reply(
        [{"role": "user", "content": "تایم‌لاین من چطور است؟"}],
        TIMELINE, language="fa", provider="off",
    )

    assert any(letter in turn["reply"] for letter in PERSIAN)
    assert "cut silence" not in turn["reply"], "the Persian reply fell back to the English list"
    assert all(step["fa"] for step in turn["steps"])


def test_the_steps_carry_real_numbers():
    turn = chat.reply([{"role": "user", "content": "hi"}], TIMELINE, provider="off")

    for step in turn["steps"]:
        assert step["en"] and step["fa"]
        assert step["ms"] >= 0
    assert turn["seconds"] >= 0.0


def test_a_provider_nobody_has_heard_of_degrades():
    """A typo in a select box must not be a 500."""
    turn = chat.reply(
        [{"role": "user", "content": "hello"}], TIMELINE, provider="not-a-provider",
    )

    assert turn["reply"].strip()
    assert turn["provider"] == "offline"


def test_an_empty_conversation_does_not_crash():
    turn = chat.reply([], TIMELINE, provider="off")

    assert isinstance(turn["reply"], str)
    assert turn["plan"] is None


@pytest.mark.parametrize("asked", [
    # «بخش» contains «خش», the hiss people ask to remove. This question used to
    # come back as a noise-reduction plan.
    "کدام بخش قوی‌تر است؟",
    # «نمایش» contains «نما», the word for a shot.
    "نمایش را عوض کن",
    "which part is the strongest?",
    "how long is my timeline?",
])
def test_a_question_never_comes_back_as_an_edit(asked):
    """A substring is not a word.

    The dry run makes this survivable rather than dangerous — nothing is applied
    — but an assistant that answers a question with an unrelated plan is an
    assistant the user stops talking to.
    """
    turn = chat.reply([{"role": "user", "content": asked}], TIMELINE, provider="off")

    assert turn["plan"] is None, f"{asked!r} was read as an editing request"


@pytest.mark.parametrize("asked,op", [
    ("نویز را کم کن", "denoise"),
    ("خش‌خش صدا را بگیر", "denoise"),
    ("در هر نما برش بزن", "splitScenes"),
    ("remove the silence", "removeSilence"),
])
def test_the_requests_that_are_edits_still_are(asked, op):
    """The word-boundary fix must not deafen the assistant."""
    turn = chat.reply([{"role": "user", "content": asked}], TIMELINE, provider="off")

    assert turn["plan"] is not None, f"{asked!r} was read as a question"
    assert op in [o["op"] for o in turn["plan"]["ops"]]


# ------------------------------------------------------------------ the API


def test_the_chat_endpoint_answers_and_names_its_choices():
    choices = client.get("/api/assistant/providers")
    assert choices.status_code == 200
    body = choices.json()
    assert "auto" in body["choices"] and "off" in body["choices"]
    # Checked, not assumed: Ollama may be installed and switched off, or running
    # and never enabled. Both facts are reported, and they are different facts.
    assert set(body["available"]["ollama"]) >= {"ready", "installed", "enabled"}

    answer = client.post(
        "/api/assistant/chat",
        json={
            "messages": [{"role": "user", "content": "remove the silence"}],
            "timeline": TIMELINE,
            "language": "en",
            "provider": "off",
        },
    )
    assert answer.status_code == 200
    payload = answer.json()
    assert payload["plan"]["ops"]
    assert payload["steps"]


@pytest.mark.parametrize("language", ["en", "fa"])
def test_the_endpoint_follows_the_language(language):
    answer = client.post(
        "/api/assistant/chat",
        json={
            "messages": [{"role": "user", "content": "سلام"}],
            "timeline": TIMELINE,
            "language": language,
            "provider": "off",
        },
    )

    assert answer.status_code == 200
    reply = answer.json()["reply"]
    if language == "fa":
        assert any(letter in reply for letter in PERSIAN)
        assert "Your timeline:" not in reply
    else:
        assert "Your timeline:" in reply
