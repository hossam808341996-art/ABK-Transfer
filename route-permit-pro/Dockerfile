FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-ara tesseract-ocr-eng fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY source.b64 /tmp/source.b64
RUN base64 -d /tmp/source.b64 | tar -xz -C /app && rm -f /tmp/source.b64
RUN pip install --no-cache-dir -r /app/requirements.txt
ENV PUBLIC_MODE=1 PYTHONUNBUFFERED=1 ABK_DATA_DIR=/app/data
RUN mkdir -p /app/data
EXPOSE 8000
CMD ["sh", "-c", "uvicorn abk.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
