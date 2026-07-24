#!/usr/bin/env python3

import io
import json
import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

import notify_qq


class NotificationFormattingTest(unittest.TestCase):
    def test_issue_opened(self):
        payload = {
            "action": "opened",
            "issue": {
                "number": 42,
                "title": "Cannot connect to PostgreSQL",
                "html_url": "https://github.com/OtterMind/Chat2DB/issues/42",
            },
            "sender": {"login": "alice"},
        }

        message = notify_qq.build_notification(
            "issues", payload, "OtterMind/Chat2DB", "alice", "", include_url=True
        )

        self.assertIn("Issue #42 已打开", message)
        self.assertIn("标题：Cannot connect to PostgreSQL", message)
        self.assertIn("操作者：alice", message)
        self.assertIn("/issues/42", message)

    def test_issue_label_change_includes_label(self):
        payload = {
            "action": "labeled",
            "issue": {"number": 7, "title": "Export fails", "html_url": ""},
            "label": {"name": "bug"},
            "sender": {"login": "maintainer"},
        }

        message = notify_qq.build_notification(
            "issues", payload, "OtterMind/Chat2DB", "maintainer", "", include_url=False
        )

        self.assertIn("Issue #7 已添加标签", message)
        self.assertIn("标签：bug", message)

    def test_merged_pull_request_is_distinct_from_closed(self):
        payload = {
            "action": "closed",
            "pull_request": {
                "number": 88,
                "title": "Fix SQL completion",
                "merged": True,
                "html_url": "https://github.com/OtterMind/Chat2DB/pull/88",
            },
            "sender": {"login": "bob"},
        }

        message = notify_qq.build_notification(
            "pull_request_target", payload, "OtterMind/Chat2DB", "bob", "", include_url=True
        )

        self.assertIn("PR #88 已合并", message)
        self.assertNotIn("已关闭", message)

    def test_pull_request_synchronize_includes_commit_range(self):
        payload = {
            "action": "synchronize",
            "before": "1111111abcdef",
            "after": "2222222abcdef",
            "pull_request": {"number": 9, "title": "Update", "html_url": ""},
            "sender": {"login": "carol"},
        }

        message = notify_qq.build_notification(
            "pull_request_target", payload, "OtterMind/Chat2DB", "carol", "", include_url=False
        )

        self.assertIn("提交已更新", message)
        self.assertIn("提交：1111111 -> 2222222", message)

    def test_release_published_includes_metadata_but_not_body(self):
        payload = {
            "action": "published",
            "release": {
                "tag_name": "v5.4.0",
                "name": "Chat2DB 5.4.0",
                "draft": False,
                "prerelease": False,
                "body": "release body must stay private",
                "html_url": "https://github.com/OtterMind/Chat2DB/releases/tag/v5.4.0",
            },
            "sender": {"login": "release-manager"},
        }

        message = notify_qq.build_notification(
            "release",
            payload,
            "OtterMind/Chat2DB",
            "release-manager",
            "",
            include_url=True,
        )

        self.assertIn("Release v5.4.0 已发布", message)
        self.assertIn("名称：Chat2DB 5.4.0", message)
        self.assertIn("状态：正式发布", message)
        self.assertIn("操作者：release-manager", message)
        self.assertIn("/releases/tag/v5.4.0", message)
        self.assertNotIn("release body must stay private", message)

    def test_deployment_created_includes_environment_and_ref(self):
        payload = {
            "action": "created",
            "deployment": {
                "environment": "staging",
                "ref": "main",
                "payload": {"secret": "must-not-leak"},
            },
            "sender": {"login": "deploy-bot"},
        }

        message = notify_qq.build_notification(
            "deployment",
            payload,
            "OtterMind/Chat2DB",
            "deploy-bot",
            "",
            include_url=True,
        )

        self.assertIn("Deployment 已创建", message)
        self.assertIn("环境：staging", message)
        self.assertIn("Ref：main", message)
        self.assertIn("状态：已创建", message)
        self.assertNotIn("must-not-leak", message)

    def test_deployment_status_uses_environment_url_without_query_credentials(self):
        payload = {
            "action": "created",
            "deployment": {"environment": "production", "ref": "v5.4.0"},
            "deployment_status": {
                "state": "success",
                "environment_url": "https://chat2db.example.com/app?token=must-not-leak",
                "log_url": "https://logs.example.com/deploy/42?key=must-not-leak",
            },
            "sender": {"login": "github-actions"},
        }

        message = notify_qq.build_notification(
            "deployment_status",
            payload,
            "OtterMind/Chat2DB",
            "github-actions",
            "",
            include_url=True,
        )

        self.assertIn("Deployment 状态已更新", message)
        self.assertIn("环境：production", message)
        self.assertIn("Ref：v5.4.0", message)
        self.assertIn("状态：成功", message)
        self.assertIn("环境链接：https://chat2db.example.com/app", message)
        self.assertNotIn("must-not-leak", message)
        self.assertNotIn("logs.example.com", message)

    def test_discussion_includes_category_and_status_but_not_body(self):
        payload = {
            "action": "created",
            "discussion": {
                "number": 12,
                "title": "How should migrations work?",
                "body": "discussion body must stay private",
                "state": "open",
                "locked": False,
                "category": {"name": "Q&A"},
                "html_url": "https://github.com/OtterMind/Chat2DB/discussions/12",
            },
            "sender": {"login": "community-member"},
        }

        message = notify_qq.build_notification(
            "discussion",
            payload,
            "OtterMind/Chat2DB",
            "community-member",
            "",
            include_url=True,
        )

        self.assertIn("Discussion #12 已创建", message)
        self.assertIn("标题：How should migrations work?", message)
        self.assertIn("分类：Q&A", message)
        self.assertIn("状态：开放", message)
        self.assertIn("操作者：community-member", message)
        self.assertIn("/discussions/12", message)
        self.assertNotIn("discussion body must stay private", message)

    def test_untrusted_title_is_bounded_and_control_characters_are_removed(self):
        payload = {
            "action": "edited",
            "issue": {"number": 1, "title": "bad\x00" + "x" * 400, "html_url": ""},
            "sender": {"login": "actor"},
        }

        message = notify_qq.build_notification(
            "issues", payload, "OtterMind/Chat2DB", "actor", "", include_url=False
        )

        self.assertNotIn("\x00", message)
        self.assertLessEqual(len(message), 900)
        self.assertIn("…", message)

    def test_untrusted_title_cannot_create_extra_message_lines(self):
        payload = {
            "action": "opened",
            "issue": {
                "number": 2,
                "title": "first line\nsecond line\r\nthird line",
                "html_url": "",
            },
            "sender": {"login": "actor"},
        }

        message = notify_qq.build_notification(
            "issues", payload, "OtterMind/Chat2DB", "actor", "", include_url=False
        )

        self.assertEqual(3, len(message.splitlines()))
        self.assertIn("标题：first line second line third line", message)

    def test_manual_dry_run_message(self):
        payload = {"inputs": {"message": "hello QQ"}}

        message = notify_qq.build_notification(
            "workflow_dispatch",
            payload,
            "OtterMind/Chat2DB",
            "maintainer",
            "https://github.com/OtterMind/Chat2DB/actions/runs/123",
            include_url=True,
        )

        self.assertIn("QQ 群通知测试", message)
        self.assertIn("内容：hello QQ", message)
        self.assertIn("actions/runs/123", message)

    def test_url_can_be_omitted(self):
        payload = {
            "action": "opened",
            "issue": {
                "number": 3,
                "title": "Details at https://example.invalid/path",
                "html_url": "https://github.com/OtterMind/Chat2DB/issues/3",
            },
            "sender": {"login": "actor"},
        }

        message = notify_qq.build_notification(
            "issues", payload, "OtterMind/Chat2DB", "actor", "", include_url=False
        )

        self.assertNotIn("https://", message)
        self.assertIn("[链接已省略]", message)


class RelayClientTest(unittest.TestCase):
    @patch("notify_qq.urlopen")
    def test_http_client_sets_explicit_user_agent(self, urlopen_mock):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"{}"
        urlopen_mock.return_value = response

        notify_qq._post_json("https://qq-relay.example.com/v1/qq/github", {}, {})

        request = urlopen_mock.call_args.args[0]
        self.assertEqual(
            "Chat2DB-GitHub-Notifier/1.0", request.get_header("User-agent")
        )

    @patch("notify_qq._post_json")
    def test_relay_request_uses_bearer_token_and_delivery_id(self, post_json):
        post_json.return_value = {"ok": True, "message_id": "42"}

        response = notify_qq.send_relay_message(
            "https://qq-relay.example.com/v1/qq/github",
            "relay-secret",
            "OtterMind/Chat2DB",
            "123456",
            "hello",
        )

        self.assertEqual("42", response["message_id"])
        post_json.assert_called_once_with(
            "https://qq-relay.example.com/v1/qq/github",
            {
                "repository": "OtterMind/Chat2DB",
                "delivery_id": "123456",
                "message": "hello",
            },
            {"Authorization": "Bearer relay-secret"},
        )

    def test_relay_url_must_use_https(self):
        with self.assertRaises(notify_qq.ConfigurationError):
            notify_qq.send_relay_message(
                "http://relay.internal/v1/qq/github",
                "token",
                "OtterMind/Chat2DB",
                "123",
                "hello",
            )

    def test_api_error_is_sanitized(self):
        error = HTTPError("https://example.invalid", 401, "Unauthorized", {}, io.BytesIO())
        body = json.dumps({"error": "invalid relay token"}).encode()

        decoded = notify_qq._decode_relay_error(error.code, body)

        self.assertEqual(401, decoded.status)
        self.assertIn("invalid relay token", str(decoded))

    def test_manual_dry_run_does_not_require_secrets(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as event_file:
            json.dump({"inputs": {"message": "dry run"}}, event_file)
            event_file.flush()
            environment = {
                "GITHUB_EVENT_PATH": event_file.name,
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REPOSITORY": "OtterMind/Chat2DB",
                "GITHUB_ACTOR": "maintainer",
                "GITHUB_RUN_ID": "123",
                "QQ_DRY_RUN": "true",
            }

            with patch.dict(os.environ, environment, clear=True):
                self.assertEqual(0, notify_qq.main())


if __name__ == "__main__":
    unittest.main()
