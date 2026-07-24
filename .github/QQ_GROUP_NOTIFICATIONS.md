# QQ Group Notifications

The `QQ group notifications` workflow sends Issue, pull-request, Release,
Deployment, and Discussion state changes to QQ group `1080856850` through a
dedicated NapCat/OneBot account. GitHub Actions can reach the private Mac Studio
deployment only through an authenticated Cloudflare Tunnel endpoint.

```text
GitHub Actions -> HTTPS relay -> OneBot HTTP -> NapCat -> QQ group 1080856850
```

The QQ account used by NapCat must be a dedicated secondary account. NapCat is
not an official QQ integration and may be affected by QQ device verification,
protocol changes, or account risk controls.

## Security boundaries

- The relay fixes the destination group server-side. GitHub cannot select a
  different QQ group.
- The public endpoint accepts only `POST /v1/qq/github` with a strong Bearer
  token, the exact repository name, a bounded message, and a delivery ID.
- Successful delivery IDs are deduplicated for 24 hours and accepted sends are
  rate-limited to 30 per minute.
- NapCat WebUI binds only to host loopback. OneBot HTTP and WebSocket ports are
  not published on the host or Internet.
- Only Issue, pull-request, and Discussion titles plus selected Release and
  Deployment metadata are sent. Bodies, comments, source code, Deployment
  payloads, credentials, and other event payload fields are excluded.
- `pull_request_target` checks out the notifier from the trusted default branch
  and never executes pull-request code. A manually dispatched test may use the
  explicitly selected maintainer branch.

## Mac Studio deployment

The deployment bundle is under `script/github/qq_relay/deploy` and pins NapCat
to `v4.18.13`. Docker Desktop, OrbStack, or another Docker-compatible runtime is
required.

```bash
cd script/github/qq_relay/deploy
python3 configure.py
docker compose up -d --build napcat relay
```

`configure.py` creates strong local tokens, a fixed-group relay configuration,
and the NapCat OneBot HTTP configuration. Generated secrets and QQ session data
are ignored by Git and must not be copied into Issues, pull requests, or logs.

Access the NapCat WebUI through an SSH tunnel rather than a LAN or public
listener:

```bash
ssh -L 6099:127.0.0.1:6099 chat2db@mac-studio-address
```

Then open `http://127.0.0.1:6099/webui`, sign in with the generated WebUI token,
and complete the QQ QR-code/device verification. Confirm that the dedicated QQ
account belongs to group `1080856850` before sending a test.

## Cloudflare Tunnel

Create a remotely managed Cloudflare Tunnel with one public hostname routed to
`http://relay:8080`. Put its tunnel token in the deployment `.env`, then start
the connector:

```bash
docker compose --profile tunnel up -d cloudflared
```

No router port forwarding is required. The public hostname should not route to
NapCat port `3000`, WebSocket port `3001`, or WebUI port `6099`.

When a host already has a Compose-managed Cloudflare connector, attach the
relay to its Docker network instead of starting a second connector:

```bash
docker compose -f compose.yml -f compose.existing-tunnel.yml up -d --build napcat relay
```

Add a hostname-and-path ingress rule before that hostname's catch-all rule and
route only `^/v1/qq/github$` to `http://chat2db-qq-relay:8080`. This preserves
all other traffic on the existing hostname.

## Repository configuration

Create these Actions secrets under **Settings > Secrets and variables >
Actions**:

| Secret | Value |
| --- | --- |
| `QQ_RELAY_URL` | `https://<tunnel-hostname>/v1/qq/github` |
| `QQ_RELAY_TOKEN` | The generated `RELAY_TOKEN` from the Mac Studio `.env` |

The optional Actions variable `QQ_NOTIFICATION_INCLUDE_URL` defaults to
`true`. Set it to `false` to omit GitHub URLs from notifications.
When OneBot explicitly rejects a message containing a URL, the relay retries
once with URLs replaced by `[链接已省略]`.

The old `QQ_BOT_APP_ID`, `QQ_BOT_CLIENT_SECRET`, and `QQ_GROUP_OPENID` secrets
are not used by this implementation and may be removed after the relay path is
verified.

## Notification coverage

The workflow sends these repository events:

- Issue and pull-request lifecycle and state changes listed in the workflow.
- Release `published`, `unpublished`, `created`, `edited`, `deleted`,
  `prereleased`, and `released` events. Messages include the tag, release name,
  release state, actor, and release URL.
- Deployment creation and every Deployment status update. Messages include the
  environment, ref, mapped status, actor, and an environment or log URL when
  GitHub provides one. URL query strings and fragments are removed.
- Discussion `created`, `edited`, `deleted`, `transferred`, `pinned`,
  `unpinned`, `labeled`, `unlabeled`, `locked`, `unlocked`, `category_changed`,
  `answered`, and `unanswered` events. Messages include the number, title,
  category, current state, actor, and Discussion URL.

Discussion comments are intentionally not subscribed to and do not generate QQ
messages. GitHub does not run the `created`, `edited`, or `deleted` Release
activity types for draft releases; `published` is the reliable event for both
stable releases and prereleases when they become public.

## Verification

Run automated checks:

```bash
python3 script/github/test_notify_qq.py
python3 script/github/qq_relay/test_relay_server.py
actionlint .github/workflows/ci.yml .github/workflows/qq-group-notifications.yml
```

Verify the live path in this order:

1. Confirm `docker compose ps` reports a healthy relay and running NapCat.
2. Call OneBot `get_login_info` and `get_group_list` from inside the Docker
   network; confirm the QQ account and group `1080856850`.
3. Dispatch the workflow with `dry_run` enabled.
4. Dispatch it again with `dry_run` disabled and confirm one QQ message.
5. Open and close a test Issue, then open and close a test pull request. Confirm
   action, number, title, actor, URL, and merged/closed distinction.
6. Publish or edit a test Release, create a test Deployment status, and change a
   test Discussion state. Confirm their selected metadata and links, and confirm
   that bodies, comments, Deployment payloads, and URL query strings are absent.

The relay intentionally returns a generic `QQ delivery failed` response when
OneBot is offline or rejects a message, so internal details are not exposed on
the public endpoint. Inspect local relay and NapCat container logs for diagnosis.

To stop notifications immediately, disable the GitHub workflow or stop the
Cloudflare connector. Rotate `RELAY_TOKEN`, `ONEBOT_TOKEN`, and the WebUI token
by replacing the local values and updating the corresponding consumer.
