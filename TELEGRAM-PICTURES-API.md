# Telegram profile pictures API: build your own

Peppa AI Messages can show the Telegram profile picture of a phone number as its icon. The app does
**not** talk to Telegram itself. It asks a small HTTPS server of yours, and that server uses a Telegram
account to look the number up. This page describes what the app expects, shows examples, and gives a
small server that you can start from.

- [How the app uses the API](#how-the-app-uses-the-api)
- [Authentication](#authentication)
- [GET me (connection test)](#get-me-connection-test)
- [GET photo](#get-photo)
- [Status codes and what the app does](#status-codes-and-what-the-app-does)
- [Examples with curl](#examples-with-curl)
- [A minimal server (Python + Telethon)](#a-minimal-server-python--telethon)
- [Protect your Telegram account](#protect-your-telegram-account)
- [Checklist](#checklist)

## How the app uses the API

In the app: **Settings → Icons and profile pictures**.

| Setting | Example | Meaning |
| --- | --- | --- |
| Telegram profile pictures for numbers that are not contacts | off | Switch (off by default) |
| Telegram profile pictures for contacts without a photo | off | Switch (off by default). A contact's own photo always wins. |
| Telegram API URL | `https://example.com/api/v1` | The **base** URL. The app adds `/me` and `/phone/...`. A `/` at the end is removed. Must start with `https://`. |
| Telegram API token | `tgm_...` | Sent as a Bearer token |
| Only numbers that start with | `+989` | Comma separated international prefixes, for example `+989, +604`. Other numbers are never sent to your server. |

When the app asks:

- when an SMS arrives, and when the user opens a conversation. It **never** asks while the user scrolls
  the conversation list, so the server never gets many lookups at once.
- one request at a time, in the background (connect timeout 20 s, read timeout 60 s). The app never
  waits for it, so a slow server is fine.
- only numbers that match a prefix. Short codes (`50001234`) and text senders (`MYBANK`) are never sent.

What the app keeps:

- A picture, or a "no picture" answer, is kept for **20 days**. After that, the next SMS or the next
  opening of the conversation asks again.
- Pictures are made smaller on the phone (at most 256 px).
- Pictures of numbers not seen for **90 days** are deleted. They are asked again if the number comes
  back.

## Authentication

Every request has this header:

```http
Authorization: Bearer <token>
```

## GET me (connection test)

Used only by **Settings → Test the connection**.

```http
GET /api/v1/me HTTP/1.1
Authorization: Bearer <token>
```

Answer `200` (JSON). The app shows `Works. Token: <name> (<account_status>)`. Both fields are optional;
any `2xx` counts as working.

```json
{"ok": true, "token": {"name": "my-phone", "account_status": "active"}}
```

On error, use a non-`2xx` status. If the body has `{"error": {"code": "..."}}`, the app shows that code,
for example `Connection failed: HTTP 401 unauthorized`.

## GET photo

```http
GET /api/v1/phone/%2B989121234567/photo?format=image HTTP/1.1
Authorization: Bearer <token>
```

- `{phone}` is the international number with `+`, URL-encoded, so `+` arrives as `%2B`. Most frameworks
  decode it for you. Ignore anything that is not a digit.
- The app always sends `format=image`. It wants the **raw picture bytes** in the body. You may also
  support other formats (for example `format=json` with base64) for other clients; the app does not use
  them.

**Answer `200`**: the picture itself, for example `Content-Type: image/jpeg`. JPEG, PNG and WebP work.
The size limit is 5 MB, and 640×640 or smaller is ideal.

**Answer `404`**: this number has no Telegram user, the user hides from phone search, or the user has no
(visible) picture. The body can be anything, for example:

```json
{"ok": false, "error": {"code": "no_photo", "message": "This user has no profile picture, or hides it."}}
```

## Status codes and what the app does

| Status | Meaning | What the app does |
| --- | --- | --- |
| `200` + image | picture found | saves it, shows it, asks again after 20 days |
| `404` | no user / no picture | remembers "no picture" for 20 days (and removes an old picture) |
| `422` | bad phone number | same as `404` |
| `429` | too many requests | pauses **all** requests for `Retry-After` seconds (10 minutes if missing) |
| `503` | Telegram asked to wait, or the account must log in again | same as `429` |
| `401` / `403` | wrong token / no permission | pauses all requests for 1 hour |
| other, timeout, no internet | temporary problem | tries this number again after 1 hour, at the earliest |
| `200` but not an image | broken answer | same as "other" |

`Retry-After` must be a number of seconds, for example `Retry-After: 120`.

## Examples with curl

```bash
BASE=https://example.com/api/v1
TOKEN=your-secret-token

curl -H "Authorization: Bearer $TOKEN" "$BASE/me"
# -> {"ok":true,"token":{"name":"my-phone","account_status":"active"}}

curl -o photo.jpg -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "$BASE/phone/%2B989121234567/photo?format=image"
# -> 200 (photo.jpg is the picture) or 404
```

## A minimal server (Python + Telethon)

[Telethon](https://docs.telethon.dev/) logs in as a normal Telegram user account. You need an
`api_id` and `api_hash` from <https://my.telegram.org> → API development tools.

**1. Log in once and print a session string** (keep it secret: it is a full login to the account):

```python
# login.py
import os
from telethon.sync import TelegramClient
from telethon.sessions import StringSession

with TelegramClient(StringSession(), int(os.environ["TG_API_ID"]), os.environ["TG_API_HASH"]) as client:
    print(client.session.save())  # asks for the phone number and the login code
```

**2. The server**

```bash
pip install fastapi uvicorn telethon   # Python 3.10+
export TG_API_ID=12345 TG_API_HASH=abc... TG_SESSION=1Aa... PHOTO_API_TOKEN=your-secret-token
uvicorn tg_photo_api:app --host 127.0.0.1 --port 8001   # put it behind an HTTPS reverse proxy
```

```python
# tg_photo_api.py
import asyncio
import base64
import hmac
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from telethon import TelegramClient, errors, functions, types
from telethon.sessions import StringSession

API_TOKEN = os.environ["PHOTO_API_TOKEN"]
client = TelegramClient(StringSession(os.environ["TG_SESSION"]), int(os.environ["TG_API_ID"]), os.environ["TG_API_HASH"])
lock = asyncio.Lock()  # one Telegram request at a time

# Small in-memory caches. Use a database in a real server.
NOT_FOUND_TTL = 24 * 3600
PHOTO_TTL = 24 * 3600
cache: dict[str, tuple[float, bytes | None]] = {}  # digits -> (time, picture or None)


@asynccontextmanager
async def lifespan(_app):
    await client.connect()
    yield
    await client.disconnect()


app = FastAPI(lifespan=lifespan)


def error(status: int, code: str, message: str, retry_after: int | None = None) -> JSONResponse:
    headers = {"Retry-After": str(retry_after)} if retry_after else None
    return JSONResponse(status_code=status, headers=headers,
                        content={"ok": False, "error": {"code": code, "message": message}})


def token_ok(request: Request) -> bool:
    return hmac.compare_digest(request.headers.get("authorization", ""), f"Bearer {API_TOKEN}")


async def find_user(digits: str) -> types.User | None:
    """Phone number -> Telegram user, or None. contacts.resolvePhone does not change the contact list."""
    try:
        result = await client(functions.contacts.ResolvePhoneRequest(phone=digits))
    except errors.RPCError as e:
        if "PHONE_NOT_OCCUPIED" in str(e).upper():
            return None
        raise
    return next((u for u in result.users if isinstance(u, types.User)), None)


@app.get("/api/v1/me")
async def me(request: Request):
    if not token_ok(request):
        return error(401, "unauthorized", "Missing or wrong token.")
    status = "active" if await client.is_user_authorized() else "logged_out"
    return {"ok": True, "token": {"name": "my-photo-api", "account_status": status}}


@app.get("/api/v1/phone/{phone}/photo")
async def photo(phone: str, request: Request, format: str = "json"):
    if not token_ok(request):
        return error(401, "unauthorized", "Missing or wrong token.")
    digits = "".join(ch for ch in phone if ch.isdigit())  # "%2B98912..." arrives as "+98912..."
    if digits.startswith("00"):
        digits = digits[2:]
    if not 8 <= len(digits) <= 15:
        return error(422, "bad_phone", "Use the international format, for example +989121234567.")

    hit = cache.get(digits)
    if hit and time.time() - hit[0] < (PHOTO_TTL if hit[1] else NOT_FOUND_TTL):
        raw = hit[1]
    else:
        async with lock:
            try:
                user = await find_user(digits)
                raw = await client.download_profile_photo(user, file=bytes, download_big=True) if user else None
            except errors.FloodWaitError as e:
                return error(503, "flood_wait", "Telegram asked to wait.", retry_after=e.seconds)
            except errors.RPCError as e:
                return error(502, "telegram_error", str(e))
        cache[digits] = (time.time(), raw)

    if not raw:
        return error(404, "no_photo", "No Telegram user with this number, or no visible picture.")
    if format == "image":
        return Response(content=raw, media_type="image/jpeg")
    return {"ok": True, "photo": {"mime": "image/jpeg", "base64": base64.b64encode(raw).decode()}}
```

Notes:

- `contacts.resolvePhone` (`ResolvePhoneRequest`) needs a recent Telethon. With an older version you
  can add the number as a temporary contact (`contacts.importContacts`), read the user, and delete the
  contact again (`contacts.deleteContacts`).
- A user can hide from phone search, or show the picture only to contacts. Then you get "not found" or
  no picture. That is normal: answer `404`.

## Protect your Telegram account

Telegram limits phone lookups strictly. Too many lookups can get the account limited or banned.

- Use a **second Telegram account**, not your main one.
- Cache the answers: phone → user for days, "not found" for at least a day, pictures for a day or more.
  Answers from the cache cost nothing.
- Limit the number of **new** phone numbers per hour per token (for example 15–20). Answer `429` with
  `Retry-After` when the limit is reached; the app pauses.
- On Telethon's `FloodWaitError`, answer `503` with `Retry-After` = the seconds Telegram asked for.
- Keep the session string and the API token secret. Anyone with the session string is logged in as
  that account.
- The app already helps: it asks only on SMS arrival and conversation open, only for your prefixes,
  one request at a time, and it keeps every answer for 20 days.

## Checklist

1. `curl "$BASE/me"` with your token gives `200`.
2. `curl` a number that has a Telegram picture: `200` and an image file. Then a number with no
   Telegram account: `404`.
3. In the app: Settings → Icons and profile pictures → set the URL (the **base**, for example
   `https://example.com/api/v1`) and the token → **Test the connection**: `Works. Token: ...`.
4. Turn on **Telegram profile pictures for numbers that are not contacts**, check the prefixes, then
   open a conversation with a matching number that has a Telegram picture. The picture appears a few
   seconds later (in the conversation and in the list).
