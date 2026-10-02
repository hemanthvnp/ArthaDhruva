FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir jupyter nbconvert ipykernel matplotlib numpy scipy pandas

COPY pipeline/ ./pipeline/
COPY notebooks/ ./notebooks/

# Not root: the code only reads the mounted raw data and writes into the mounted output folders.
RUN useradd --create-home --uid 1000 app && chown app:app /app
USER app

# Raw ZIPs (historical_data_*/) and all pipeline outputs (data/) are mounted
# as volumes at run time -- see docker-compose.yml -- not baked into the image.
CMD ["python", "-m", "pipeline.run"]
