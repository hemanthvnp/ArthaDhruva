"""Seeds a *running* risk-engine instance with a realistic demo dataset -- so the app can be
demonstrated as a populated, in-use system (real clients, real linked loans, real computed
scores, real audit/login history) instead of an empty calculator someone types numbers into.

Unlike export_model.py etc. (which write files this Java app reads at startup), this script is
API-driven like batch_refresh_segment_correlation.py: every user, loan link, and score it produces
is a side effect of a real authenticated HTTP call against a live risk-engine, using the exact
same validation/persistence/audit paths a real admin or analyst would go through. It never touches
Postgres/Redis/Neo4j directly.

Prerequisites:
  - risk-engine running and reachable (RISK_ENGINE_URL, default http://localhost:8080)
  - one already-authenticated ADMIN bearer token (ADMIN_TOKEN) -- log in as the bootstrap admin
    once (see README) and pass its token here; this script does not perform the admin's own
    login/2FA
  - data/processed/loan_level populated (run the pipeline first -- see README "Run the pipeline")

Usage:
  ADMIN_TOKEN=eyJ... python seed_demo_data.py
  RISK_ENGINE_URL=http://localhost:8090 ADMIN_TOKEN=eyJ... python seed_demo_data.py

Safe to re-run: usernames are deterministic, and the script treats an existing account (the API's
own 409 "Username already exists") as "already seeded" rather than erroring. See design.md's
"Idempotency" decision for why an already-enrolled ANALYST account still needs one extra step
(admin-driven 2FA reset) on a repeat run -- its TOTP secret is intentionally never persisted
anywhere between runs.
"""
import base64
import hashlib
import hmac
import os
import struct
import sys
import time

import polars as pl
import requests

RISK_ENGINE_URL = os.environ.get("RISK_ENGINE_URL", "http://localhost:8080")
ADMIN_TOKEN = os.environ.get("ADMIN_TOKEN")

DEMO_PASSWORD = "DemoPass123!"
ANALYST_USERNAME = "analyst.demo"
NUM_LOW_RISK = 4
NUM_HIGH_RISK = 4

FEATURE_COLUMNS = [
    "loan_sequence_number", "credit_score", "original_dti", "original_upb", "original_cltv",
    "original_ltv", "original_interest_rate", "original_loan_term", "number_of_borrowers",
    "number_of_units", "mi_percent", "occupancy_status", "property_type", "loan_purpose",
    "channel", "first_time_homebuyer_flag", "property_state",
]


class SeedError(RuntimeError):
    pass


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------

class ApiClient:
    """Thin wrapper: injects a bearer token (defaulting to the admin token) and raises with the
    response body on unexpected non-2xx so failures are readable instead of a bare status code."""

    def __init__(self, base_url: str, default_token: str | None):
        self.base_url = base_url.rstrip("/")
        self.default_token = default_token

    def _headers(self, token):
        headers = {"Content-Type": "application/json"}
        effective = token if token is not None else self.default_token
        if effective:
            headers["Authorization"] = f"Bearer {effective}"
        return headers

    def post(self, path, json_body=None, token=None, allow_statuses=frozenset()):
        resp = requests.post(f"{self.base_url}{path}", json=json_body, headers=self._headers(token))
        if not resp.ok and resp.status_code not in allow_statuses:
            raise SeedError(f"POST {path} -> {resp.status_code}: {resp.text}")
        return resp

    def get(self, path, params=None, token=None, allow_statuses=frozenset()):
        resp = requests.get(f"{self.base_url}{path}", params=params, headers=self._headers(token))
        if not resp.ok and resp.status_code not in allow_statuses:
            raise SeedError(f"GET {path} -> {resp.status_code}: {resp.text}")
        return resp


# ---------------------------------------------------------------------------
# TOTP (RFC 6238), stdlib only -- same algorithm hand-validated earlier against this exact app
# ---------------------------------------------------------------------------

def generate_totp(secret: str, period: int = 30, digits: int = 6) -> str:
    key = base64.b32decode(secret.upper() + "=" * ((8 - len(secret) % 8) % 8))
    counter = int(time.time() // period)
    msg = struct.pack(">Q", counter)
    digest = hmac.new(key, msg, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


# ---------------------------------------------------------------------------
# Loan sampling -- real feature values, deliberately mixed risk, from the processed dataset
# ---------------------------------------------------------------------------

def sample_loans(n_low_risk: int = NUM_LOW_RISK, n_high_risk: int = NUM_HIGH_RISK) -> list[dict]:
    loan_level_glob = "../data/processed/loan_level/orig_year=*/orig_quarter=*/*.parquet"
    try:
        # extra_columns="ignore": quarters processed under different schema-migration states
        # (see pipeline/schema.py's legacy vs. 2026 layout) can carry different column sets --
        # harmless here since only FEATURE_COLUMNS is actually selected.
        df = (
            pl.scan_parquet(loan_level_glob, extra_columns="ignore")
            .select(FEATURE_COLUMNS)
            .drop_nulls()
            .collect()
        )
    except pl.exceptions.PolarsError as e:
        raise SeedError(
            "Could not read data/processed/loan_level -- this project's processed dataset isn't "
            "populated locally, or its schema doesn't match what this script expects. Run the "
            "pipeline first (README section 3, 'Run the pipeline') before seeding demo data.\n"
            f"Underlying error: {e}"
        ) from e
    if df.height == 0:
        raise SeedError("data/processed/loan_level is empty -- run the pipeline first (README section 3).")

    low_risk = (
        df.filter((pl.col("credit_score") >= 760) & (pl.col("original_ltv") <= 70) & (pl.col("original_dti") <= 30))
        .unique(subset=["property_state"], keep="first")
        .head(n_low_risk)
    )
    high_risk = (
        df.filter((pl.col("credit_score") <= 650) & (pl.col("original_ltv") >= 90) & (pl.col("original_dti") >= 40))
        .unique(subset=["property_state"], keep="first")
        .head(n_high_risk)
    )
    sample = pl.concat([low_risk, high_risk])

    if sample.height < 2:
        # Fallback for a small/unusual local dataset where the strict filters above match too
        # few rows -- spread across the credit-score distribution instead of failing outright.
        step = max(1, df.height // (n_low_risk + n_high_risk))
        sample = df.sort("credit_score").gather_every(step).head(n_low_risk + n_high_risk)

    return sample.to_dicts()


def to_loan_features(row: dict, loan_id: str | None = None) -> dict:
    body = {
        "creditScore": int(row["credit_score"]),
        "originalDti": float(row["original_dti"]),
        "originalUpb": float(row["original_upb"]),
        "originalCltv": float(row["original_cltv"]),
        "originalLtv": float(row["original_ltv"]),
        "originalInterestRate": float(row["original_interest_rate"]),
        "originalLoanTerm": int(row["original_loan_term"]),
        "numberOfBorrowers": int(row["number_of_borrowers"]),
        "numberOfUnits": int(row["number_of_units"]),
        "miPercent": float(row["mi_percent"]),
        "occupancyStatus": row["occupancy_status"],
        "propertyType": row["property_type"],
        "loanPurpose": row["loan_purpose"],
        "channel": row["channel"],
        "firstTimeHomebuyerFlag": row["first_time_homebuyer_flag"],
        "propertyState": row["property_state"],
    }
    if loan_id:
        body["loanId"] = loan_id
    return body


# ---------------------------------------------------------------------------
# ANALYST provisioning
# ---------------------------------------------------------------------------

def _login_password_only(base_url: str, username: str, password: str):
    """None means 401 (bad credentials, or -- for a mandatory-2FA role -- a missing/wrong code,
    which is indistinguishable by design; see README's login-hardening section). Anything else
    non-2xx is a real error."""
    resp = requests.post(f"{base_url}/login", json={"username": username, "password": password},
                          headers={"Content-Type": "application/json"})
    if resp.status_code == 401:
        return None
    if not resp.ok:
        raise SeedError(f"POST /login for {username} -> {resp.status_code}: {resp.text}")
    return resp.json()


def _complete_totp_setup(api: ApiClient, setup_token: str) -> dict:
    secret = api.post("/account/2fa/setup", token=setup_token).json()["secret"]
    resp = api.post("/account/2fa/confirm", {"code": generate_totp(secret)}, token=setup_token,
                     allow_statuses={401})
    if resp.status_code == 401:
        # 30s window edge case -- retry once with a freshly computed code.
        resp = api.post("/account/2fa/confirm", {"code": generate_totp(secret)}, token=setup_token)
    body = resp.json()
    body["_secret"] = secret
    return body


def provision_analyst(api: ApiClient) -> dict:
    create_resp = api.post("/admin/users",
                            {"username": ANALYST_USERNAME, "password": DEMO_PASSWORD, "role": "ANALYST"},
                            allow_statuses={409})
    already_existed = create_resp.status_code == 409

    login_body = _login_password_only(api.base_url, ANALYST_USERNAME, DEMO_PASSWORD)
    if login_body is None:
        # Already fully enrolled from a prior run, and this script never persists the secret it
        # generated then -- force fresh re-enrollment via the same admin-driven recovery path a
        # real lost-phone case would use, rather than reaching into the database.
        api.post(f"/admin/users/{ANALYST_USERNAME}/reset-2fa")
        login_body = _login_password_only(api.base_url, ANALYST_USERNAME, DEMO_PASSWORD)
        if login_body is None or not login_body.get("setupRequired"):
            raise SeedError(f"Could not re-enroll {ANALYST_USERNAME} in 2FA after admin reset")

    if login_body.get("setupRequired"):
        confirmed = _complete_totp_setup(api, login_body["setupToken"])
        if "token" not in confirmed:
            raise SeedError(f"2FA confirm for {ANALYST_USERNAME} did not return a session: {confirmed}")
        return {"username": ANALYST_USERNAME, "password": DEMO_PASSWORD, "totp_secret": confirmed["_secret"],
                "token": confirmed["token"], "already_existed": already_existed}

    if "token" in login_body:
        return {"username": ANALYST_USERNAME, "password": DEMO_PASSWORD, "totp_secret": None,
                "token": login_body["token"], "already_existed": already_existed}

    raise SeedError(f"Unexpected login response for {ANALYST_USERNAME}: {login_body}")


# ---------------------------------------------------------------------------
# CLIENT provisioning
# ---------------------------------------------------------------------------

def provision_client(api: ApiClient, index: int, loan_row: dict) -> dict:
    username = f"client.demo.{index}"
    loan_id = loan_row["loan_sequence_number"]

    create_resp = api.post("/admin/users", {"username": username, "role": "CLIENT", "loanIds": [loan_id]},
                            allow_statuses={409})
    already_existed = create_resp.status_code == 409

    if already_existed:
        # Cover a prior partial run that created the user but never got to attach the loan.
        api.post(f"/admin/users/{username}/loans", {"loanId": loan_id}, allow_statuses={404})
        return {"username": username, "password": None, "loan_id": loan_id, "already_existed": True}

    activation_link = create_resp.json()["activationLink"]
    activation_token = activation_link.split("token=", 1)[-1]
    activate_resp = requests.post(f"{api.base_url}/activate",
                                   json={"activationToken": activation_token, "password": DEMO_PASSWORD},
                                   headers={"Content-Type": "application/json"})
    if not activate_resp.ok:
        raise SeedError(f"POST /activate for {username} -> {activate_resp.status_code}: {activate_resp.text}")

    return {"username": username, "password": DEMO_PASSWORD, "loan_id": loan_id, "already_existed": False}


# ---------------------------------------------------------------------------
# Score + realistic activity generation
# ---------------------------------------------------------------------------

def score_client_loans(api: ApiClient, analyst_token: str, clients: list[dict], loan_rows_by_id: dict) -> None:
    for client in clients:
        row = loan_rows_by_id[client["loan_id"]]
        body = to_loan_features(row, loan_id=client["loan_id"])
        resp = api.post("/score", body, token=analyst_token)
        client["score"] = resp.json()


def generate_realistic_activity(api: ApiClient, analyst_token: str, loans: list[dict]) -> None:
    """No persistence target of their own (see design.md Non-Goals) -- purely leaves realistic,
    varied entries in model_invocation_events instead of an all-/score audit trail."""
    for row in loans[:3]:
        api.post("/expected-loss", to_loan_features(row), token=analyst_token)
    for months_ahead in (1, 3, 6):
        api.get("/regime-forecast", params={"monthsAhead": months_ahead}, token=analyst_token)


def generate_login_attempts(clients: list[dict], base_url: str) -> None:
    activated = [c for c in clients if c.get("password")]
    if len(activated) >= 1:
        c = activated[0]
        requests.post(f"{base_url}/login", json={"username": c["username"], "password": "WrongPassword!9"})
    if len(activated) >= 2:
        c = activated[1]
        requests.post(f"{base_url}/login", json={"username": c["username"], "password": c["password"]})


# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

def print_summary(analyst: dict | None, clients: list[dict], failures: list[str]) -> None:
    print("\n" + "=" * 78)
    print(" DEMO DATA SEEDING COMPLETE")
    print("=" * 78)

    if analyst:
        print(f"\nANALYST  username={analyst['username']}  password={analyst['password']}")
        if analyst.get("totp_secret"):
            print(f"         TOTP secret (add to an authenticator app): {analyst['totp_secret']}")
        else:
            print("         2FA already enrolled from a prior run (secret not re-shown).")
    else:
        print("\nANALYST: not provisioned -- see failures below.")

    print(f"\nCLIENTS ({len(clients)}):")
    for c in clients:
        pw = c["password"] if c["password"] else "(already existed -- password unchanged from prior run)"
        score_note = ""
        if "score" in c:
            score_note = f"  calibratedRisk={c['score'].get('calibratedProbability'):.4%}"
        print(f"  username={c['username']:<16} loanId={c['loan_id']:<16} password={pw}{score_note}")

    if failures:
        print(f"\n{len(failures)} issue(s) encountered (other steps still completed):")
        for f in failures:
            print(f"  - {f}")
    else:
        print("\nNo issues encountered.")
    print("=" * 78)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    if not ADMIN_TOKEN:
        print("ERROR: set ADMIN_TOKEN to a valid ADMIN bearer token before running this script.\n"
              "Log in once as the bootstrap admin (see README's Authentication section) and pass "
              "its token, e.g.:\n"
              "  ADMIN_TOKEN=eyJ... python seed_demo_data.py", file=sys.stderr)
        sys.exit(1)

    api = ApiClient(RISK_ENGINE_URL, ADMIN_TOKEN)
    failures: list[str] = []

    print(f"Sampling real loans from data/processed/loan_level (target: {NUM_LOW_RISK} low-risk + "
          f"{NUM_HIGH_RISK} high-risk)...")
    loans = sample_loans()
    states = sorted({row["property_state"] for row in loans})
    print(f"Selected {len(loans)} loans across {len(states)} state(s): {', '.join(states)}")
    loan_rows_by_id = {row["loan_sequence_number"]: row for row in loans}

    analyst = None
    try:
        analyst = provision_analyst(api)
        print(f"Analyst ready: {analyst['username']} (already existed: {analyst['already_existed']})")
    except Exception as e:
        failures.append(f"analyst provisioning: {e}")

    clients: list[dict] = []
    for i, row in enumerate(loans, start=1):
        try:
            client = provision_client(api, i, row)
            clients.append(client)
            print(f"Client ready: {client['username']} -> loan {client['loan_id']} "
                  f"(already existed: {client['already_existed']})")
        except Exception as e:
            failures.append(f"client {i} (loan {row['loan_sequence_number']}): {e}")

    if analyst:
        try:
            score_client_loans(api, analyst["token"], clients, loan_rows_by_id)
        except Exception as e:
            failures.append(f"scoring seeded loans: {e}")

        try:
            generate_realistic_activity(api, analyst["token"], loans)
        except Exception as e:
            failures.append(f"realistic activity generation: {e}")
    else:
        failures.append("skipped scoring/activity generation: no analyst session available")

    try:
        generate_login_attempts(clients, api.base_url)
    except Exception as e:
        failures.append(f"login attempt generation: {e}")

    print_summary(analyst, clients, failures)


if __name__ == "__main__":
    main()
