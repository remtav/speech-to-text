FROM python:3.12-slim-bookworm AS base

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Build stamp: the git commit the image was built from, shown in the UI.  Only
# this small stage sees .git; without it (a source archive) the commit is unknown.
FROM base AS buildinfo
COPY . /src
RUN git config --global --add safe.directory /src \
    && (git -C /src log -1 --format='{"commit": "%H", "date": "%cI"}' 2>/dev/null || echo '{}') > /build-info.json

FROM base

RUN python -m pip install --upgrade pip

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

# Sentence tokenizer used by WhisperX alignment (otherwise downloaded at runtime)
RUN python -m nltk.downloader -d /usr/local/share/nltk_data punkt_tab

COPY app/ ./app/
COPY --from=buildinfo /build-info.json ./app/build-info.json
COPY templates/ ./templates/
COPY static/ ./static/

ENV WHISPER_MODEL=base
ENV WHISPER_PORT=8081
ENV PYTHONUNBUFFERED=1
# CTranslate2 (faster-whisper) loads cuDNN/cuBLAS from the pip-installed NVIDIA wheels
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.12/site-packages/nvidia/cudnn/lib:/usr/local/lib/python3.12/site-packages/nvidia/cublas/lib

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/healthz' % os.getenv('WHISPER_PORT', '8081'), timeout=4)"

CMD ["python", "-m", "app.main"]
