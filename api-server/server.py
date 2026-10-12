#!/usr/bin/env python3
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import traceback
import unicodedata
from collections import defaultdict, deque
from contextlib import contextmanager
from datetime import datetime, timezone
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


DATA_DIR = Path(os.environ.get("DATA_DIR", "./data"))
DATABASE = DATA_DIR / "hptelecom.sqlite3"
SETUP_TOKEN_FILE = DATA_DIR / ".admin-setup-token"
PORT = int(os.environ.get("PORT", "8080"))
PUBLIC_ORIGIN = os.environ.get("PUBLIC_ORIGIN", "https://hptelecom.id.vn").rstrip("/")
COOKIE_SECURE = os.environ.get("COOKIE_SECURE", "1").lower() not in {"0", "false", "no"}
SESSION_SECONDS = max(900, int(os.environ.get("SESSION_HOURS", "8")) * 60 * 60)
PASSWORD_ITERATIONS = 310_000
MAX_BODY_BYTES = 128 * 1024
class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def team_slug(name):
    text = str(name or "").translate(str.maketrans({"Đ": "D", "đ": "d"}))
    text = unicodedata.normalize("NFD", text)
    text = "".join(char for char in text if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", "", text.lower()) or "nhom"


def unique_team_slug(db, name):
    base = team_slug(name)
    candidate = base
    suffix = 1
    while db.execute("SELECT 1 FROM teams WHERE slug=? LIMIT 1", (candidate,)).fetchone():
        candidate = f"{base}{suffix}"
        suffix += 1
    return candidate


def db_connect():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DATABASE, timeout=15)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA journal_mode = WAL")
    connection.execute("PRAGMA busy_timeout = 15000")
    return connection


@contextmanager
def db_session():
    connection = db_connect()
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def init_database():
    with db_session() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                full_name TEXT NOT NULL,
                username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                email TEXT NOT NULL COLLATE NOCASE UNIQUE,
                phone TEXT NOT NULL DEFAULT '',
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'operator'
                    CHECK (role IN ('admin', 'coordinator', 'operator')),
                status TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'active', 'rejected', 'disabled')),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
            CREATE TABLE IF NOT EXISTS teams (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                slug TEXT,
                kind TEXT NOT NULL CHECK (kind IN ('individual', 'organization')),
                owner_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TEXT NOT NULL,
                UNIQUE(owner_user_id, name COLLATE NOCASE)
            );
            CREATE TABLE IF NOT EXISTS team_members (
                team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                membership_role TEXT NOT NULL CHECK (membership_role IN ('owner', 'coordinator', 'technician')),
                added_by TEXT REFERENCES users(id) ON DELETE SET NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(team_id, user_id)
            );
            CREATE INDEX IF NOT EXISTS team_members_user ON team_members(user_id);
            CREATE TABLE IF NOT EXISTS setup_token (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                token_hash TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS incidents (
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                month TEXT NOT NULL,
                team TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS incidents_month_team ON incidents(month, team);
            CREATE TABLE IF NOT EXISTS relocations (
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                month TEXT NOT NULL,
                team TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS relocations_month_team ON relocations(month, team);
            """
        )
        team_columns = {row["name"] for row in db.execute("PRAGMA table_info(teams)")}
        if "slug" not in team_columns:
            db.execute("ALTER TABLE teams ADD COLUMN slug TEXT")
        used_slugs = set()
        for team in db.execute(
            "SELECT id, name, slug FROM teams ORDER BY created_at, id"
        ).fetchall():
            slug = team["slug"]
            if not slug or slug in used_slugs:
                base = team_slug(team["name"])
                slug = base
                suffix = 1
                while slug in used_slugs:
                    slug = f"{base}{suffix}"
                    suffix += 1
                db.execute("UPDATE teams SET slug=? WHERE id=?", (slug, team["id"]))
            used_slugs.add(slug)
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS teams_slug_unique ON teams(slug)")
    ensure_setup_token()


def ensure_setup_token():
    with db_session() as db:
        user_count = db.execute("SELECT COUNT(*) FROM users").fetchone()[0]
        setup = db.execute("SELECT token_hash FROM setup_token WHERE singleton = 1").fetchone()
        if user_count:
            if SETUP_TOKEN_FILE.exists():
                try:
                    SETUP_TOKEN_FILE.unlink()
                except OSError as exc:
                    print(f"WARNING: Could not remove stale setup token file: {exc}", flush=True)
            return
        if setup and SETUP_TOKEN_FILE.exists():
            token = SETUP_TOKEN_FILE.read_text(encoding="utf-8").strip()
            if token:
                print("One-time admin setup code (enter once at POST /api/setup):", flush=True)
                print(token, flush=True)
                return
        db.execute("DELETE FROM setup_token WHERE singleton = 1")
        token = secrets.token_urlsafe(24)
        SETUP_TOKEN_FILE.write_text(token, encoding="utf-8")
        try:
            SETUP_TOKEN_FILE.chmod(0o600)
        except OSError as exc:
            print(f"WARNING: Could not restrict setup token file permissions: {exc}", flush=True)
        db.execute(
            "INSERT INTO setup_token(singleton, token_hash) VALUES (1, ?)",
            (hashlib.sha256(token.encode()).hexdigest(),),
        )
        print("One-time admin setup code (enter once at POST /api/setup):", flush=True)
        print(token, flush=True)


def password_hash(password, salt=None):
    salt_bytes = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt_bytes, PASSWORD_ITERATIONS
    )
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${salt_bytes.hex()}${digest.hex()}"


def verify_password(password, stored):
    try:
        algorithm, iterations, salt_hex, digest_hex = stored.split("$", 3)
        if algorithm != "pbkdf2_sha256":
            return False
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), int(iterations)
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except (ValueError, TypeError):
        return False


def validate_password(password):
    if not isinstance(password, str) or len(password) < 12 or len(password) > 256:
        raise ApiError(400, "invalid_password", "Mật khẩu phải dài từ 12 đến 256 ký tự.")


def validate_month(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", value):
        raise ApiError(400, "invalid_month", "Tháng phải có định dạng YYYY-MM.")
    return value


def validate_user_input(data, registration=False):
    if not isinstance(data, dict):
        raise ApiError(400, "invalid_body", "Nội dung yêu cầu phải là JSON object.")
    full_name = data.get("fullName", data.get("full_name", ""))
    username = data.get("username", "")
    email = data.get("email", "")
    phone = data.get("phone", "")
    password = data.get("password", "")
    if not isinstance(full_name, str) or not 2 <= len(full_name.strip()) <= 120:
        raise ApiError(400, "invalid_full_name", "Họ tên phải có từ 2 đến 120 ký tự.")
    if not isinstance(username, str) or not re.fullmatch(r"[A-Za-z0-9._-]{3,40}", username):
        raise ApiError(400, "invalid_username", "Tên đăng nhập dài 3-40 ký tự, chỉ dùng chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.")
    if not isinstance(email, str) or len(email) > 254 or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email):
        raise ApiError(400, "invalid_email", "Địa chỉ email không hợp lệ.")
    if not isinstance(phone, str) or len(phone) > 30:
        raise ApiError(400, "invalid_phone", "Số điện thoại không hợp lệ.")
    validate_password(password)
    return full_name.strip(), username.strip(), email.strip(), phone.strip(), password


def validate_record(data):
    if not isinstance(data, dict):
        raise ApiError(400, "invalid_body", "Bản ghi phải là JSON object.")
    month = validate_month(data.get("month"))
    code = data.get("code")
    team = data.get("team", "")
    payload = data.get("data", {})
    if not isinstance(code, str) or not code.strip() or len(code) > 120:
        raise ApiError(400, "invalid_code", "Mã tuyến/báo cáo là bắt buộc và tối đa 120 ký tự.")
    if not isinstance(team, str) or len(team) > 120:
        raise ApiError(400, "invalid_team", "Team/Đội không hợp lệ.")
    if not isinstance(payload, dict):
        raise ApiError(400, "invalid_data", "Trường data phải là JSON object.")
    if len(json.dumps(payload, ensure_ascii=False)) > MAX_BODY_BYTES:
        raise ApiError(413, "record_too_large", "Dữ liệu bản ghi vượt quá giới hạn.")
    return month, code.strip(), team.strip(), payload


def public_user(row):
    team_names = row["team_names"] if "team_names" in row.keys() else ""
    return {
        "id": row["id"],
        "fullName": row["full_name"],
        "username": row["username"],
        "email": row["email"],
        "phone": row["phone"],
        "role": row["role"],
        "status": row["status"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
        "teams": team_names.split(" · ") if team_names else [],
    }


def create_session(db, user_id):
    token = secrets.token_urlsafe(32)
    db.execute(
        "INSERT INTO sessions(token_hash, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        (hashlib.sha256(token.encode()).hexdigest(), user_id, int(time.time()) + SESSION_SECONDS, now_iso()),
    )
    return token


class RateLimiter:
    def __init__(self):
        self.lock = threading.Lock()
        self.entries = defaultdict(deque)

    def allow(self, key, limit, window):
        now = time.monotonic()
        with self.lock:
            bucket = self.entries[key]
            while bucket and bucket[0] <= now - window:
                bucket.popleft()
            if len(bucket) >= limit:
                return False
            bucket.append(now)
            return True


rate_limiter = RateLimiter()


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "HP-API"
    sys_version = ""

    def log_message(self, fmt, *args):
        print(f"{self.client_address[0]} - {fmt % args}", flush=True)

    def _send_json(self, status, payload, headers=None):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", "0" if status == 204 else str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        if COOKIE_SECURE:
            self.send_header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if status != 204:
            self.wfile.write(body)

    def _read_json(self):
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ApiError(400, "invalid_content_length", "Content-Length không hợp lệ.") from exc
        if length < 0 or length > MAX_BODY_BYTES:
            raise ApiError(413, "body_too_large", "Nội dung yêu cầu vượt quá giới hạn 128 KB.")
        raw = self.rfile.read(length)
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(400, "invalid_json", "Nội dung yêu cầu không phải JSON hợp lệ.") from exc
        if not isinstance(data, dict):
            raise ApiError(400, "invalid_body", "Nội dung yêu cầu phải là JSON object.")
        return data

    def _path(self):
        path = urlsplit(self.path).path
        if path == "/api":
            return "/"
        if path.startswith("/api/"):
            return path[4:]
        return path

    def _query(self):
        return parse_qs(urlsplit(self.path).query)

    def _check_origin(self):
        origin = self.headers.get("Origin")
        if origin and origin.rstrip("/") != PUBLIC_ORIGIN:
            raise ApiError(403, "origin_not_allowed", "Nguồn yêu cầu không được phép.")

    def _user(self, db):
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
        except CookieError as exc:
            raise ApiError(401, "unauthorized", "Vui lòng đăng nhập.") from exc
        morsel = cookie.get("hp_session")
        if not morsel:
            raise ApiError(401, "unauthorized", "Vui lòng đăng nhập.")
        token_hash = hashlib.sha256(morsel.value.encode()).hexdigest()
        session = db.execute(
            "SELECT users.* FROM sessions JOIN users ON users.id = sessions.user_id "
            "WHERE sessions.token_hash = ? AND sessions.expires_at > ?",
            (token_hash, int(time.time())),
        ).fetchone()
        if not session or session["status"] != "active":
            raise ApiError(401, "unauthorized", "Phiên đăng nhập không hợp lệ hoặc tài khoản chưa được duyệt.")
        return session

    def _require(self, user, *roles):
        if roles and user["role"] not in roles:
            raise ApiError(403, "forbidden", "Bạn không có quyền thực hiện thao tác này.")

    def _rate_limit(self, action, limit, window):
        address = self.client_address[0]
        if os.environ.get("TRUST_PROXY", "0").lower() in {"1", "true", "yes"}:
            forwarded = self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
            if forwarded:
                address = forwarded
        if not rate_limiter.allow(f"{action}:{address}", limit, window):
            raise ApiError(429, "rate_limited", "Quá nhiều yêu cầu. Vui lòng thử lại sau.")

    def _cookie(self, token, expires):
        cookie = f"hp_session={token}; Path=/api; HttpOnly; SameSite=Strict; Max-Age={expires}"
        if COOKIE_SECURE:
            cookie += "; Secure"
        return cookie

    def _handle(self):
        path = self._path()
        method = self.command
        if method == "OPTIONS":
            self._check_origin()
            self._send_json(204, {}, {
                "Access-Control-Allow-Origin": PUBLIC_ORIGIN,
                "Access-Control-Allow-Credentials": "true",
                "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
                "Access-Control-Allow-Headers": "Content-Type",
                "Vary": "Origin",
            })
            return

        if method in {"POST", "PATCH", "DELETE"}:
            self._check_origin()

        if path == "/health" and method == "GET":
            with db_session() as db:
                db.execute("SELECT 1").fetchone()
            self._send_json(200, {"ok": True, "service": "hptelecom-api"})
            return

        if path == "/setup" and method == "POST":
            self._rate_limit("setup", 5, 900)
            data = self._read_json()
            setup_code = data.get("setupCode")
            if not isinstance(setup_code, str) or len(setup_code) < 20:
                raise ApiError(400, "invalid_setup_code", "Mã khởi tạo không hợp lệ.")
            full_name, username, email, phone, password = validate_user_input(data)
            with db_session() as db:
                db.execute("BEGIN IMMEDIATE")
                token_row = db.execute("SELECT token_hash FROM setup_token WHERE singleton = 1").fetchone()
                if db.execute("SELECT COUNT(*) FROM users").fetchone()[0] != 0 or not token_row:
                    raise ApiError(409, "setup_closed", "Khởi tạo quản trị viên đã hoàn tất.")
                supplied = hashlib.sha256(setup_code.encode()).hexdigest()
                if not hmac.compare_digest(token_row["token_hash"], supplied):
                    raise ApiError(403, "invalid_setup_code", "Mã khởi tạo không chính xác.")
                user_id = secrets.token_hex(16)
                timestamp = now_iso()
                db.execute(
                    "INSERT INTO users(id,full_name,username,email,phone,password_hash,role,status,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?, 'admin','active',?,?)",
                    (user_id, full_name, username, email, phone, password_hash(password), timestamp, timestamp),
                )
                db.execute("DELETE FROM setup_token WHERE singleton = 1")
                session_token = create_session(db, user_id)
                user = db.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            try:
                SETUP_TOKEN_FILE.unlink(missing_ok=True)
            except OSError as exc:
                self.log_error("Could not remove consumed setup token file", exc)
            self._send_json(201, {"user": public_user(user)}, {
                "Set-Cookie": self._cookie(session_token, SESSION_SECONDS),
            })
            return

        if path == "/auth/register" and method == "POST":
            self._rate_limit("register", 5, 3600)
            data = self._read_json()
            full_name, username, email, phone, password = validate_user_input(data)
            user_id = secrets.token_hex(16)
            timestamp = now_iso()
            try:
                with db_session() as db:
                    db.execute(
                        "INSERT INTO users(id,full_name,username,email,phone,password_hash,role,status,created_at,updated_at) "
                        "VALUES(?,?,?,?,?,?, 'operator','pending',?,?)",
                        (user_id, full_name, username, email, phone, password_hash(password), timestamp, timestamp),
                    )
            except sqlite3.IntegrityError as exc:
                raise ApiError(409, "account_exists", "Tên đăng nhập hoặc email đã được sử dụng.") from exc
            self._send_json(202, {
                "message": "Đăng ký thành công. Tài khoản cần được quản trị viên duyệt trước khi đăng nhập.",
                "user": {"id": user_id, "fullName": full_name, "username": username, "status": "pending"},
            })
            return

        if path == "/auth/login" and method == "POST":
            self._rate_limit("login", 10, 900)
            data = self._read_json()
            username = data.get("username")
            password = data.get("password")
            if not isinstance(username, str) or not isinstance(password, str):
                raise ApiError(400, "invalid_credentials", "Hãy nhập tên đăng nhập và mật khẩu.")
            with db_session() as db:
                db.execute("DELETE FROM sessions WHERE expires_at <= ?", (int(time.time()),))
                user = db.execute(
                    "SELECT * FROM users WHERE username = ? OR email = ?",
                    (username.strip(), username.strip()),
                ).fetchone()
                if not user or not verify_password(password, user["password_hash"]):
                    raise ApiError(401, "invalid_credentials", "Tên đăng nhập hoặc mật khẩu không chính xác.")
                if user["status"] != "active":
                    if user["status"] == "pending":
                        raise ApiError(403, "approval_required", "Tài khoản đang chờ quản trị viên duyệt.")
                    raise ApiError(403, "account_inactive", "Tài khoản hiện không hoạt động.")
                token = create_session(db, user["id"])
            self._send_json(200, {"user": public_user(user)}, {
                "Set-Cookie": self._cookie(token, SESSION_SECONDS),
            })
            return

        if path == "/auth/logout" and method == "POST":
            with db_session() as db:
                cookie = SimpleCookie()
                try:
                    cookie.load(self.headers.get("Cookie", ""))
                except CookieError as exc:
                    raise ApiError(400, "invalid_cookie", "Cookie đăng nhập không hợp lệ.") from exc
                morsel = cookie.get("hp_session")
                if morsel:
                    db.execute("DELETE FROM sessions WHERE token_hash = ?", (
                        hashlib.sha256(morsel.value.encode()).hexdigest(),
                    ))
            self._send_json(200, {"ok": True}, {"Set-Cookie": self._cookie("", 0)})
            return

        with db_session() as db:
            user = self._user(db)
            if path == "/auth/me" and method == "GET":
                self._send_json(200, {"user": public_user(user)})
                return

            if path == "/auth/me" and method == "PATCH":
                data = self._read_json()
                allowed = {"fullName", "email", "phone"}
                if not isinstance(data, dict) or not data or set(data) - allowed:
                    raise ApiError(400, "invalid_profile_update", "Chỉ có thể cập nhật họ tên, email hoặc số điện thoại.")
                full_name = data.get("fullName", user["full_name"])
                email = data.get("email", user["email"])
                phone = data.get("phone", user["phone"])
                if not isinstance(full_name, str) or not 2 <= len(full_name.strip()) <= 120:
                    raise ApiError(400, "invalid_full_name", "Họ tên phải có từ 2 đến 120 ký tự.")
                if not isinstance(email, str) or len(email) > 254 or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", email.strip()):
                    raise ApiError(400, "invalid_email", "Địa chỉ email không hợp lệ.")
                if not isinstance(phone, str) or len(phone) > 30:
                    raise ApiError(400, "invalid_phone", "Số điện thoại không hợp lệ.")
                db.execute(
                    "UPDATE users SET full_name=?, email=?, phone=?, updated_at=? WHERE id=?",
                    (full_name.strip(), email.strip(), phone.strip(), now_iso(), user["id"]),
                )
                updated = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
                self._send_json(200, {"user": public_user(updated)})
                return

            if path == "/teams" and method == "GET":
                if user["role"] == "admin":
                    rows = db.execute(
                        "SELECT t.*, tm.membership_role FROM teams t "
                        "LEFT JOIN team_members tm ON tm.team_id=t.id AND tm.user_id=? "
                        "ORDER BY t.created_at DESC",
                        (user["id"],),
                    ).fetchall()
                else:
                    rows = db.execute(
                        "SELECT t.*, tm.membership_role FROM teams t "
                        "JOIN team_members tm ON tm.team_id=t.id "
                        "WHERE tm.user_id=? ORDER BY t.created_at DESC",
                        (user["id"],),
                    ).fetchall()
                result = []
                for team in rows:
                    owner = db.execute(
                        "SELECT id, full_name FROM users WHERE id=?", (team["owner_user_id"],)
                    ).fetchone()
                    members = db.execute(
                        "SELECT u.id, u.full_name, u.username, u.email, tm.membership_role, tm.created_at "
                        "FROM team_members tm JOIN users u ON u.id=tm.user_id "
                        "WHERE tm.team_id=? AND u.status='active' "
                        "ORDER BY CASE tm.membership_role WHEN 'owner' THEN 0 WHEN 'coordinator' THEN 1 ELSE 2 END, "
                        "u.full_name COLLATE NOCASE",
                        (team["id"],),
                    ).fetchall()
                    result.append({
                        "id": team["id"],
                        "name": team["name"],
                        "slug": team["slug"],
                        "kind": team["kind"],
                        "ownerId": team["owner_user_id"],
                        "ownerName": owner["full_name"] if owner else "",
                        "membershipRole": team["membership_role"] or "admin",
                        "createdAt": team["created_at"],
                        "members": [{
                            "id": member["id"],
                            "fullName": member["full_name"],
                            "username": member["username"],
                            "email": member["email"],
                            "role": member["membership_role"],
                            "joinedAt": member["created_at"],
                        } for member in members],
                    })
                self._send_json(200, {"teams": result})
                return

            if path == "/teams" and method == "POST":
                data = self._read_json()
                name = data.get("name")
                kind = data.get("kind")
                if not isinstance(name, str) or not 2 <= len(name.strip()) <= 100:
                    raise ApiError(400, "invalid_team_name", "Tên Team/Đội phải có từ 2 đến 100 ký tự.")
                if kind not in {"individual", "organization"}:
                    raise ApiError(400, "invalid_team_kind", "Chọn nhóm cá nhân hoặc tổ chức.")
                team_id = secrets.token_hex(16)
                timestamp = now_iso()
                try:
                    db.execute("BEGIN IMMEDIATE")
                    slug = unique_team_slug(db, name.strip())
                    db.execute(
                        "INSERT INTO teams(id,name,slug,kind,owner_user_id,created_at) VALUES(?,?,?,?,?,?)",
                        (team_id, name.strip(), slug, kind, user["id"], timestamp),
                    )
                    db.execute(
                        "INSERT INTO team_members(team_id,user_id,membership_role,added_by,created_at) "
                        "VALUES(?,?,'owner',?,?)",
                        (team_id, user["id"], user["id"], timestamp),
                    )
                except sqlite3.IntegrityError as exc:
                    raise ApiError(409, "team_exists", "Bạn đã tạo Team/Đội trùng tên.") from exc
                self._send_json(201, {"team": {
                    "id": team_id, "name": name.strip(), "slug": slug, "kind": kind,
                    "ownerId": user["id"], "membershipRole": "owner", "createdAt": timestamp,
                    "members": [{"id": user["id"], "fullName": user["full_name"],
                                 "username": user["username"], "email": user["email"], "role": "owner",
                                 "joinedAt": timestamp}],
                }})
                return

            if path.startswith("/teams/"):
                parts = path.strip("/").split("/")
                if len(parts) >= 2 and parts[0] == "teams":
                    team_id = parts[1]
                    team = db.execute("SELECT * FROM teams WHERE id=?", (team_id,)).fetchone()
                    if not team:
                        raise ApiError(404, "team_not_found", "Không tìm thấy Team/Đội.")
                    is_owner = team["owner_user_id"] == user["id"]
                    is_admin = user["role"] == "admin"
                    actor_membership = db.execute(
                        "SELECT membership_role FROM team_members WHERE team_id=? AND user_id=?",
                        (team_id, user["id"]),
                    ).fetchone()
                    actor_role = actor_membership["membership_role"] if actor_membership else None
                    is_member = actor_membership is not None
                    can_manage_members = is_owner or is_admin or actor_role == "coordinator"
                    if len(parts) == 3 and parts[2] == "members" and method == "POST":
                        if not can_manage_members:
                            raise ApiError(403, "team_coordinator_required", "Chỉ Điều phối viên của nhóm mới được thêm thành viên.")
                        data = self._read_json()
                        identity = data.get("username", data.get("email", ""))
                        if not isinstance(identity, str) or not identity.strip():
                            raise ApiError(400, "member_identity_required", "Nhập tên đăng nhập hoặc email thành viên.")
                        target = db.execute(
                            "SELECT * FROM users WHERE (username=? OR email=?) AND status='active'",
                            (identity.strip(), identity.strip()),
                        ).fetchone()
                        if not target:
                            raise ApiError(404, "active_member_not_found", "Không tìm thấy tài khoản đã được duyệt đang hoạt động.")
                        try:
                            db.execute(
                                "INSERT INTO team_members(team_id,user_id,membership_role,added_by,created_at) "
                        "VALUES(?,?,'technician',?,?)",
                                (team_id, target["id"], user["id"], now_iso()),
                            )
                        except sqlite3.IntegrityError as exc:
                            raise ApiError(409, "member_already_added", "Người này đã có trong nhóm.") from exc
                        self._send_json(201, {"ok": True})
                        return
                    if len(parts) == 4 and parts[2] == "members" and method == "PATCH":
                        if not (is_owner or is_admin):
                            raise ApiError(403, "team_owner_required", "Chỉ chủ nhóm mới được cấp quyền Điều phối viên.")
                        target_id = parts[3]
                        data = self._read_json()
                        role = data.get("role")
                        if role not in {"coordinator", "technician"}:
                            raise ApiError(400, "invalid_team_role", "Vai trò nhóm phải là Điều phối viên hoặc Kỹ thuật viên.")
                        target_member = db.execute(
                            "SELECT membership_role FROM team_members WHERE team_id=? AND user_id=?",
                            (team_id, target_id),
                        ).fetchone()
                        if not target_member:
                            raise ApiError(404, "team_member_not_found", "Không tìm thấy thành viên trong nhóm.")
                        if target_member["membership_role"] == "owner":
                            raise ApiError(409, "cannot_change_team_owner", "Không thể đổi vai trò của chủ nhóm.")
                        db.execute(
                            "UPDATE team_members SET membership_role=? WHERE team_id=? AND user_id=?",
                            (role, team_id, target_id),
                        )
                        self._send_json(200, {"ok": True, "role": role})
                        return
                    if len(parts) == 4 and parts[2] == "members" and method == "DELETE":
                        target_id = parts[3]
                        if target_id == team["owner_user_id"]:
                            raise ApiError(409, "cannot_remove_team_owner", "Không thể xóa chủ nhóm khỏi nhóm của chính họ.")
                        if not (can_manage_members or (is_member and target_id == user["id"])):
                            raise ApiError(403, "team_member_permission", "Bạn không có quyền xóa thành viên này.")
                        cursor = db.execute(
                            "DELETE FROM team_members WHERE team_id=? AND user_id=? AND membership_role!='owner'",
                            (team_id, target_id),
                        )
                        if cursor.rowcount == 0:
                            raise ApiError(404, "team_member_not_found", "Không tìm thấy thành viên trong nhóm.")
                        self._send_json(200, {"ok": True})
                        return
                    if len(parts) == 2 and method == "DELETE":
                        if not (is_owner or is_admin):
                            raise ApiError(403, "team_owner_required", "Chỉ chủ nhóm mới được xóa Team/Đội.")
                        db.execute("DELETE FROM teams WHERE id=?", (team_id,))
                        self._send_json(200, {"ok": True})
                        return
                    raise ApiError(404, "not_found", "Không tìm thấy API endpoint.")

            if path == "/users" and method == "GET":
                self._require(user, "admin")
                query = self._query()
                status = query.get("status", [None])[0]
                if status and status not in {"pending", "active", "rejected", "disabled"}:
                    raise ApiError(400, "invalid_status", "Trạng thái không hợp lệ.")
                rows = db.execute(
                    "SELECT u.*, (SELECT GROUP_CONCAT(t.name, ' · ') FROM team_members tm "
                    "JOIN teams t ON t.id=tm.team_id WHERE tm.user_id=u.id) AS team_names "
                    "FROM users u WHERE (? IS NULL OR u.status = ?) ORDER BY u.created_at DESC",
                    (status, status),
                ).fetchall()
                self._send_json(200, {"users": [public_user(row) for row in rows]})
                return

            if path.startswith("/users/") and method == "PATCH":
                self._require(user, "admin")
                target_id = path.split("/", 2)[2]
                data = self._read_json()
                allowed = {"status", "role"}
                if not isinstance(data, dict) or not data or set(data) - allowed:
                    raise ApiError(400, "invalid_user_update", "Chỉ có thể cập nhật status hoặc role.")
                target = db.execute("SELECT * FROM users WHERE id = ?", (target_id,)).fetchone()
                if not target:
                    raise ApiError(404, "user_not_found", "Không tìm thấy tài khoản.")
                status = data.get("status", target["status"])
                role = data.get("role", target["role"])
                if status not in {"pending", "active", "rejected", "disabled"}:
                    raise ApiError(400, "invalid_status", "Trạng thái không hợp lệ.")
                if role not in {"admin", "coordinator", "operator"}:
                    raise ApiError(400, "invalid_role", "Vai trò không hợp lệ.")
                if target_id == user["id"] and role != "admin":
                    raise ApiError(409, "cannot_demote_self", "Không thể hạ quyền tài khoản đang đăng nhập.")
                if target["role"] == "admin" and target["status"] == "active" and (role != "admin" or status != "active"):
                    admins = db.execute("SELECT COUNT(*) FROM users WHERE role='admin' AND status='active'").fetchone()[0]
                    if admins <= 1:
                        raise ApiError(409, "last_admin", "Không thể vô hiệu hóa quản trị viên cuối cùng.")
                db.execute("UPDATE users SET status=?, role=?, updated_at=? WHERE id=?", (status, role, now_iso(), target_id))
                updated = db.execute("SELECT * FROM users WHERE id=?", (target_id,)).fetchone()
                self._send_json(200, {"user": public_user(updated)})
                return

            if path.startswith("/users/") and method == "DELETE":
                self._require(user, "admin")
                target_id = path.split("/", 2)[2]
                target = db.execute("SELECT * FROM users WHERE id=?", (target_id,)).fetchone()
                if not target:
                    raise ApiError(404, "user_not_found", "Không tìm thấy tài khoản.")
                if target["role"] == "admin" and target["status"] == "active":
                    admins = db.execute("SELECT COUNT(*) FROM users WHERE role='admin' AND status='active'").fetchone()[0]
                    if admins <= 1:
                        raise ApiError(409, "last_admin", "Không thể xóa quản trị viên cuối cùng.")
                db.execute("DELETE FROM users WHERE id=?", (target_id,))
                self._send_json(200, {"ok": True})
                return

            if path in {"/incidents", "/relocations"} and method == "GET":
                query = self._query()
                month = query.get("month", [None])[0]
                team = query.get("team", [None])[0]
                if month:
                    validate_month(month)
                if team is not None and len(team) > 120:
                    raise ApiError(400, "invalid_team", "Tên Team/Đội tối đa 120 ký tự.")
                try:
                    limit = min(500, max(1, int(query.get("limit", ["100"])[0])))
                    offset = max(0, int(query.get("offset", ["0"])[0]))
                except ValueError as exc:
                    raise ApiError(400, "invalid_pagination", "limit hoặc offset không hợp lệ.") from exc
                table = "incidents" if path == "/incidents" else "relocations"
                records = db.execute(
                    f"SELECT * FROM {table} WHERE (? IS NULL OR month=?) AND (? IS NULL OR team=?) "
                    "ORDER BY month DESC, code ASC LIMIT ? OFFSET ?",
                    (month, month, team, team, limit, offset),
                ).fetchall()
                self._send_json(200, {
                    "records": [self._public_record(row) for row in records],
                    "limit": limit,
                    "offset": offset,
                })
                return

            if path in {"/incidents", "/relocations"} and method == "POST":
                self._require(user, "admin", "coordinator")
                data = self._read_json()
                month, code, team, payload = validate_record(data)
                record_id = secrets.token_hex(16)
                timestamp = now_iso()
                table = "incidents" if path == "/incidents" else "relocations"
                db.execute(
                    f"INSERT INTO {table}(id,code,month,team,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    (record_id, code, month, team, json.dumps(payload, ensure_ascii=False), timestamp, timestamp),
                )
                row = db.execute(f"SELECT * FROM {table} WHERE id=?", (record_id,)).fetchone()
                self._send_json(201, {"record": self._public_record(row)})
                return

            for resource, table in (("incidents", "incidents"), ("relocations", "relocations")):
                prefix = f"/{resource}/"
                if path.startswith(prefix) and method in {"PATCH", "DELETE"}:
                    self._require(user, "admin", "coordinator")
                    record_id = path[len(prefix):]
                    row = db.execute(f"SELECT * FROM {table} WHERE id=?", (record_id,)).fetchone()
                    if not row:
                        raise ApiError(404, "record_not_found", "Không tìm thấy báo cáo.")
                    if method == "DELETE":
                        db.execute(f"DELETE FROM {table} WHERE id=?", (record_id,))
                        self._send_json(200, {"ok": True})
                        return
                    data = self._read_json()
                    month, code, team, payload = validate_record(data)
                    db.execute(
                        f"UPDATE {table} SET code=?,month=?,team=?,payload=?,updated_at=? WHERE id=?",
                        (code, month, team, json.dumps(payload, ensure_ascii=False), now_iso(), record_id),
                    )
                    updated = db.execute(f"SELECT * FROM {table} WHERE id=?", (record_id,)).fetchone()
                    self._send_json(200, {"record": self._public_record(updated)})
                    return

        raise ApiError(404, "not_found", "Không tìm thấy API endpoint.")

    @staticmethod
    def _public_record(row):
        return {
            "id": row["id"],
            "code": row["code"],
            "month": row["month"],
            "team": row["team"],
            "data": json.loads(row["payload"]),
            "createdAt": row["created_at"],
            "updatedAt": row["updated_at"],
        }

    def _dispatch(self):
        try:
            self._handle()
        except ApiError as exc:
            self._send_json(exc.status, {"error": {"code": exc.code, "message": exc.message}})
        except sqlite3.IntegrityError as exc:
            self.log_error("Database constraint rejected request", exc)
            self._send_json(409, {"error": {"code": "conflict", "message": "Dữ liệu bị trùng hoặc không hợp lệ."}})
        except Exception as exc:
            self.log_error("Unhandled API error", exc)
            self._send_json(500, {"error": {"code": "internal_error", "message": "Máy chủ gặp lỗi; hãy thử lại."}})

    def log_error(self, message, error):
        print(f"{message}: {error}", flush=True)
        traceback.print_exc()

    def do_GET(self):
        self._dispatch()

    def do_POST(self):
        self._dispatch()

    def do_PATCH(self):
        self._dispatch()

    def do_DELETE(self):
        self._dispatch()

    def do_OPTIONS(self):
        self._dispatch()


if __name__ == "__main__":
    init_database()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), ApiHandler)
    server.daemon_threads = True
    print(f"HP Telecom API listening on 0.0.0.0:{PORT}; public origin={PUBLIC_ORIGIN}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("Shutting down HP Telecom API.", flush=True)
    finally:
        server.server_close()
