# Pi V2 platform contract

Pi presentation uses `chat2db-community-client/src/service/pi`. Only its
composition root (`index.ts`) selects the Web or desktop adapter. V1 services
and the mixed-version history list retain their existing compatibility routes.

```text
Pi UI → typed client → HTTP / JCEF transport → PiOperationRegistry
                                              ↓
                                  existing controllers and domain services
Pi UI → host adapter → platform directory, attachment and output operations
```

## Operations and transport

- `contract.ts` defines operation names, named payloads and result types.
- Both transports send the same versioned envelope to `/api/v3/ai/pi/invoke`.
  JCEF uses the existing `commandLineRequest` and `WebJcefServerBridge`.
  It bypasses MVC route/argument discovery for this one entry point.
- `PiOperationRegistry` is the single backend allowlist, payload validator and
  response serializer. It invokes existing Spring controller beans so domain
  authorization, ownership and runtime behavior remain shared with REST.
- Responses retain `success`, `data`, `errorCode` and `errorMessage`, and echo
  `protocolVersion` and `requestId`. The client validates the envelope and owns
  business errors. Async errors retain the request locale.
- The client owns timeout and AbortSignal cleanup. Aborting an observation
  drops late replies; it does not roll back an accepted mutation or stop a run.
  Stopping generation requires `runs.cancel` explicitly.
- `events.list` pages forwards with `afterSequence` while a run streams, and
  backwards with `beforeSequence` when the reader scrolls into older history.
  Event files are named by sequence, so a backwards page is a range read of the
  requested window instead of a scan of the whole session.

## Host operations

- Web directory selection prompts for a path on the Pi server. Desktop opens
  the native directory chooser. Both return a path or `null` for cancellation;
  the caller persists a chosen path separately with `workspace.set`.
- Attachments use the browser file picker and existing multipart upload in
  Web; desktop uses its native picker and `attachments.parseLocal`.
- Output read/search use the common client. Web downloads through the existing
  authenticated streaming endpoint after checking availability; desktop uses
  `outputs.save`, then reveals the saved file. Large files stay off the JSON
  transport.
- `workspace.selectDirectory`, `attachments.parseLocal` and `outputs.save`
  require the actual desktop bridge context. Sending them over HTTP, including
  forged headers, cannot enable native file access.

## Extending Pi

1. Add a typed operation in `contract.ts` and bind it in `client.ts`.
2. Register its payload and shared handler once in `PiOperationRegistry`.
   Use `registerDesktop` only for a native capability owned by the host adapter.
3. Add its fixture to `PiTransportContractTest`; it runs every operation through
   MockMvc and the actual JCEF bridge and checks parity and native-only denial.
4. Run `yarn test:agent-chat`, lint and the Community frontend build, plus the
   backend web module tests with tests enabled. `test:pi-adapters` also runs in
   the frontend prebuild gate and rejects platform access from Pi-only UI.

Keep platform conditionals, transport paths and raw bridge calls out of Pi
business components. REST compatibility endpoints remain available to existing
callers. Native GUI testing is separate from bridge contract tests.
