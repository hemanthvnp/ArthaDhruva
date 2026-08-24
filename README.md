# ArthaDhruva — Mortgage Credit Risk Intelligence Platform

Built on the Freddie Mac Single-Family Loan-Level Dataset. See `pipeline/` for the ETL, `notebooks/` for analysis and modeling.

## Setup (for a new collaborator)

The raw and processed data is **not in this repo** (raw ~9GB, processed ~53GB — see `.gitignore`). The pipeline is fully deterministic, so re-running it locally produces byte-identical output to what's already been analyzed. This is the intended way for a second person to get the same dataset without anyone transferring tens of gigabytes around.

### 1. Clone and install dependencies

```bash
git clone https://github.com/hemanthvnp/ArthaDhruva.git
cd ArthaDhruva
pip install -r requirements.txt
pip install -r requirements-notebooks.txt  # used by the notebooks and backend/ export scripts, not the core pipeline
```

Requires Python 3.10+ and roughly 16GB RAM (the pipeline is chunked specifically to stay within that; see the docstring in `pipeline/chunked.py`).

### 2. Get the raw data

Register for and download the Freddie Mac Single-Family Loan-Level Dataset yourself (each collaborator should obtain it under their own account per Freddie Mac's terms, rather than receiving a copy from a teammate).

Place the quarterly ZIPs at the repo root in this exact layout — the pipeline looks for them here (see `pipeline/config.py`):

```
ArthaDhruva/
  historical_data_2020/
    historical_data_2020Q1.zip
    historical_data_2020Q2.zip
    ...
  historical_data_2021/
    ...
  ... through historical_data_2025/ (2025 currently has Q1-Q3 only)
```

You don't need all 23 quarters to start — `extract.py` skips any quarter whose ZIP isn't present, so you can begin with a handful of quarters (e.g. just `2020Q1`-`2020Q4`) and add more later.

### 3. Run the pipeline

```bash
# Everything: extract -> readable -> clean -> preprocess, all quarters found
python -m pipeline.run

# Just a couple of quarters, useful for a fast first run
python -m pipeline.run --quarter 2020Q1 --quarter 2020Q2

# Re-run a single stage only (e.g. after a code change to preprocessing)
python -m pipeline.run --stage preprocess --force
```

Output lands in `data/readable/`, `data/clean/`, `data/processed/loan_level/`, `data/processed/monthly_panel/` — these are the paths the notebooks read from (`../data/processed/...`).

**Alternative: Docker.** `docker-compose.yml` already wires up the same volume layout:

```bash
docker compose run pipeline        # runs python -m pipeline.run
docker compose up jupyter          # Jupyter Lab on localhost:8888, same mounted data
```

### 4. Verify your local run matches

```bash
python -c "from pipeline import validate; validate.write_summary_readme()"
```

Writes `data/docs/final_summary.md` with row counts and default rate per quarter. For the full 23-quarter run, this should read **12,213,996 total loans, 474,387,684 monthly panel rows, ~1.99% overall default rate** — compare against that to confirm your local copy matches.

### 5. Notebooks

Everything in `notebooks/` is tracked in git and reads from `../data/...` relative paths, so once your local `data/` is populated, they run unchanged — no path edits needed.

**One thing to set up before both of you are committing notebook changes**: notebook output cells change on every re-run even when the code doesn't, which makes for noisy diffs and false merge conflicts. Install [`nbstripout`](https://github.com/kynan/nbstripout) once per machine so outputs are stripped automatically on commit:

```bash
pip install nbstripout
nbstripout --install
```

### 6. Backend (risk-engine)

`backend/` holds the deployable side: a Java/Spring Boot scoring service (`backend/risk-engine/`) plus the Python scripts that train the models and export the artifacts it serves (`export_model.py`, `export_hmm.py`, `reexport_onnx.py`).

The exported artifacts (`model.onnx`, `calibration.json`, `category_mappings.json`, `feature_order.json`, `hmm_regime.json`) are small and already committed under `backend/risk-engine/src/main/resources/`, so a teammate normally doesn't need to regenerate them — just start Postgres and run the service:

```bash
docker compose up -d postgres   # SR 11-7-style audit trail storage (model_invocation_events table, auto-created on first boot)
cd backend/risk-engine
./mvnw spring-boot:run          # mvnw.cmd on Windows
```

Requires JDK 17 (the wrapper downloads Maven itself, no separate Maven install needed). The app connects to `jdbc:postgresql://localhost:5432/arthadhruva` by default (matching the Docker Compose service); override with the `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` env vars if you're pointing it at a different Postgres instance.

Every call to `/score` and `/regime-forecast` is persisted immutably to `model_invocation_events` (request, response, latency, success/failure) via a Spring AOP aspect — inspect it directly:

```bash
docker compose exec postgres psql -U arthadhruva -d arthadhruva -c "select endpoint, success, latency_ms, occurred_at from model_invocation_events order by occurred_at desc limit 5;"
```

**Regenerating the artifacts** (only if you change the model/training code) requires `data/processed/...` to be populated first (step 3) and the notebook dependencies installed (step 1):

```bash
cd backend
python export_model.py   # retrains the LightGBM PD model, writes model.onnx + calibration.json + category_mappings.json + feature_order.json
python export_hmm.py     # refits the regime HMM, writes hmm_regime.json
```

Both are deterministic given the same `data/` contents. `trained_checkpoint.joblib` is a local cache of the trained model (regenerable, ~127MB) and is gitignored — don't commit it.
