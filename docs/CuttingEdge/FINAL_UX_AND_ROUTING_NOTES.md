# Final UX + routing notes (1.0)

Two MIT open-source projects were evaluated at the owner's request and folded in
where they genuinely help. Both are credited; neither is vendored as runtime code.

## ui-ux-pro-max-skill (MIT) — design intelligence
Used as the *checklist* for the final polish, not as shipped code. The app already
implements its highest-value rules:

- single, consistent brand mark (shared-element hero → docked);
- visible `:focus-visible` rings and `prefers-reduced-motion` fallbacks;
- loading states for every long task (progress + elapsed + Stop), never a spinner
  with no escape;
- confidence/“why” shown next to every AI decision (no silent automation);
- one accent used sparingly (the 5-cyberpunk / 3-glass Hybrid budget);
- mono (JetBrains) for every number, Vazirmatn/Inter for text; RTL-safe.

## OmniRoute (MIT) — the provider-router pattern
Adopted natively in `core/assistant/providers.py`:

- a new **gateway** provider: any local OpenAI-compatible endpoint (OmniRoute,
  LiteLLM, a local OpenRouter) via `gateway_base_url/_api_key/_model` in config;
- a **fallback ladder** (`gateway → ollama → openai → gemini → anthropic`): a dead
  or slow provider degrades to the next instead of silencing the assistant.
  The gateway needs no key when local, keeping the app local-first.

Determinism: the planners contain no randomness, so an AI plan is reproducible by
construction (same measurements → same plan) — the “deterministic seed” property
without a seed to leak.
