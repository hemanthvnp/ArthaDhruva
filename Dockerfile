FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir jupyter nbconvert ipykernel matplotlib numpy scipy pandas

COPY pipeline/ ./pipeline/
COPY notebooks/ ./notebooks/

# Raw ZIPs (historical_data_*/) and all pipeline outputs (data/) are mounted
# as volumes at run time -- see docker-compose.yml -- not baked into the image.
CMD ["python", "-m", "pipeline.run"]
