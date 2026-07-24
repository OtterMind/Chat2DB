#!/usr/bin/env python3

import io
import json
import os
import tempfile
import unittest
from pathlib import Path
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

    def test_issue_comment_includes_bounded_excerpt(self):
        payload = {
            "action": "created",
            "issue": {
                "number": 21,
                "title": "Cannot save datasource",
                "html_url": "https://github.com/OtterMind/Chat2DB/issues/21",
            },
            "comment": {
                "body": "[CQ:at,qq=all] first line\nsecond line "
                + "x" * 220
                + "TAIL_SECRET",
                "html_url": "https://github.com/OtterMind/Chat2DB/issues/21#issuecomment-1",
                "user": {"login": "reporter"},
            },
            "sender": {"login": "reporter"},
        }

        message = notify_qq.build_notification(
            "issue_comment",
            payload,
            "OtterMind/Chat2DB",
            "reporter",
            "",
            include_url=True,
        )

        self.assertIn("Issue #21 已评论", message)
        self.assertIn("标题：Cannot save datasource", message)
        self.assertIn("评论者：reporter", message)
        self.assertIn("摘要：[CQ :at,qq=all] first line second line", message)
        self.assertNotIn("[CQ:", message)
        self.assertIn("#issuecomment-1", message)
        self.assertNotIn("TAIL_SECRET", message)

    def test_deleted_pull_request_comment_does_not_echo_body(self):
        payload = {
            "action": "deleted",
            "issue": {
                "number": 22,
                "title": "Fix metadata loading",
                "html_url": "https://github.com/OtterMind/Chat2DB/pull/22",
                "pull_request": {"url": "https://api.github.com/pulls/22"},
            },
            "comment": {
                "body": "deleted content must not be sent",
                "html_url": "https://github.com/OtterMind/Chat2DB/pull/22#issuecomment-2",
                "user": {"login": "reviewer"},
            },
            "sender": {"login": "reviewer"},
        }

        message = notify_qq.build_notification(
            "issue_comment",
            payload,
            "OtterMind/Chat2DB",
            "reviewer",
            "",
            include_url=True,
        )

        self.assertIn("PR #22 已删除评论", message)
        self.assertIn("链接：https://github.com/OtterMind/Chat2DB/pull/22", message)
        self.assertNotIn("摘要：", message)
        self.assertNotIn("deleted content", message)

    def test_submitted_pull_request_review_states_are_distinct(self):
        expectations = {
            "approved": ("评审已批准", "结果：已批准"),
            "changes_requested": ("评审要求修改", "结果：要求修改"),
            "commented": ("收到评审评论", "结果：已评论"),
        }

        for state, expected in expectations.items():
            with self.subTest(state=state):
                payload = {
                    "action": "submitted",
                    "pull_request": {
                        "number": 23,
                        "title": "Improve SQL editor",
                        "html_url": "https://github.com/OtterMind/Chat2DB/pull/23",
                    },
                    "review": {
                        "state": state,
                        "body": "Review summary",
                        "html_url": "https://github.com/OtterMind/Chat2DB/pull/23#pullrequestreview-3",
                        "user": {"login": "maintainer"},
                    },
                    "sender": {"login": "maintainer"},
                }

                message = notify_qq.build_notification(
                    "pull_request_review",
                    payload,
                    "OtterMind/Chat2DB",
                    "maintainer",
                    "",
                    include_url=True,
                )

                self.assertIn(f"PR #23 {expected[0]}", message)
                self.assertIn(expected[1], message)
                self.assertIn("评审人：maintainer", message)
                self.assertIn("摘要：Review summary", message)

    def test_dismissed_review_does_not_echo_body(self):
        payload = {
            "action": "dismissed",
            "pull_request": {"number": 24, "title": "Update parser", "html_url": ""},
            "review": {
                "state": "dismissed",
                "body": "obsolete review must not be sent",
                "user": {"login": "maintainer"},
            },
            "sender": {"login": "maintainer"},
        }

        message = notify_qq.build_notification(
            "pull_request_review",
            payload,
            "OtterMind/Chat2DB",
            "maintainer",
            "",
            include_url=False,
        )

        self.assertIn("PR #24 评审已撤销", message)
        self.assertIn("结果：已撤销", message)
        self.assertNotIn("摘要：", message)
        self.assertNotIn("obsolete review", message)

    def test_line_review_comment_includes_location_but_not_diff(self):
        payload = {
            "action": "created",
            "pull_request": {
                "number": 25,
                "title": "Fix transaction handling",
                "html_url": "https://github.com/OtterMind/Chat2DB/pull/25",
            },
            "comment": {
                "body": "Please cover the rollback path.",
                "diff_hunk": "@@ secret source code must not be sent @@",
                "path": "script/github/notify_qq.py",
                "line": 120,
                "html_url": "https://github.com/OtterMind/Chat2DB/pull/25#discussion_r4",
                "user": {"login": "reviewer"},
            },
            "sender": {"login": "reviewer"},
        }

        message = notify_qq.build_notification(
            "pull_request_review_comment",
            payload,
            "OtterMind/Chat2DB",
            "reviewer",
            "",
            include_url=True,
        )

        self.assertIn("PR #25 新增代码评审评论", message)
        self.assertIn("位置：script/github/notify_qq.py:120", message)
        self.assertIn("摘要：Please cover the rollback path.", message)
        self.assertIn("#discussion_r4", message)
        self.assertNotIn("secret source code", message)

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

    def test_comment_collector_writes_message_without_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            event_path = os.path.join(directory, "event.json")
            output_path = os.path.join(directory, "artifact", "message.json")
            with open(event_path, "w", encoding="utf-8") as event_file:
                json.dump(
                    {
                        "action": "created",
                        "issue": {"number": 31, "title": "Question", "html_url": ""},
                        "comment": {
                            "body": "Collected comment",
                            "html_url": "",
                            "user": {"login": "contributor"},
                        },
                        "sender": {"login": "contributor"},
                    },
                    event_file,
                )
            environment = {
                "GITHUB_EVENT_PATH": event_path,
                "GITHUB_EVENT_NAME": "issue_comment",
                "GITHUB_REPOSITORY": "OtterMind/Chat2DB",
                "GITHUB_ACTOR": "contributor",
                "GITHUB_RUN_ID": "301",
                "QQ_MESSAGE_OUTPUT_PATH": output_path,
            }

            with patch.dict(os.environ, environment, clear=True):
                self.assertEqual(0, notify_qq.main())

            with open(output_path, encoding="utf-8") as artifact_file:
                envelope = json.load(artifact_file)
            self.assertEqual(1, envelope["version"])
            self.assertEqual("issue_comment", envelope["event_name"])
            self.assertEqual("OtterMind/Chat2DB", envelope["repository"])
            self.assertIn("Issue #31 已评论", envelope["message"])

    @patch("notify_qq.send_relay_message")
    def test_prepared_sender_uses_original_run_as_delivery_id(self, send_message):
        send_message.return_value = {"message_id": "55"}
        with tempfile.TemporaryDirectory() as directory:
            event_path = os.path.join(directory, "workflow-run.json")
            message_path = os.path.join(directory, "message.json")
            with open(event_path, "w", encoding="utf-8") as event_file:
                json.dump({"action": "completed"}, event_file)
            with open(message_path, "w", encoding="utf-8") as message_file:
                json.dump(
                    {
                        "version": 1,
                        "event_name": "pull_request_review",
                        "repository": "OtterMind/Chat2DB",
                        "message": "[Chat2DB GitHub] PR #32 评审已批准",
                    },
                    message_file,
                )
            environment = {
                "GITHUB_EVENT_PATH": event_path,
                "GITHUB_EVENT_NAME": "workflow_run",
                "GITHUB_REPOSITORY": "OtterMind/Chat2DB",
                "GITHUB_ACTOR": "github-actions",
                "GITHUB_RUN_ID": "sender-run",
                "QQ_PREPARED_MESSAGE_PATH": message_path,
                "QQ_DELIVERY_ID": "collector-run",
                "QQ_RELAY_URL": "https://qq-relay.example.com/v1/qq/github",
                "QQ_RELAY_TOKEN": "relay-secret",
            }

            with patch.dict(os.environ, environment, clear=True):
                self.assertEqual(0, notify_qq.main())

        send_message.assert_called_once_with(
            "https://qq-relay.example.com/v1/qq/github",
            "relay-secret",
            "OtterMind/Chat2DB",
            "collector-run",
            "[Chat2DB GitHub] PR #32 评审已批准",
        )

    def test_prepared_sender_rejects_wrong_repository(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as message_file:
            json.dump(
                {
                    "version": 1,
                    "event_name": "issue_comment",
                    "repository": "attacker/repository",
                    "message": "untrusted",
                },
                message_file,
            )
            message_file.flush()

            with self.assertRaises(notify_qq.ConfigurationError):
                notify_qq._read_prepared_message(
                    Path(message_file.name), "OtterMind/Chat2DB"
                )

    def test_prepared_sender_rejects_onebot_cq_code(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as message_file:
            json.dump(
                {
                    "version": 1,
                    "event_name": "issue_comment",
                    "repository": "OtterMind/Chat2DB",
                    "message": "[Chat2DB GitHub] [CQ:at,qq=all]",
                },
                message_file,
            )
            message_file.flush()

            with self.assertRaises(notify_qq.ConfigurationError):
                notify_qq._read_prepared_message(
                    Path(message_file.name), "OtterMind/Chat2DB"
                )

    def test_prepared_sender_rejects_unallowed_event_and_oversized_message(self):
        invalid_envelopes = (
            {
                "version": 1,
                "event_name": "workflow_dispatch",
                "repository": "OtterMind/Chat2DB",
                "message": "not a collected event",
            },
            {
                "version": 1,
                "event_name": "issue_comment",
                "repository": "OtterMind/Chat2DB",
                "message": "x" * 901,
            },
        )

        for envelope in invalid_envelopes:
            with self.subTest(event_name=envelope["event_name"]):
                with tempfile.NamedTemporaryFile(
                    mode="w", encoding="utf-8"
                ) as message_file:
                    json.dump(envelope, message_file)
                    message_file.flush()

                    with self.assertRaises(notify_qq.ConfigurationError):
                        notify_qq._read_prepared_message(
                            Path(message_file.name), "OtterMind/Chat2DB"
                        )


if __name__ == "__main__":
    unittest.main()
