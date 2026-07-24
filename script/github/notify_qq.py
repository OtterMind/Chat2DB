#!/usr/bin/env python3
"""Send selected GitHub repository changes through the Chat2DB QQ relay."""

from __future__ import annotations

import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


TRANSIENT_HTTP_STATUSES = {429, 500, 502, 503, 504}
URL_PATTERN = re.compile(r"(?i)\b(?:https?://|www\.)\S+")
COLLECTED_EVENT_NAMES = {
    "issue_comment",
    "pull_request_review",
    "pull_request_review_comment",
}

ISSUE_ACTIONS = {
    "opened": "已打开",
    "edited": "已编辑",
    "deleted": "已删除",
    "transferred": "已转移",
    "pinned": "已置顶",
    "unpinned": "已取消置顶",
    "closed": "已关闭",
    "reopened": "已重新打开",
    "assigned": "已指派",
    "unassigned": "已取消指派",
    "labeled": "已添加标签",
    "unlabeled": "已移除标签",
    "locked": "已锁定讨论",
    "unlocked": "已解锁讨论",
    "milestoned": "已加入里程碑",
    "demilestoned": "已移出里程碑",
}

PULL_REQUEST_ACTIONS = {
    "assigned": "已指派",
    "unassigned": "已取消指派",
    "labeled": "已添加标签",
    "unlabeled": "已移除标签",
    "opened": "已打开",
    "edited": "已编辑",
    "closed": "已关闭",
    "reopened": "已重新打开",
    "synchronize": "提交已更新",
    "converted_to_draft": "已转为草稿",
    "locked": "已锁定讨论",
    "unlocked": "已解锁讨论",
    "enqueued": "已进入合并队列",
    "dequeued": "已退出合并队列",
    "milestoned": "已加入里程碑",
    "demilestoned": "已移出里程碑",
    "ready_for_review": "已可供评审",
    "review_requested": "已请求评审",
    "review_request_removed": "已取消评审请求",
    "auto_merge_enabled": "已启用自动合并",
    "auto_merge_disabled": "已禁用自动合并",
}

RELEASE_ACTIONS = {
    "published": "已发布",
    "unpublished": "已取消发布",
    "created": "已创建",
    "edited": "已编辑",
    "deleted": "已删除",
    "prereleased": "已设为预发布",
    "released": "已设为正式发布",
}

DISCUSSION_ACTIONS = {
    "created": "已创建",
    "edited": "已编辑",
    "deleted": "已删除",
    "transferred": "已转移",
    "pinned": "已置顶",
    "unpinned": "已取消置顶",
    "labeled": "已添加标签",
    "unlabeled": "已移除标签",
    "locked": "已锁定",
    "unlocked": "已解锁",
    "category_changed": "已更改分类",
    "answered": "已标记回答",
    "unanswered": "已取消回答",
}

DEPLOYMENT_STATES = {
    "error": "错误",
    "failure": "失败",
    "inactive": "已停用",
    "in_progress": "进行中",
    "queued": "排队中",
    "pending": "等待中",
    "success": "成功",
}

DISCUSSION_STATES = {
    "open": "开放",
    "closed": "已关闭",
}

COMMENT_ACTIONS = {
    "created": "已评论",
    "edited": "已编辑评论",
    "deleted": "已删除评论",
}

REVIEW_COMMENT_ACTIONS = {
    "created": "新增代码评审评论",
    "edited": "已编辑代码评审评论",
    "deleted": "已删除代码评审评论",
}

REVIEW_STATES = {
    "approved": "已批准",
    "changes_requested": "要求修改",
    "commented": "已评论",
    "dismissed": "已撤销",
    "pending": "待提交",
}


class ConfigurationError(RuntimeError):
    """Raised when required GitHub Actions configuration is invalid."""


@dataclass
class RelayAPIError(RuntimeError):
    status: int
    message: str

    def __str__(self) -> str:
        return f"QQ relay request failed (HTTP {self.status}): {self.message}"


def _clean_text(value: Any, max_length: int) -> str:
    text = str(value or "")
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", text)
    text = re.sub(r"(?i)\[CQ:", "[CQ :", text)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= max_length:
        return text
    return text[: max_length - 1].rstrip() + "…"


def _login(value: Any) -> str:
    if isinstance(value, Mapping):
        return _clean_text(value.get("login"), 80)
    return ""


def _join_message_lines(lines: list[str], max_length: int = 900) -> str:
    message = "\n".join(line for line in lines if line)
    if len(message) <= max_length:
        return message
    return message[: max_length - 1].rstrip() + "…"


def _remove_urls(value: str) -> str:
    return URL_PATTERN.sub("[链接已省略]", value)


def _safe_deployment_url(value: Any) -> str:
    url = _clean_text(value, 500)
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return ""
    if parsed.username or parsed.password:
        return ""
    return parsed._replace(query="", fragment="").geturl()


def _release_status(release: Mapping[str, Any]) -> str:
    if release.get("draft"):
        return "草稿"
    if release.get("prerelease"):
        return "预发布"
    return "正式发布"


def _discussion_status(discussion: Mapping[str, Any], action: str) -> str:
    if action == "deleted":
        return "已删除"
    if discussion.get("locked") or action == "locked":
        return "已锁定"
    if action == "unlocked":
        return "已解锁"
    if action == "answered" or discussion.get("answer_html_url"):
        return "已回答"
    if action == "unanswered":
        return "未回答"
    state = _clean_text(discussion.get("state"), 40).lower()
    return DISCUSSION_STATES.get(state, state or "开放")


def _comment_excerpt(comment: Mapping[str, Any], action: str) -> str:
    if action in {"deleted", "dismissed"}:
        return ""
    return _clean_text(comment.get("body"), 180)


def _review_action_label(action: str, state: str) -> str:
    if action == "dismissed":
        return "评审已撤销"
    if action == "edited":
        return "评审已编辑"
    if state == "approved":
        return "评审已批准"
    if state == "changes_requested":
        return "评审要求修改"
    if state == "commented":
        return "收到评审评论"
    return "已提交评审"


def _write_prepared_message(
    path: Path, event_name: str, repository: str, message: str
) -> None:
    if event_name not in COLLECTED_EVENT_NAMES:
        raise ConfigurationError(f"Cannot collect unsupported event: {event_name}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "version": 1,
                "event_name": event_name,
                "repository": repository,
                "message": message,
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )


def _read_prepared_message(path: Path, repository: str) -> tuple[str, str]:
    if not path.is_file() or path.stat().st_size > 4096:
        raise ConfigurationError("Prepared QQ notification is missing or too large")
    try:
        envelope = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ConfigurationError("Prepared QQ notification is not valid JSON") from error
    if not isinstance(envelope, Mapping) or envelope.get("version") != 1:
        raise ConfigurationError("Prepared QQ notification has an invalid format")
    if envelope.get("repository") != repository:
        raise ConfigurationError("Prepared QQ notification repository does not match")
    event_name = str(envelope.get("event_name") or "")
    if event_name not in COLLECTED_EVENT_NAMES:
        raise ConfigurationError("Prepared QQ notification event is not allowed")
    message = envelope.get("message")
    if not isinstance(message, str) or not message or len(message) > 900:
        raise ConfigurationError("Prepared QQ notification message is invalid")
    if re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", message):
        raise ConfigurationError("Prepared QQ notification contains control characters")
    if re.search(r"(?i)\[CQ:", message):
        raise ConfigurationError("Prepared QQ notification contains a OneBot CQ code")
    return event_name, message


def _event_detail(event_name: str, action: str, payload: Mapping[str, Any]) -> str:
    if action in {"labeled", "unlabeled"}:
        label = payload.get("label") or {}
        return f"标签：{_clean_text(label.get('name'), 80)}"
    if action in {"assigned", "unassigned"}:
        return f"处理人：{_login(payload.get('assignee'))}"
    if action in {"milestoned", "demilestoned"}:
        milestone = payload.get("milestone") or {}
        return f"里程碑：{_clean_text(milestone.get('title'), 100)}"
    if action in {"review_requested", "review_request_removed"}:
        reviewer = _login(payload.get("requested_reviewer"))
        team = payload.get("requested_team") or {}
        target = reviewer or _clean_text(team.get("name"), 80)
        return f"评审人：{target}"
    if event_name == "pull_request_target" and action == "synchronize":
        before = _clean_text(payload.get("before"), 12)
        after = _clean_text(payload.get("after"), 12)
        if before and after:
            return f"提交：{before[:7]} -> {after[:7]}"
    return ""


def build_notification(
    event_name: str,
    payload: Mapping[str, Any],
    repository: str,
    actor: str,
    run_url: str,
    *,
    include_url: bool,
) -> str:
    """Build a bounded plain-text notification from a GitHub event payload."""
    action = _clean_text(payload.get("action"), 60)
    repository_name = _clean_text(repository.rsplit("/", 1)[-1], 80) or "GitHub"
    prefix = f"[{repository_name} GitHub]"

    if event_name == "workflow_dispatch":
        inputs = payload.get("inputs") or {}
        lines = [
            f"{prefix} QQ 群通知测试",
            f"内容：{_clean_text(inputs.get('message'), 240)}",
            f"操作者：{_clean_text(actor, 80)}",
        ]
        if include_url and run_url:
            lines.append(f"运行：{run_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    sender = _login(payload.get("sender")) or _clean_text(actor, 80)

    if event_name == "issue_comment":
        item = payload.get("issue") or {}
        comment = payload.get("comment") or {}
        item_name = "PR" if "pull_request" in item else "Issue"
        number = item.get("number") or "?"
        action_label = COMMENT_ACTIONS.get(action, f"评论状态已变更（{action}）")
        commenter = _login(comment.get("user")) or sender
        lines = [
            f"{prefix} {item_name} #{number} {action_label}",
            f"标题：{_clean_text(item.get('title'), 220)}",
            f"评论者：{commenter}",
        ]
        excerpt = _comment_excerpt(comment, action)
        if excerpt:
            lines.append(f"摘要：{excerpt}")
        html_url = _clean_text(
            comment.get("html_url") if action != "deleted" else item.get("html_url"),
            500,
        )
        if include_url and html_url:
            lines.append(f"链接：{html_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name == "pull_request_review":
        item = payload.get("pull_request") or {}
        review = payload.get("review") or {}
        number = item.get("number") or "?"
        state = _clean_text(review.get("state"), 40).lower()
        reviewer = _login(review.get("user")) or sender
        lines = [
            f"{prefix} PR #{number} {_review_action_label(action, state)}",
            f"标题：{_clean_text(item.get('title'), 220)}",
            f"结果：{REVIEW_STATES.get(state, state or '未知')}",
            f"评审人：{reviewer}",
        ]
        excerpt = _comment_excerpt(review, action)
        if excerpt:
            lines.append(f"摘要：{excerpt}")
        html_url = _clean_text(review.get("html_url") or item.get("html_url"), 500)
        if include_url and html_url:
            lines.append(f"链接：{html_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name == "pull_request_review_comment":
        item = payload.get("pull_request") or {}
        comment = payload.get("comment") or {}
        number = item.get("number") or "?"
        action_label = REVIEW_COMMENT_ACTIONS.get(
            action, f"代码评审评论状态已变更（{action}）"
        )
        reviewer = _login(comment.get("user")) or sender
        path = _clean_text(comment.get("path"), 180)
        line_number = comment.get("line") or comment.get("original_line")
        location = f"{path}:{line_number}" if path and line_number else path
        lines = [
            f"{prefix} PR #{number} {action_label}",
            f"标题：{_clean_text(item.get('title'), 220)}",
            f"评审人：{reviewer}",
        ]
        if location:
            lines.append(f"位置：{location}")
        excerpt = _comment_excerpt(comment, action)
        if excerpt:
            lines.append(f"摘要：{excerpt}")
        html_url = _clean_text(
            comment.get("html_url") if action != "deleted" else item.get("html_url"),
            500,
        )
        if include_url and html_url:
            lines.append(f"链接：{html_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name == "release":
        release = payload.get("release") or {}
        tag = _clean_text(release.get("tag_name"), 120) or "未命名"
        name = _clean_text(release.get("name"), 220) or tag
        action_label = RELEASE_ACTIONS.get(action, f"状态已变更（{action}）")
        lines = [
            f"{prefix} Release {tag} {action_label}",
            f"名称：{name}",
            f"状态：{_release_status(release)}",
            f"操作者：{sender}",
        ]
        html_url = _clean_text(release.get("html_url"), 500)
        if include_url and html_url:
            lines.append(f"链接：{html_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name in {"deployment", "deployment_status"}:
        deployment = payload.get("deployment") or {}
        environment = _clean_text(deployment.get("environment"), 120) or "未指定"
        ref = _clean_text(deployment.get("ref"), 120)
        if not ref:
            ref = _clean_text(deployment.get("sha"), 12)[:7] or "未指定"

        if event_name == "deployment":
            action_label = "已创建"
            status_label = "已创建"
            status = {}
        else:
            action_label = "状态已更新"
            status = payload.get("deployment_status") or {}
            state = _clean_text(status.get("state"), 40).lower()
            status_label = DEPLOYMENT_STATES.get(state, state or "未知")

        lines = [
            f"{prefix} Deployment {action_label}",
            f"环境：{environment}",
            f"Ref：{ref}",
            f"状态：{status_label}",
            f"操作者：{sender}",
        ]
        environment_url = _safe_deployment_url(status.get("environment_url"))
        log_url = _safe_deployment_url(status.get("log_url"))
        if include_url and environment_url:
            lines.append(f"环境链接：{environment_url}")
        elif include_url and log_url:
            lines.append(f"日志：{log_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name == "discussion":
        discussion = payload.get("discussion") or {}
        number = discussion.get("number") or "?"
        title = _clean_text(discussion.get("title"), 220)
        category = discussion.get("category") or {}
        category_name = _clean_text(category.get("name"), 100) or "未分类"
        action_label = DISCUSSION_ACTIONS.get(action, f"状态已变更（{action}）")
        lines = [
            f"{prefix} Discussion #{number} {action_label}",
            f"标题：{title}",
            f"分类：{category_name}",
            f"状态：{_discussion_status(discussion, action)}",
            f"操作者：{sender}",
        ]
        detail = _event_detail(event_name, action, payload)
        if detail and not detail.endswith("："):
            lines.append(detail)
        html_url = _clean_text(discussion.get("html_url"), 500)
        if include_url and html_url:
            lines.append(f"链接：{html_url}")
        message = _join_message_lines(lines)
        return message if include_url else _remove_urls(message)

    if event_name == "issues":
        item = payload.get("issue") or {}
        item_name = "Issue"
        action_label = ISSUE_ACTIONS.get(action, f"状态已变更（{action}）")
    elif event_name == "pull_request_target":
        item = payload.get("pull_request") or {}
        item_name = "PR"
        if action == "closed" and item.get("merged"):
            action_label = "已合并"
        else:
            action_label = PULL_REQUEST_ACTIONS.get(action, f"状态已变更（{action}）")
    else:
        raise ValueError(f"Unsupported GitHub event: {event_name}")

    number = item.get("number") or payload.get("number") or "?"
    title = _clean_text(item.get("title"), 220)
    detail = _event_detail(event_name, action, payload)
    html_url = _clean_text(item.get("html_url"), 500)

    lines = [
        f"{prefix} {item_name} #{number} {action_label}",
        f"标题：{title}",
        f"操作者：{sender}",
    ]
    if detail and not detail.endswith("："):
        lines.append(detail)
    if include_url and html_url:
        lines.append(f"链接：{html_url}")

    message = _join_message_lines(lines)
    return message if include_url else _remove_urls(message)


def _decode_relay_error(status: int, body: bytes) -> RelayAPIError:
    message = body.decode("utf-8", errors="replace")[:500]
    try:
        data = json.loads(message)
        if isinstance(data, Mapping):
            message = str(data.get("error") or data.get("message") or message)
    except (ValueError, TypeError, json.JSONDecodeError):
        pass
    return RelayAPIError(status=status, message=_clean_text(message, 300))


def _post_json(url: str, payload: Mapping[str, Any], headers: Mapping[str, str]) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request_headers = {
        "Content-Type": "application/json",
        "User-Agent": "Chat2DB-GitHub-Notifier/1.0",
        **headers,
    }

    for attempt in range(3):
        request = Request(url, data=body, headers=request_headers, method="POST")
        try:
            with urlopen(request, timeout=15) as response:
                response_body = response.read()
                decoded = json.loads(response_body.decode("utf-8")) if response_body else {}
                if not isinstance(decoded, dict):
                    raise RuntimeError("QQ relay response was not a JSON object")
                return decoded
        except HTTPError as error:
            relay_error = _decode_relay_error(error.code, error.read())
            if error.code not in TRANSIENT_HTTP_STATUSES or attempt == 2:
                raise relay_error from error
        except URLError as error:
            if attempt == 2:
                raise RuntimeError(f"QQ relay network request failed: {error.reason}") from error
        time.sleep(2**attempt)

    raise AssertionError("unreachable")


def _validated_relay_url(value: str) -> str:
    parsed = urlparse(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.fragment
    ):
        raise ConfigurationError("QQ_RELAY_URL must be an HTTPS URL without credentials or a fragment")
    return value


def send_relay_message(
    relay_url: str,
    relay_token: str,
    repository: str,
    delivery_id: str,
    content: str,
) -> dict[str, Any]:
    return _post_json(
        _validated_relay_url(relay_url),
        {
            "repository": repository,
            "delivery_id": delivery_id,
            "message": content,
        },
        {"Authorization": f"Bearer {relay_token}"},
    )


def _required_environment() -> tuple[str, str]:
    names = ("QQ_RELAY_URL", "QQ_RELAY_TOKEN")
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise ConfigurationError("Missing required GitHub Actions secrets: " + ", ".join(missing))
    return tuple(os.environ[name] for name in names)  # type: ignore[return-value]


def _is_true(value: str | None) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def main() -> int:
    event_path = Path(os.environ.get("GITHUB_EVENT_PATH", ""))
    if not event_path.is_file():
        raise ConfigurationError("GITHUB_EVENT_PATH does not point to an event payload")

    with event_path.open(encoding="utf-8") as event_file:
        payload = json.load(event_file)

    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    repository = os.environ.get("GITHUB_REPOSITORY", "OtterMind/Chat2DB")
    actor = os.environ.get("GITHUB_ACTOR", "unknown")
    server_url = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    run_url = f"{server_url}/{repository}/actions/runs/{run_id}" if run_id else ""
    include_url = _is_true(os.environ.get("QQ_INCLUDE_URL", "true"))

    prepared_path = os.environ.get("QQ_PREPARED_MESSAGE_PATH")
    if prepared_path:
        event_name, message = _read_prepared_message(Path(prepared_path), repository)
        action = "prepared"
    else:
        message = build_notification(
            event_name, payload, repository, actor, run_url, include_url=include_url
        )
        action = _clean_text(payload.get("action") or "manual", 60)

        output_path = os.environ.get("QQ_MESSAGE_OUTPUT_PATH")
        if output_path:
            _write_prepared_message(Path(output_path), event_name, repository, message)
            print(f"QQ notification collected: event={event_name}, action={action}")
            return 0

    if _is_true(os.environ.get("QQ_DRY_RUN")):
        print(f"QQ notification dry run passed: event={event_name}, action={action}")
        return 0

    delivery_id = _clean_text(os.environ.get("QQ_DELIVERY_ID") or run_id, 160)
    if not delivery_id:
        raise ConfigurationError("A delivery ID is required for relay deduplication")
    relay_url, relay_token = _required_environment()
    response = send_relay_message(
        relay_url, relay_token, repository, delivery_id, message
    )
    message_id = _clean_text(response.get("message_id"), 160)
    duplicate = bool(response.get("duplicate"))
    url_removed = bool(response.get("url_removed"))
    suffixes = []
    if duplicate:
        suffixes.append("duplicate suppressed")
    if url_removed:
        suffixes.append("URL removed by fallback")
    suffix = "; " + "; ".join(suffixes) if suffixes else ""
    print(f"QQ notification sent: message_id={message_id or 'unknown'}{suffix}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ConfigurationError, RelayAPIError, RuntimeError, ValueError) as error:
        print(f"QQ notification failed: {error}", file=sys.stderr)
        sys.exit(1)
