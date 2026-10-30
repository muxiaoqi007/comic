#!/usr/bin/env python3
"""Local WebDAV + Komga mock server for smoke testing the Comic app.

Usage:
    python3 tools/smoke/server.py [port]

Serves:
    /dav/...                    WebDAV (Basic auth: dav / dav)
    /komga/api/v1/...           Komga (X-API-Key: testkey)
    /koobone/...                Koobone mock (email/password: reader@example.com / koobone)

Fixtures are generated into tools/smoke/fixtures/comics on first run.
"""

import io
import json
import os
import sys
import zipfile
from datetime import datetime, timezone
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote, urlparse
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw, ImageFont

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
FIXTURE_ROOT = os.path.join(BASE_DIR, "fixtures", "comics")

DAV_USER = "dav"
DAV_PASSWORD = "dav"
KOMGA_KEY = "testkey"
KOMGA_LEGACY = os.environ.get("KOMGA_LEGACY") == "1"
KOMGA_BROKEN_SERIES = os.environ.get("KOMGA_BROKEN_SERIES") == "1"

try:
    FONT = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 72)
    SMALL_FONT = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 40)
except OSError:
    FONT = ImageFont.load_default()
    SMALL_FONT = FONT


def page_png(title, page, total, palette):
    color = palette[(page - 1) % len(palette)]
    img = Image.new("RGB", (720, 1080), color)
    draw = ImageDraw.Draw(img)
    draw.rectangle([40, 40, 680, 1040], outline=(255, 255, 255), width=6)
    draw.text((70, 90), title, font=SMALL_FONT, fill=(255, 255, 255))
    draw.text((300, 470), str(page), font=FONT, fill=(255, 255, 255))
    draw.text((70, 960), f"{page} / {total}", font=SMALL_FONT, fill=(255, 255, 255))
    buffer = io.BytesIO()
    img.save(buffer, "PNG")
    return buffer.getvalue()


def page_jpg(title, page, total, palette):
    png = page_png(title, page, total, palette)
    img = Image.open(io.BytesIO(png)).convert("RGB")
    buffer = io.BytesIO()
    img.save(buffer, "JPEG", quality=88)
    return buffer.getvalue()


PALETTES = {
    "Series A": [(196, 64, 82), (46, 134, 171), (63, 167, 106)],
    "Series B": [(92, 74, 168), (206, 126, 52), (40, 110, 140)],
    "One Shot": [(120, 60, 120), (42, 96, 156)],
}

KOOBONE_EMAIL = "reader@example.com"
KOOBONE_PASSWORD = "koobone"
KOOBONE_UIN = 1001

ZLIBRARY_EMAIL = "reader@example.com"
ZLIBRARY_PASSWORD = "zlibrary"
ZLIBRARY_BOOKS = [
    {
        "id": "zl-1",
        "hash": "abc123",
        "title": "Z-Library 测试漫画",
        "author": "Test Author",
        "extension": "epub",
        "filesize": 48213,
        "year": "2026",
        "language": "Chinese",
        "palette": [(92, 74, 168), (206, 126, 52), (40, 110, 140)],
    },
    {
        "id": "zl-2",
        "hash": "def456",
        "title": "Z-Library 测试 PDF",
        "author": "Test Author",
        "extension": "pdf",
        "filesize": 35120,
        "year": "2025",
        "language": "English",
        "palette": [(196, 64, 82), (46, 134, 171), (63, 167, 106)],
    },
]

KOOBONE_VOLUMES = [
    {
        "vol_name": "Koobone Vol 2",
        "vol_author": "作者甲",
        "vol_series": "Koobone 測試系列",
        "vol_seriesid": "KB-TEST",
        "vol_snumber": "卷02",
        "file_md5": "kb-vol2",
        "pages": 4,
        "palette": [(196, 64, 82), (46, 134, 171), (63, 167, 106)],
    },
    {
        "vol_name": "Koobone Vol 10",
        "vol_author": "作者甲",
        "vol_series": "Koobone 測試系列",
        "vol_seriesid": "KB-TEST",
        "vol_snumber": "卷10",
        "file_md5": "kb-vol10",
        "pages": 3,
        "palette": [(92, 74, 168), (206, 126, 52), (40, 110, 140)],
    },
    {
        "vol_name": "Koobone Vol 3",
        "vol_author": "作者甲",
        "vol_series": "Koobone 測試系列",
        "vol_seriesid": "",
        "vol_snumber": "卷03",
        "file_md5": "kb-vol3",
        "pages": 2,
        "palette": [(63, 167, 106), (196, 64, 82)],
    },
    {
        "vol_name": "阿凡达",
        "vol_author": "作者乙",
        "vol_series": "阿凡达",
        "vol_seriesid": "KB-AVATAR",
        "vol_snumber": "卷01",
        "file_md5": "kb-avatar",
        "pages": 2,
        "palette": [(120, 60, 120), (42, 96, 156)],
    },
]

KOOBONE_EPUBS = {}


def build_epub(volume):
    title = volume["vol_name"]
    count = volume["pages"]
    palette = volume["palette"]
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("mimetype", "application/epub+zip")
        archive.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" '
            'media-type="application/oebps-package+xml"/></rootfiles></container>',
        )
        manifest = []
        spine = []
        for number in range(1, count + 1):
            manifest.append(f'<item id="p{number}" href="text/page{number}.xhtml" media-type="application/xhtml+xml"/>')
            manifest.append(f'<item id="i{number}" href="images/{number:03d}.jpg" media-type="image/jpeg"/>')
            spine.append(f'<itemref idref="p{number}"/>')
            archive.writestr(
                f"OEBPS/images/{number:03d}.jpg",
                page_jpg(f"{title} - page {number}", number, count, palette),
            )
            archive.writestr(
                f"OEBPS/text/page{number}.xhtml",
                '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
                f'<img src="../images/{number:03d}.jpg"/></body></html>',
            )
        opf = (
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">'
            f'<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>{escape(title)}</dc:title></metadata>'
            f'<manifest>{"".join(manifest)}</manifest>'
            f'<spine>{"".join(spine)}</spine></package>'
        )
        archive.writestr("OEBPS/content.opf", opf)
    return buffer.getvalue()


def koobone_epub(volume):
    md5 = volume["file_md5"]
    if md5 not in KOOBONE_EPUBS:
        KOOBONE_EPUBS[md5] = build_epub(volume)
    return KOOBONE_EPUBS[md5]


ZLIBRARY_FILES = {}


def zlibrary_file(book):
    key = f"{book['id']}.{book['extension']}"
    if key in ZLIBRARY_FILES:
        return ZLIBRARY_FILES[key]
    volume = {
        "vol_name": book["title"],
        "pages": 3,
        "palette": book["palette"],
    }
    if book["extension"] == "epub":
        body = build_epub(volume)
    else:
        image = Image.open(io.BytesIO(page_png(book["title"], 1, 3, book["palette"])))
        buffer = io.BytesIO()
        image.convert("RGB").save(buffer, "PDF")
        body = buffer.getvalue()
    ZLIBRARY_FILES[key] = body
    return body


def build_fixtures():
    os.makedirs(FIXTURE_ROOT, exist_ok=True)
    series_a = os.path.join(FIXTURE_ROOT, "Series A")
    series_b_chapter = os.path.join(FIXTURE_ROOT, "Series B", "Chapter 01")
    os.makedirs(series_a, exist_ok=True)
    os.makedirs(series_b_chapter, exist_ok=True)

    def write_cbz(path, title, pages):
        palette = PALETTES[title]
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for number in range(1, pages + 1):
                archive.writestr(
                    f"{number:03d}.jpg",
                    page_jpg(f"{title} - page {number}", number, pages, palette),
                )

    write_cbz(os.path.join(series_a, "Vol 1.cbz"), "Series A", 8)
    write_cbz(os.path.join(series_a, "Vol 2.cbz"), "Series A", 6)
    write_cbz(os.path.join(FIXTURE_ROOT, "One Shot.cbz"), "One Shot", 4)

    for number in range(1, 6):
        with open(os.path.join(series_b_chapter, f"{number:03d}.jpg"), "wb") as handle:
            handle.write(page_jpg(f"Series B - page {number}", number, 5, PALETTES["Series B"]))


KOMGA_LIBRARIES = [{"id": "lib-main", "name": "Main Library", "root": "/comics"}]

KOMGA_SERIES = {
    "lib-main": [
        {
            "id": "series-a",
            "libraryId": "lib-main",
            "name": "Series A",
            "booksCount": 2,
            "metadata": {"title": "Series A", "summary": "A two-volume test series served by the mock Komga API."},
        },
        {
            "id": "series-b",
            "libraryId": "lib-main",
            "name": "Series B",
            "booksCount": 1,
            "metadata": {"title": "Series B", "summary": "An image-folder book."},
        },
    ]
}

KOMGA_BOOKS = {
    "series-a": [
        {
            "id": "book-a1",
            "seriesId": "series-a",
            "name": "Vol 1.cbz",
            "number": 1,
            "sizeBytes": 812345,
            "media": {"pagesCount": 8, "mediaType": "application/vnd.comicbook+zip"},
            "metadata": {"title": "Vol 1"},
        },
        {
            "id": "book-a2",
            "seriesId": "series-a",
            "name": "Vol 2.cbz",
            "number": 2,
            "sizeBytes": 645678,
            "media": {"pagesCount": 6, "mediaType": "application/vnd.comicbook+zip"},
            "metadata": {"title": "Vol 2"},
        },
    ],
    "series-b": [
        {
            "id": "book-b1",
            "seriesId": "series-b",
            "name": "Chapter 01",
            "number": 1,
            "sizeBytes": 512000,
            "media": {"pagesCount": 5, "mediaType": "image/jpeg"},
            "metadata": {"title": "Chapter 01"},
        },
    ],
}

BOOK_PAGES = {"book-a1": 8, "book-a2": 6, "book-b1": 5}
BOOK_TITLES = {"book-a1": "Series A", "book-a2": "Series A", "book-b1": "Series B"}
PROGRESS_LOG = []


def dav_path_to_fs(url_path):
    relative = unquote(url_path[len("/dav"):]).strip("/")
    return os.path.join(FIXTURE_ROOT, *[part for part in relative.split("/") if part])


def dav_href(url_path):
    return url_path if url_path.startswith("/dav") else "/dav" + url_path


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, status, body=b"", content_type="application/octet-stream", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD" and body:
            self.wfile.write(body)

    def _check_dav_auth(self):
        import base64

        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            self._send(401, b"auth required", "text/plain", {"WWW-Authenticate": 'Basic realm="dav"'})
            return False
        try:
            decoded = base64.b64decode(header[6:]).decode()
        except Exception:
            decoded = ""
        if decoded != f"{DAV_USER}:{DAV_PASSWORD}":
            self._send(401, b"bad credentials", "text/plain")
            return False
        return True

    def _check_komga_auth(self):
        if self.headers.get("X-API-Key") == KOMGA_KEY:
            return True
        self._send(401, b"unauthorized", "text/plain")
        return False

    def _check_koobone_auth(self):
        return "kbsess=ok" in self.headers.get("Cookie", "")

    # ---------------------------------------------------------------- Koobone
    def _serve_koobone_login(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        form = parse_qs(raw)
        email = (form.get("email") or [""])[0]
        password = (form.get("passwd") or [""])[0]
        if email == KOOBONE_EMAIL and password == KOOBONE_PASSWORD:
            self._send_json(
                {"ret": 0, "msg": "", "uin": KOOBONE_UIN, "uid": "", "skey": "mockskey", "device": ""},
                headers={"Set-Cookie": "kbsess=ok; Path=/; HttpOnly"},
            )
        else:
            self._send_json(
                {"ret": -1, "msg": "邮箱地址错误或密码错误。", "msgid": 501, "uin": 0, "uid": "", "skey": "", "device": ""},
            )

    def _serve_koobone(self):
        path = urlparse(self.path).path
        relative = path[len("/koobone"):]
        host = self.headers.get("Host") or "127.0.0.1:8088"
        base = f"http://{host}/koobone"

        if relative == "/vol_list.php":
            if not self._check_koobone_auth():
                self._send_json({"uin": 0, "total": 0, "totalpage": 0, "nowpage": 0, "data": []})
                return
            data = [
                {
                    "vol_name": volume["vol_name"],
                    "vol_author": volume["vol_author"],
                    "vol_series": volume["vol_series"],
                    "vol_seriesid": volume["vol_seriesid"],
                    "vol_snumber": volume["vol_snumber"],
                    "file_url": f"{base}/files/{volume['file_md5']}.epub",
                    "file_md5": volume["file_md5"],
                    "cover_url": f"{base}/covers/{volume['file_md5']}.png",
                    "cover_small": f"{base}/covers/{volume['file_md5']}.png",
                    "cover_type": "png",
                }
                for volume in KOOBONE_VOLUMES
            ]
            self._send_json(
                {"uin": KOOBONE_UIN, "total": len(data), "totalpage": 1, "nowpage": 1, "data": data},
            )
            return

        if relative.startswith("/files/") and relative.endswith(".epub"):
            if not self._check_koobone_auth():
                self._send(401, b"unauthorized", "text/plain")
                return
            md5 = relative[len("/files/"):-len(".epub")]
            volume = next((v for v in KOOBONE_VOLUMES if v["file_md5"] == md5), None)
            if volume is None:
                self._send(404, b"not found", "text/plain")
                return
            self._send(200, koobone_epub(volume), "application/epub+zip")
            return

        if relative.startswith("/covers/") and relative.endswith(".png"):
            md5 = relative[len("/covers/"):-len(".png")]
            volume = next((v for v in KOOBONE_VOLUMES if v["file_md5"] == md5), None)
            if volume is None:
                self._send(404, b"not found", "text/plain")
                return
            self._send(200, page_png(volume["vol_name"], 1, 1, volume["palette"]), "image/png")
            return

        self._send(404, b"not found", "text/plain")

    # ---------------------------------------------------------------- Z-Library
    def _serve_zlibrary_get(self):
        path = urlparse(self.path).path
        host = self.headers.get("Host") or "127.0.0.1:8088"
        base = f"http://{host}/zlibrary"
        if path == "/zlibrary/eapi/user/profile":
            self._send_json(
                {"success": 1, "user": {"downloads_today": 1, "downloads_limit": 10}},
            )
            return
        if path.startswith("/zlibrary/eapi/book/") and path.endswith("/file"):
            relative = path[len("/zlibrary/eapi/book/"):-len("/file")]
            book_id = relative.split("/")[0]
            book = next((b for b in ZLIBRARY_BOOKS if b["id"] == book_id), None)
            if book is None:
                self._send_json({"success": 0, "error": "book not found"})
                return
            self._send_json(
                {
                    "success": 1,
                    "file": {
                        "allowDownload": 1,
                        "downloadLink": f"{base}/files/{book['id']}.{book['extension']}",
                        "description": book["title"],
                        "author": book["author"],
                        "extension": book["extension"],
                    },
                },
            )
            return
        if path.startswith("/zlibrary/files/"):
            name = path[len("/zlibrary/files/"):]
            book = next(
                (b for b in ZLIBRARY_BOOKS if name == f"{b['id']}.{b['extension']}"),
                None,
            )
            if book is None:
                self._send(404, b"not found", "text/plain")
                return
            content_type = "application/epub+zip" if book["extension"] == "epub" else "application/pdf"
            self._send(200, zlibrary_file(book), content_type)
            return
        if path.startswith("/zlibrary/covers/"):
            name = path[len("/zlibrary/covers/"):]
            book = next(
                (b for b in ZLIBRARY_BOOKS if name == f"{b['id']}.png"),
                None,
            )
            if book is None:
                self._send(404, b"not found", "text/plain")
                return
            self._send(200, page_png(book["title"], 1, 1, book["palette"]), "image/png")
            return
        self._send(404, b"not found", "text/plain")

    def _serve_zlibrary_post(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        form = parse_qs(raw)
        host = self.headers.get("Host") or "127.0.0.1:8088"
        base = f"http://{host}/zlibrary"
        if path == "/zlibrary/eapi/user/login":
            email = (form.get("email") or [""])[0]
            password = (form.get("password") or [""])[0]
            if email == ZLIBRARY_EMAIL and password == ZLIBRARY_PASSWORD:
                self._send_json(
                    {
                        "success": 1,
                        "user": {"id": "1001", "remix_userkey": "mockkey", "name": "Reader"},
                    },
                )
            else:
                self._send_json({"success": 0, "error": "邮箱或密码错误"})
            return
        if path == "/zlibrary/eapi/book/search":
            query = (form.get("message") or [""])[0].lower()
            extensions = form.get("extensions[]") or []
            books = [
                book for book in ZLIBRARY_BOOKS
                if (not query or query in book["title"].lower())
                and (not extensions or book["extension"] in extensions)
            ]
            self._send_json(
                {
                    "success": 1,
                    "books": [
                        {
                            "id": book["id"],
                            "hash": book["hash"],
                            "title": book["title"],
                            "author": book["author"],
                            "cover": f"{base}/covers/{book['id']}.png",
                            "extension": book["extension"],
                            "filesize": book["filesize"],
                            "year": book["year"],
                            "language": book["language"],
                        }
                        for book in books
                    ],
                    "pagination": {"current": 1, "total_pages": 1},
                },
            )
            return
        self._send(404, b"not found", "text/plain")

    # ---------------------------------------------------------------- WebDAV
    def do_PROPFIND(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length:
            self.rfile.read(length)
        url_path = urlparse(self.path).path
        if not url_path.startswith("/dav"):
            self._send(404, b"not found", "text/plain")
            return
        if not self._check_dav_auth():
            return
        fs_path = dav_path_to_fs(url_path)
        if not os.path.exists(fs_path):
            self._send(404, b"not found", "text/plain")
            return

        depth = self.headers.get("Depth", "1")
        entries = [(url_path, fs_path)]
        if depth != "0" and os.path.isdir(fs_path):
            base_href = url_path.rstrip("/")
            for name in sorted(os.listdir(fs_path)):
                entries.append((base_href + "/" + quote(name), os.path.join(fs_path, name)))

        parts = ['<?xml version="1.0" encoding="utf-8"?>', '<d:multistatus xmlns:d="DAV:">']
        for href, entry_fs in entries:
            stat = os.stat(entry_fs)
            is_dir = os.path.isdir(entry_fs)
            name = os.path.basename(entry_fs.rstrip("/")) or "dav"
            parts.append("<d:response>")
            parts.append(f"<d:href>{escape(dav_href(href))}</d:href>")
            parts.append("<d:propstat><d:prop>")
            parts.append(f"<d:displayname>{escape(name)}</d:displayname>")
            if is_dir:
                parts.append("<d:resourcetype><d:collection/></d:resourcetype>")
            else:
                parts.append("<d:resourcetype/>")
                parts.append(f"<d:getcontentlength>{stat.st_size}</d:getcontentlength>")
            parts.append(
                f"<d:getlastmodified>{formatdate(stat.st_mtime, usegmt=True)}</d:getlastmodified>"
            )
            parts.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>")
            parts.append("</d:response>")
        parts.append("</d:multistatus>")
        body = "".join(parts).encode()
        self._send(207, body, "application/xml; charset=utf-8")

    def do_GET(self):
        path = urlparse(self.path).path
        if path.startswith("/dav"):
            self._serve_dav_file()
            return
        if path.startswith("/komga/"):
            self._serve_komga_get(path)
            return
        if path.startswith("/koobone/"):
            self._serve_koobone()
            return
        if path.startswith("/zlibrary/"):
            self._serve_zlibrary_get()
            return
        self._send(404, b"not found", "text/plain")

    def do_HEAD(self):
        path = urlparse(self.path).path
        if not path.startswith("/dav"):
            self._send(404, b"not found", "text/plain")
            return
        if not self._check_dav_auth():
            return
        fs_path = dav_path_to_fs(path)
        if not os.path.isfile(fs_path):
            self._send(404, b"not found", "text/plain")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(os.path.getsize(fs_path)))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()

    def _serve_dav_file(self):
        if not self._check_dav_auth():
            return
        path = urlparse(self.path).path
        fs_path = dav_path_to_fs(path)
        if not os.path.isfile(fs_path):
            self._send(404, b"not found", "text/plain")
            return
        size = os.path.getsize(fs_path)
        range_header = self.headers.get("Range")
        start = 0
        end = size - 1
        status = 200
        if range_header and range_header.startswith("bytes="):
            spec = range_header[len("bytes="):]
            first, _, last = spec.partition("-")
            start = int(first) if first else 0
            end = int(last) if last else end
            start = max(0, start)
            end = min(size - 1, end)
            status = 206
        length = max(0, end - start + 1)
        with open(fs_path, "rb") as handle:
            handle.seek(start)
            data = handle.read(length)
        headers = {"Accept-Ranges": "bytes"}
        if status == 206:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        self._send(status, data, "application/octet-stream", headers)

    # ---------------------------------------------------------------- Komga
    def _serve_komga_get(self, path):
        if not self._check_komga_auth():
            return
        base = "/komga/api/v1"
        relative = path[len(base):] if path.startswith(base) else ""
        query = parse_qs(urlparse(self.path).query)

        if relative == "/libraries":
            self._send_json(KOMGA_LIBRARIES)
            return

        if relative == "/series":
            if KOMGA_BROKEN_SERIES:
                self._send(500, b"boom", "text/plain")
                return
            library_id = (query.get("library_id") or ["lib-main"])[0]
            self._send_json({"content": KOMGA_SERIES.get(library_id, []), "totalElements": 2})
            return

        if relative.startswith("/series/") and relative.endswith("/books"):
            series_id = relative[len("/series/"):-len("/books")]
            self._send_json({"content": KOMGA_BOOKS.get(series_id, []), "totalElements": 1})
            return

        if relative.startswith("/series/") and relative.endswith("/thumbnail"):
            series_id = relative[len("/series/"):-len("/thumbnail")]
            title = "Series A" if series_id == "series-a" else "Series B"
            self._send(200, page_png(title, 1, 8, PALETTES[title]), "image/png")
            return

        match = relative.rsplit("/pages", 1)
        if len(match) == 2 and relative.startswith("/books/"):
            book_id = match[0][len("/books/"):]
            pages = BOOK_PAGES.get(book_id, 1)
            suffix = match[1]
            if suffix == "":
                self._send_json(
                    [
                        {"number": number, "fileName": f"{number:03d}.png", "mediaType": "image/png"}
                        for number in range(1, pages + 1)
                    ]
                )
                return
            if suffix.startswith("/"):
                number = int(suffix[1:])
                title = BOOK_TITLES.get(book_id, "Komga")
                self._send(200, page_png(title, number, pages, PALETTES[title]), "image/png")
                return

        self._send(404, b"not found", "text/plain")

    def _send_json(self, payload, status=200, headers=None):
        self._send(status, json.dumps(payload).encode(), "application/json", headers)

    def do_PATCH(self):
        path = urlparse(self.path).path
        if not path.startswith("/komga/api/v1/"):
            self._send(404, b"not found", "text/plain")
            return
        if not self._check_komga_auth():
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode() if length else ""
        relative = path[len("/komga/api/v1"):]
        if relative.startswith("/books/") and relative.endswith("/read-progress"):
            book_id = relative[len("/books/"):-len("/read-progress")]
            entry = {"bookId": book_id, "body": body}
            PROGRESS_LOG.append(entry)
            print("read-progress:", entry, flush=True)
            self._send(204, b"", "text/plain")
            return
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/koobone/login_do.php":
            self._serve_koobone_login()
            return
        if path.startswith("/zlibrary/"):
            self._serve_zlibrary_post()
            return
        if not path.startswith("/komga/api/v1/"):
            self._send(404, b"not found", "text/plain")
            return
        if not self._check_komga_auth():
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode() if length else ""
        relative = path[len("/komga/api/v1"):]
        if relative in ("/series/list", "/books/list"):
            if KOMGA_LEGACY:
                self._send(404, b"not found", "text/plain")
                return
            if KOMGA_BROKEN_SERIES and relative == "/series/list":
                self._send(500, b"boom", "text/plain")
                return
            try:
                payload = json.loads(body) if body else {}
            except ValueError:
                self._send(400, b"bad request", "text/plain")
                return
            condition = payload.get("condition") or {}
            if relative == "/series/list":
                library_id = ((condition.get("libraryId") or {}).get("value")) or "lib-main"
                self._send_json({"content": KOMGA_SERIES.get(library_id, []), "totalElements": 2})
            else:
                series_id = ((condition.get("seriesId") or {}).get("value")) or ""
                self._send_json({"content": KOMGA_BOOKS.get(series_id, []), "totalElements": 1})
            return
        self._send(404, b"not found", "text/plain")


def main():
    build_fixtures()
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8088
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"fixtures: {FIXTURE_ROOT}")
    print(f"WebDAV:   http://127.0.0.1:{port}/dav  (dav / dav)")
    print(f"Komga:    http://127.0.0.1:{port}/komga (X-API-Key: {KOMGA_KEY})")
    print(f"Z-Library: http://127.0.0.1:{port}/zlibrary (reader@example.com / zlibrary)")
    server.serve_forever()


if __name__ == "__main__":
    main()
