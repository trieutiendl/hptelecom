import http.cookiejar
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

os.environ["COOKIE_SECURE"] = "0"
os.environ["PUBLIC_ORIGIN"] = "http://127.0.0.1"

import server


class ApiIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data_dir = tempfile.TemporaryDirectory(dir=Path(__file__).parent)
        server.DATA_DIR = Path(cls.data_dir.name)
        server.DATABASE = server.DATA_DIR / "test.sqlite3"
        server.SETUP_TOKEN_FILE = server.DATA_DIR / ".admin-setup-token"
        server.PASSWORD_ITERATIONS = 1_000
        server.init_database()
        cls.setup_code = server.SETUP_TOKEN_FILE.read_text(encoding="utf-8")
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.ApiHandler)
        cls.base_url = f"http://127.0.0.1:{cls.httpd.server_port}/api"
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=3)
        cls.data_dir.cleanup()

    def client(self):
        cookies = http.cookiejar.CookieJar()
        return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

    def request(self, client, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={
                "Content-Type": "application/json",
                "Origin": "http://127.0.0.1",
            },
        )
        try:
            response = client.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        return response.code, json.loads(raw) if raw else {}

    def test_setup_approval_login_and_record_permissions(self):
        admin = self.client()
        status, body = self.request(admin, "POST", "/setup", {
            "setupCode": self.setup_code,
            "fullName": "Quản trị viên",
            "username": "admin",
            "email": "admin@example.com",
            "password": "Admin-password-123",
        })
        self.assertEqual(status, 201, body)
        self.assertEqual(self.request(admin, "GET", "/users?status=active")[0], 200)

        status, body = self.request(self.client(), "POST", "/auth/register", {
            "fullName": "Nhân viên thử nghiệm",
            "username": "operator",
            "email": "operator@example.com",
            "password": "Operator-password-123",
        })
        self.assertEqual(status, 202, body)
        operator_id = body["user"]["id"]
        self.assertEqual(
            self.request(self.client(), "POST", "/auth/login", {
                "username": "operator",
                "password": "Operator-password-123",
            })[0],
            403,
        )

        self.assertEqual(self.request(admin, "PATCH", f"/users/{operator_id}", {
            "status": "active",
            "role": "coordinator",
        })[0], 200)
        operator = self.client()
        self.assertEqual(self.request(operator, "POST", "/auth/login", {
            "username": "operator",
            "password": "Operator-password-123",
        })[0], 200)

        status, body = self.request(operator, "POST", "/teams", {
            "name": "Đội cáp miền Trung",
            "kind": "organization",
        })
        self.assertEqual(status, 201, body)
        team_id = body["team"]["id"]
        self.assertEqual(body["team"]["name"], "Đội cáp miền Trung")
        self.assertEqual(body["team"]["slug"], "doicapmientrung")
        self.assertEqual(body["team"]["members"][0]["role"], "owner")
        self.assertEqual(self.request(operator, "GET", "/teams")[0], 200)

        status, body = self.request(self.client(), "POST", "/auth/register", {
            "fullName": "Chủ nhóm trùng tên",
            "username": "duplicate-owner",
            "email": "duplicate-owner@example.com",
            "password": "Duplicate-owner-password-123",
        })
        self.assertEqual(status, 202, body)
        duplicate_owner_id = body["user"]["id"]
        self.assertEqual(self.request(admin, "PATCH", f"/users/{duplicate_owner_id}", {
            "status": "active",
            "role": "operator",
        })[0], 200)
        duplicate_owner = self.client()
        self.assertEqual(self.request(duplicate_owner, "POST", "/auth/login", {
            "username": "duplicate-owner",
            "password": "Duplicate-owner-password-123",
        })[0], 200)
        status, duplicate_body = self.request(duplicate_owner, "POST", "/teams", {
            "name": "Đội cáp miền Trung",
            "kind": "organization",
        })
        self.assertEqual(status, 201, duplicate_body)
        self.assertEqual(duplicate_body["team"]["name"], "Đội cáp miền Trung")
        self.assertEqual(duplicate_body["team"]["slug"], "doicapmientrung1")
        status, admin_teams = self.request(admin, "GET", "/teams")
        self.assertEqual(status, 200, admin_teams)
        same_named_teams = [item for item in admin_teams["teams"] if item["name"] == "Đội cáp miền Trung"]
        self.assertEqual({item["slug"] for item in same_named_teams}, {"doicapmientrung", "doicapmientrung1"})

        self.assertEqual(self.request(operator, "POST", f"/teams/{team_id}/members", {
            "username": "admin",
        })[0], 201)
        status, body = self.request(operator, "GET", "/teams")
        self.assertEqual(status, 200, body)
        self.assertEqual(body["teams"][0]["members"][1]["role"], "technician")
        self.assertEqual(self.request(operator, "PATCH", f"/teams/{team_id}/members/" + body["teams"][0]["members"][1]["id"], {
            "role": "coordinator",
        })[0], 200)
        status, body = self.request(operator, "GET", "/teams")
        self.assertEqual(body["teams"][0]["members"][1]["role"], "coordinator")
        self.assertEqual(self.request(operator, "POST", f"/teams/{team_id}/members", {
            "username": "admin",
        })[0], 409)

        status, body = self.request(self.client(), "POST", "/auth/register", {
            "fullName": "Điều phối viên nhóm",
            "username": "team-coordinator",
            "email": "team-coordinator@example.com",
            "password": "Coordinator-password-123",
        })
        self.assertEqual(status, 202, body)
        coordinator_id = body["user"]["id"]
        self.assertEqual(self.request(admin, "PATCH", f"/users/{coordinator_id}", {
            "status": "active",
            "role": "operator",
        })[0], 200)
        group_coordinator = self.client()
        self.assertEqual(self.request(group_coordinator, "POST", "/auth/login", {
            "username": "team-coordinator",
            "password": "Coordinator-password-123",
        })[0], 200)
        self.assertEqual(self.request(operator, "POST", f"/teams/{team_id}/members", {
            "username": "team-coordinator",
        })[0], 201)
        status, body = self.request(operator, "GET", "/teams")
        self.assertEqual(status, 200, body)
        group_member = next(member for member in body["teams"][0]["members"] if member["id"] == coordinator_id)
        self.assertEqual(group_member["role"], "technician")
        self.assertEqual(self.request(operator, "PATCH", f"/teams/{team_id}/members/{coordinator_id}", {
            "role": "coordinator",
        })[0], 200)
        self.assertEqual(self.request(group_coordinator, "PATCH", f"/teams/{team_id}/members/{operator_id}", {
            "role": "technician",
        })[0], 403)
        self.assertEqual(self.request(group_coordinator, "DELETE", f"/teams/{team_id}")[0], 403)

        incident = {
            "month": "2026-10",
            "code": "SC-001",
            "team": "Đội cáp miền Trung",
            "data": {"description": "Mất tín hiệu"},
        }
        status, body = self.request(operator, "POST", "/incidents", incident)
        self.assertEqual(status, 201, body)
        self.assertEqual(body["record"]["data"]["description"], "Mất tín hiệu")
        self.assertEqual(
            self.request(operator, "GET", "/incidents?month=2026-10&team=H%C3%A0%20N%E1%BB%99i")[0],
            200,
        )
        arbitrary_team = dict(incident, code="SC-002", team="Đội tùy chọn không thuộc danh sách tỉnh")
        self.assertEqual(self.request(operator, "POST", "/relocations", arbitrary_team)[0], 201)
        too_long_team = dict(incident, team="x" * 121)
        self.assertEqual(self.request(operator, "POST", "/relocations", too_long_team)[0], 400)

        self.assertEqual(self.request(admin, "POST", "/setup", {
            "setupCode": self.setup_code,
            "fullName": "Quản trị viên khác",
            "username": "second-admin",
            "email": "second@example.com",
            "password": "Second-admin-password",
        })[0], 409)
        self.assertEqual(self.request(operator, "POST", "/auth/logout")[0], 200)
        self.assertEqual(self.request(operator, "GET", "/auth/me")[0], 401)
        self.assertEqual(self.request(admin, "GET", "/health")[0], 200)


if __name__ == "__main__":
    unittest.main()
