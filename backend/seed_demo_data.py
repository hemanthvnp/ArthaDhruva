"""Seeds a running risk-engine with a realistic demo: an analyst, a few borrower accounts linked to
loans, recorded scores across the risk range, open cases with notes, and a portfolio projection, so the
console opens on a system in use instead of empty pages.

Everything is done through the public API, with the same validation, audit trail and rate limits a real
user meets; nothing is written to the database directly. The loans are the ones the instance itself
serves (its own portfolio, or the built-in catalog), so no local dataset is needed.

Sign-in for the administrator, one of:
  ADMIN_TOKEN=<bearer token>                      a session you already have
  ADMIN_PASSWORD=<password>                       the bootstrap admin's password from the start-up log.
                                                  On its first sign-in the script enrols the second factor;
                                                  its secret, and every other account created or reset,
                                                  ends up in backend/demo-credentials.txt (gitignored).
  ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=<secret>   an administrator who is already enrolled

Other settings: RISK_ENGINE_URL (default http://localhost:8080), ORG_SLUG (default legacy),
ADMIN_USERNAME (default admin), INSECURE_TLS=1 to accept a self-signed certificate.

  ADMIN_PASSWORD=... python seed_demo_data.py
  RISK_ENGINE_URL=https://localhost INSECURE_TLS=1 ADMIN_TOKEN=eyJ... python seed_demo_data.py

Safe to run again: accounts that exist are left as they are (the analyst's second factor is reset and
re-enrolled, because its secret is never kept between runs), scoring a loan again replaces its score,
and a portfolio run in progress is waited for rather than duplicated.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import struct
import sys
import time

import requests

BASE_URL = os.environ.get("RISK_ENGINE_URL", "http://localhost:8080").rstrip("/") + "/v1"
ORG_SLUG = os.environ.get("ORG_SLUG", "legacy")
ADMIN_USERNAME = os.environ.get("ADMIN_USERNAME", "admin")
VERIFY_TLS = os.environ.get("INSECURE_TLS") != "1"

DEMO_PASSWORD = "DemoPass123!"
ANALYST_USERNAME = "analyst.demo"
CLIENTS = 6            # borrower accounts, each linked to one loan
SCORED_LOANS = 40      # loans scored, spread across the credit-score range
RUN_TIMEOUT_SECONDS = 600


class SeedError(RuntimeError):
    pass


# ---------------------------------------------------------------------------------------------- HTTP

class Api:
    """Requests against the API with a default session credential, carried as the ad_session cookie --
    the API only accepts a bearer Authorization header for the narrow 2FA-setup-token flow (pass
    setup_token for that, never token), everything else is cookie-based now. A refused request raises
    with the server's own message; a rate-limited one waits as long as the server asks and tries again."""

    def __init__(self, token: str | None = None):
        self.token = token
        self.csrf: str | None = None
        self.session = requests.Session()
        self.session.verify = VERIFY_TLS

    def _csrf_token(self) -> str:
        """The double-submit token every write needs (SecurityConfig): the server hands one out in the XSRF-TOKEN
        cookie, and a write sends it back as that cookie and again in the X-XSRF-TOKEN header. GET /csrf has no
        handler on purpose and answers 401 with the cookie attached, so the status is ignored. Fetched once per Api
        and kept (the server holds no state for it). Like every other cookie here it is not left in the session."""
        if self.csrf is None:
            response = self.session.get(BASE_URL + "/csrf", timeout=30)
            self.csrf = response.cookies.get("XSRF-TOKEN") or ""
            self.session.cookies.clear()
            if not self.csrf:
                raise SeedError(f"GET /csrf did not set the XSRF-TOKEN cookie (status {response.status_code})")
        return self.csrf

    def call(self, method: str, path: str, body=None, token: str | None = None, setup_token: str | None = None,
            allow: frozenset = frozenset(), params=None):
        headers = {"Content-Type": "application/json"}
        cookies = {}
        if setup_token:
            # Mutually exclusive with a session cookie on the backend -- never send both, or whichever
            # identity the server resolves first (observed: the cookie) silently shadows the other.
            headers["Authorization"] = f"Bearer {setup_token}"
        else:
            credential = token if token is not None else self.token
            if credential:
                cookies["ad_session"] = credential
            if method.upper() not in ("GET", "HEAD", "OPTIONS"):
                # A bearer-header setup call is exempt on the server; every cookie-session write, and the login
                # POST itself, carries the token.
                csrf = self._csrf_token()
                cookies["XSRF-TOKEN"] = csrf
                headers["X-XSRF-TOKEN"] = csrf
        for _ in range(6):
            response = self.session.request(method, BASE_URL + path, json=body, headers=headers, cookies=cookies,
                                             params=params, timeout=120)
            if response.status_code != 429:
                break
            time.sleep(float(response.headers.get("Retry-After", "1")))
        # Every call states its identity explicitly via token/setup_token above; the session must never
        # accumulate a Set-Cookie as ambient state, or a later call with a *different* or *no* identity
        # silently inherits whoever logged in last (this really happened: client 1's own session cookie,
        # picked up here, outranked the explicit admin cookie on client 2's request and 403'd it).
        self.session.cookies.clear()
        if not response.ok and response.status_code not in allow:
            raise SeedError(f"{method} {path} -> {response.status_code}: {response.text[:300]}")
        return response

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body, **kw)


def totp(secret: str, period: int = 30, digits: int = 6) -> str:
    """RFC 6238 with the standard library only."""
    key = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    digest = hmac.new(key, struct.pack(">Q", int(time.time() // period)), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return str((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 10 ** digits).zfill(digits)


# ------------------------------------------------------------------------------------------ sign-in

def login(api: Api, username: str, password: str, code: str | None = None) -> tuple[dict | None, str | None]:
    """The login response and the session cookie value, or (None, None) when the credentials (or the
    code) were refused. The cookie is None for a setupRequired/mfaRequired response too -- neither
    issues a session, only the setup-token response field or nothing at all."""
    body = {"orgSlug": ORG_SLUG, "username": username, "password": password}
    if code:
        body["totpCode"] = code
    response = api.post("/login", body, allow=frozenset({401}))
    if response.status_code == 401:
        return None, None
    return response.json(), response.cookies.get("ad_session")


def enrol_second_factor(api: Api, setup_token: str) -> tuple[dict, str, str]:
    """Completes enrolment with a setup token. Returns the response body, the secret and the new
    session's cookie value."""
    secret = api.post("/account/2fa/setup", setup_token=setup_token).json()["secret"]
    response = api.post("/account/2fa/confirm", {"code": totp(secret)}, setup_token=setup_token, allow=frozenset({401}))
    if response.status_code == 401:   # the code was computed at the very end of its 30 seconds
        time.sleep(1)
        response = api.post("/account/2fa/confirm", {"code": totp(secret)}, setup_token=setup_token)
    cookie = response.cookies.get("ad_session")
    if not cookie:
        raise SeedError(f"Enrolment did not return a session: {response.text[:300]}")
    return response.json(), secret, cookie


def admin_session(api: Api) -> tuple[str, str | None]:
    """A session cookie value for the administrator, and the second-factor secret if this run created it."""
    if os.environ.get("ADMIN_TOKEN"):
        return os.environ["ADMIN_TOKEN"], None
    password = os.environ.get("ADMIN_PASSWORD")
    if not password:
        raise SeedError("Set ADMIN_TOKEN, or ADMIN_PASSWORD (see the top of this file).")
    secret = os.environ.get("ADMIN_TOTP_SECRET")
    outcome, cookie = login(api, ADMIN_USERNAME, password, totp(secret) if secret else None)
    if outcome is None:
        raise SeedError("The administrator's sign-in was refused. If the account is already enrolled in two-factor "
                        "authentication, pass ADMIN_TOTP_SECRET as well, or pass ADMIN_TOKEN instead.")
    if outcome.get("setupRequired"):
        _, new_secret, cookie = enrol_second_factor(api, outcome["setupToken"])
        return cookie, new_secret
    if cookie:
        return cookie, None
    raise SeedError(f"Unexpected sign-in response for the administrator: {outcome}")


def provision_analyst(api: Api) -> dict:
    created = api.post("/admin/users", {"username": ANALYST_USERNAME, "password": DEMO_PASSWORD, "role": "ANALYST"},
                       allow=frozenset({409}))
    outcome, _ = login(api, ANALYST_USERNAME, DEMO_PASSWORD)
    if outcome is None:
        # Enrolled by an earlier run, whose secret was never kept: enrol again through the same recovery
        # path a lost phone would take.
        api.post(f"/admin/users/{ANALYST_USERNAME}/reset-2fa")
        outcome, _ = login(api, ANALYST_USERNAME, DEMO_PASSWORD)
    if not outcome or not outcome.get("setupRequired"):
        raise SeedError(f"Could not start two-factor enrolment for {ANALYST_USERNAME}: {outcome}")
    _, secret, cookie = enrol_second_factor(api, outcome["setupToken"])
    return {"username": ANALYST_USERNAME, "password": DEMO_PASSWORD, "secret": secret, "token": cookie,
            "existed": created.status_code == 409}


def provision_client(api: Api, index: int, loan_id: str) -> dict:
    username = f"client.demo.{index}"
    created = api.post("/admin/users", {"username": username, "role": "CLIENT", "loanIds": [loan_id]}, allow=frozenset({409}))
    if created.status_code == 409:
        api.post(f"/admin/users/{username}/loans", {"loanId": loan_id}, allow=frozenset({404, 409}))
        return {"username": username, "password": None, "loan_id": loan_id}
    token = created.json()["activationLink"].split("token=", 1)[-1]
    api.post("/activate", {"activationToken": token, "password": DEMO_PASSWORD}, token="")
    return {"username": username, "password": DEMO_PASSWORD, "loan_id": loan_id}


# ------------------------------------------------------------------------------------------- content

def spread(loans: list[dict], n: int) -> list[dict]:
    """n loans evenly spaced along the credit-score ranking, weakest first."""
    ranked = sorted(loans, key=lambda loan: (loan["creditScore"], loan["loanId"]))
    if len(ranked) <= n:
        return ranked
    step = (len(ranked) - 1) / (n - 1)
    return [ranked[round(i * step)] for i in range(n)]


def score_loans(api: Api, token: str, loans: list[dict]) -> dict[str, float]:
    return {loan["loanId"]: api.post("/score", loan, token=token).json()["calibratedProbability"] for loan in loans}


def open_cases(api: Api, analyst: str, admin: str, by_risk: list[tuple[str, float]]) -> int:
    """The riskiest scored loans become cases in different states, the way a review queue looks mid-week.
    The analyst escalates one and the administrator clears it: the four-eyes rule needs two people."""
    opened = 0
    plan = [
        ("ESCALATED", True, "Debt-to-income looks understated against the stated income; asking for pay stubs.",
         "Requested the last three pay stubs and the 2025 tax return from the branch."),
        ("REVIEWED", True, None, "High LTV with mortgage insurance in place. Watching the next two payments."),
        ("REVIEWED", False, None, "Score driven by credit history; payment record is clean so far."),
        ("ESCALATED", True, "Second lien found on the property that is not in the file.",
         "Title search shows a second lien recorded after origination."),
    ]
    for (loan_id, _), (status, flagged, reason, note) in zip(by_risk, plan):
        path = f"/loans/{loan_id}/case"
        current = api.get(path, token=analyst).json()
        if current["status"] != "NEW":
            continue   # an earlier run, or a person, has already worked this case
        body = {"status": status, "assignedTo": ANALYST_USERNAME, "flagged": flagged, "expectedVersion": current["version"]}
        if reason:
            body["reason"] = reason
        api.post(path, body, token=analyst)
        api.post(f"/loans/{loan_id}/notes", {"text": note}, token=analyst)
        opened += 1
    if len(by_risk) >= 4:
        path = f"/loans/{by_risk[3][0]}/case"
        current = api.get(path, token=admin).json()
        if current["status"] == "ESCALATED" and current.get("escalatedBy") == ANALYST_USERNAME:
            api.post(path, {"status": "CLEARED", "assignedTo": ANALYST_USERNAME, "flagged": False,
                            "reason": "Second lien was released in 2024; release recorded with the county.",
                            "expectedVersion": current["version"]}, token=admin)
    return opened


def run_portfolio(api: Api, token: str) -> dict:
    """Starts a projection (or joins the one in progress) and waits for it."""
    run = api.post("/risk/portfolio/runs", {"scenarios": ["BASELINE", "ADVERSE", "SEVERELY_ADVERSE"]}, token=token,
                   allow=frozenset({409})).json()
    deadline = time.time() + RUN_TIMEOUT_SECONDS
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.time() > deadline:
            raise SeedError("The portfolio run did not finish in time; it continues on the server.")
        time.sleep(2)
        run = api.get(f"/risk/portfolio/runs/{run['id']}", token=token).json()
        print(f"  portfolio run: {run['loansDone']} of {run['loansTotal']} loans", end="\r", flush=True)
    print()
    if run["status"] != "COMPLETED":
        raise SeedError(f"The portfolio run ended as {run['status']}: {run.get('error')}")
    return run


# ---------------------------------------------------------------------------------------------- main

def main() -> None:
    if not VERIFY_TLS:
        requests.packages.urllib3.disable_warnings()   # the certificate is self-signed on purpose here
    api = Api()
    failures: list[str] = []

    api.token, admin_secret = admin_session(api)
    catalog = api.get("/loans", params={"limit": 500}).json()
    if not catalog:
        raise SeedError("The instance has no loans to work with.")
    print(f"{len(catalog)} loans available on {BASE_URL}")

    analyst = None
    try:
        analyst = provision_analyst(api)
        print(f"Analyst ready: {analyst['username']}")
    except Exception as e:   # each step is independent: one failing must not hide what the others did
        failures.append(f"analyst: {e}")

    chosen = spread(catalog, SCORED_LOANS)
    clients = []
    borrowers = chosen[: CLIENTS // 2] + chosen[-(CLIENTS - CLIENTS // 2):]   # the weakest and the strongest
    for i, loan in enumerate(borrowers, start=1):
        try:
            clients.append(provision_client(api, i, loan["loanId"]))
        except Exception as e:
            failures.append(f"client {i}: {e}")
    print(f"{len(clients)} borrower accounts ready")

    scores: dict[str, float] = {}
    if analyst:
        try:
            scores = score_loans(api, analyst["token"], chosen)
            print(f"{len(scores)} loans scored")
            by_risk = sorted(scores.items(), key=lambda item: -item[1])
            print(f"{open_cases(api, analyst['token'], api.token, by_risk)} cases opened")
            for loan in chosen[:3]:
                api.post("/expected-loss", loan, token=analyst["token"])
            api.post("/risk/term-structure/compare", {"loan": chosen[0]}, token=analyst["token"])
            api.get("/regime-forecast", params={"monthsAhead": 24}, token=analyst["token"])
        except Exception as e:
            failures.append(f"scores and cases: {e}")
        try:
            run = run_portfolio(api, analyst["token"])
            print(f"Portfolio projected: {run['loansTotal']} loans under {len(run['scenarios'])} scenarios")
        except Exception as e:
            failures.append(f"portfolio run: {e}")

    # A refused and an accepted sign-in, so the sign-in log has both.
    for client in [c for c in clients if c["password"]][:2]:
        login(api, client["username"], "WrongPassword!9")
        login(api, client["username"], client["password"])

    print("\n" + "=" * 78)
    # Credentials go to a local, gitignored file rather than the console: a freshly generated secret
    # printed to stdout lingers in terminal scrollback, tmux history and any CI log capture for as long
    # as that history is kept, with no way to redact it after the fact. A file the operator opens once
    # and the .gitignore blocks from ever being committed has the same one-time-delivery property
    # without that open-ended exposure.
    lines = []
    if admin_secret:
        lines.append(f"ADMINISTRATOR {ADMIN_USERNAME}: two-factor secret {admin_secret}")
        lines.append("  Add it to an authenticator app now. It is not stored anywhere else.")
    if analyst:
        lines.append(f"ANALYST       {analyst['username']}  password {analyst['password']}  two-factor secret {analyst['secret']}")
    for client in clients:
        password = client["password"] or "(unchanged from an earlier run)"
        risk = f"  default risk {scores[client['loan_id']]:.2%}" if client["loan_id"] in scores else ""
        lines.append(f"BORROWER      {client['username']:<15} password {password}  loan {client['loan_id']}{risk}")
    creds_path = os.path.join(os.path.dirname(__file__), "demo-credentials.txt")
    with open(creds_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    try:
        os.chmod(creds_path, 0o600)
    except OSError:
        pass   # best-effort outside POSIX; the file is still gitignored and local-only
    print(f"Credentials for the accounts just created or reset: {creds_path}")
    print("(gitignored, local only -- open it once, then delete it)")
    print(f"Organization: {ORG_SLUG}")
    if failures:
        print(f"\n{len(failures)} step(s) failed; the others completed:")
        for failure in failures:
            print(f"  - {failure}")
    print("=" * 78)
    if failures:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except SeedError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
