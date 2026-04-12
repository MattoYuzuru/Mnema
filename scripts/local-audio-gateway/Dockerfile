FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models/audio/hf \
    LOCAL_AUDIO_DATA_DIR=/models/audio \
    PIPER_DATA_DIR=/models/audio/piper \
    PIPER_DOWNLOAD_DIR=/models/audio/piper

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      build-essential \
      espeak-ng \
      ffmpeg \
      git \
      libsndfile1 \
      sox \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir \
      --index-url https://download.pytorch.org/whl/cu124 \
      --extra-index-url https://pypi.org/simple \
      torch==2.6.0 \
      torchaudio==2.6.0 \
    && pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8091

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8091"]
