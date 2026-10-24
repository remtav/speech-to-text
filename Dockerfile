FROM python:3.12-slim-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    git \
    gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN python -m pip install --upgrade pip

COPY requirements.txt .

RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/
COPY templates/ ./templates/

ENV WHISPER_MODEL=base
ENV PYTHONUNBUFFERED=1
# CTranslate2 (faster-whisper) loads cuDNN/cuBLAS from the pip-installed NVIDIA wheels
ENV LD_LIBRARY_PATH=/usr/local/lib/python3.12/site-packages/nvidia/cudnn/lib:/usr/local/lib/python3.12/site-packages/nvidia/cublas/lib

EXPOSE 8000

CMD ["python", "-m", "app.main"]
