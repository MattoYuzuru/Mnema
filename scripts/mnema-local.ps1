$ErrorActionPreference = 'Stop'

$RootDir = (Resolve-Path "$PSScriptRoot/..").Path
$WorkDir = Join-Path $RootDir '.mnema'
$EnvFile = Join-Path $RootDir '.env.local'
$EnvFileForCompose = $EnvFile -replace '\\', '/'
$OverrideFile = Join-Path $WorkDir 'compose.ports.yml'
$DryRun = $env:MNEMA_DRY_RUN -eq '1'
$UsedPorts = [System.Collections.Generic.HashSet[int]]::new()

New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null

function Test-Command {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-PortBusy {
    param([int]$Port)
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $false
    }
    catch {
        return $true
    }
    finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Get-NextFreePort {
    param([int]$StartPort)
    $port = $StartPort
    while ((Test-PortBusy -Port $port) -or $UsedPorts.Contains($port)) {
        $port++
    }
    [void]$UsedPorts.Add($port)
    return $port
}

function Prompt-WithDefault {
    param([string]$Prompt, [string]$Default)
    $value = Read-Host "$Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $Default
    }
    return $value.Trim()
}

function Prompt-Choice {
    param([string]$Prompt, [string]$Default, [string[]]$Allowed)
    while ($true) {
        $value = Prompt-WithDefault -Prompt $Prompt -Default $Default
        $normalized = $value.Trim().ToLowerInvariant()
        if ($Allowed -contains $normalized) {
            return $normalized
        }
        Write-Host "[error] Supported values: $($Allowed -join ', ')."
    }
}

function Normalize-OllamaModelName {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ''
    }
    return $Value.Trim()
}

function Test-ValidIdentifier {
    param([string]$Value)
    return $Value -match '^[A-Za-z0-9._-]{3,64}$'
}

function Test-ValidPassword {
    param([string]$Value)
    return -not [string]::IsNullOrEmpty($Value) -and $Value.Length -ge 8
}

function Prompt-ValidatedIdentifier {
    param([string]$Prompt, [string]$Default)
    while ($true) {
        $value = Prompt-WithDefault -Prompt $Prompt -Default $Default
        if (Test-ValidIdentifier -Value $value) {
            return $value
        }
        Write-Host "[error] $Prompt must be 3-64 chars and contain only letters, digits, dot, underscore, hyphen."
    }
}

function Prompt-ValidatedPassword {
    param([string]$Prompt, [string]$Default)
    while ($true) {
        $value = Prompt-WithDefault -Prompt $Prompt -Default $Default
        if (Test-ValidPassword -Value $value) {
            return $value
        }
        Write-Host "[error] $Prompt must be at least 8 characters."
    }
}

function New-HexSecret {
    param([int]$ByteCount)
    $bytes = New-Object byte[] $ByteCount
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function New-Base64Secret {
    param([int]$ByteCount)
    $bytes = New-Object byte[] $ByteCount
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Print-PortInfo {
    param([string]$Name, [int]$DefaultPort, [int]$FinalPort)
    if ($DefaultPort -eq $FinalPort) {
        Write-Host "[port] $Name: $FinalPort"
    }
    else {
        Write-Host "[port] $Name: $DefaultPort is busy, using $FinalPort"
    }
}

function Test-DockerGpuAvailable {
    $null = & docker run --rm --gpus all alpine:3.20 true 2>$null
    return $LASTEXITCODE -eq 0
}

function Get-NvidiaDeviceIds {
    if (-not (Test-Command nvidia-smi)) {
        return @()
    }
    $lines = & nvidia-smi -L 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $lines) {
        return @()
    }
    $ids = @()
    foreach ($line in $lines) {
        if ($line -match '^GPU\\s+(\\d+):') {
            $ids += $Matches[1]
        }
    }
    return $ids
}

function Get-RecommendedOllamaModel {
    $memBytes = 0
    try {
        $memBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    }
    catch {
        $memBytes = 0
    }
    if ($memBytes -le 0) {
        return 'qwen3:8b'
    }
    $memGb = [math]::Floor($memBytes / 1GB)
    if ($memGb -lt 12) {
        return 'qwen3:4b'
    }
    if ($memGb -lt 24) {
        return 'qwen3:8b'
    }
    return 'qwen3:14b'
}

function Get-RecommendedOllamaBackupTextModel {
    $memBytes = 0
    try {
        $memBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    }
    catch {
        $memBytes = 0
    }
    if ($memBytes -le 0) {
        return 'qwen2.5:7b'
    }
    $memGb = [math]::Floor($memBytes / 1GB)
    if ($memGb -lt 12) {
        return 'qwen2.5:3b'
    }
    if ($memGb -lt 24) {
        return 'qwen2.5:7b'
    }
    return 'qwen3:8b'
}

function Get-RecommendedOllamaVisionModel {
    $memBytes = 0
    try {
        $memBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    }
    catch {
        $memBytes = 0
    }
    if ($memBytes -le 0) {
        return 'qwen2.5vl:3b'
    }
    $memGb = [math]::Floor($memBytes / 1GB)
    if ($memGb -lt 16) {
        return 'qwen2.5vl:3b'
    }
    if ($memGb -lt 28) {
        return 'qwen2.5vl:7b'
    }
    return 'minicpm-v:8b'
}

function Print-OllamaNextCommands {
    $composeCmd = "docker compose --env-file `"$EnvFile`" -f docker-compose.yml -f `"$OverrideFile`""
    Write-Host "[next] Inspect local models:"
    Write-Host "       $composeCmd exec -T ollama ollama list"
    Write-Host "[next] Pull another model manually:"
    Write-Host "       $composeCmd exec -T ollama ollama pull <model>"
    Write-Host "[next] Run model interactively:"
    Write-Host "       $composeCmd exec -T ollama ollama run <model>"
    Write-Host "[next] Check via API (/api/tags):"
    Write-Host "       curl http://localhost:$OLLAMA_PORT/api/tags"
    Write-Host "[next] Check gateway models/voices:"
    Write-Host "       curl http://localhost:$LOCAL_AI_GATEWAY_PORT/v1/models"
    Write-Host "       curl http://localhost:$LOCAL_AI_GATEWAY_PORT/v1/audio/voices"
}

if (-not (Test-Command docker)) {
    throw '[error] Docker is not installed or not in PATH.'
}

$composeVersion = & docker compose version 2>$null
if ($LASTEXITCODE -ne 0) {
    throw '[error] Docker Compose plugin is not available (docker compose).'
}

Write-Host '== Mnema local bootstrap =='
Write-Host 'The script will detect busy ports and move to the next free port automatically.'

$dbUser = Prompt-ValidatedIdentifier -Prompt 'Postgres user' -Default 'mnema'
$dbName = Prompt-ValidatedIdentifier -Prompt 'Postgres database' -Default 'mnema'
$dbPassword = Prompt-ValidatedPassword -Prompt 'Postgres password' -Default 'mnema_local'
$minioUser = Prompt-ValidatedIdentifier -Prompt 'MinIO root user' -Default 'mnema'
$minioPassword = Prompt-ValidatedPassword -Prompt 'MinIO root password' -Default 'mnema_minio_local'
$starterModelRecommended = Get-RecommendedOllamaModel
$openAiDefaultModel = Prompt-WithDefault -Prompt 'Starter Ollama text model' -Default $starterModelRecommended
Write-Host '[setup] Offline audio backend for TTS/STT'
$audioBackendMode = Prompt-Choice -Prompt 'Audio backend mode (piper/ollama/custom/none)' -Default 'piper' -Allowed @('piper', 'ollama', 'custom', 'none')
$openAiTtsModel = ''
$openAiSttModel = ''
$openAiImageModel = ''
$localAudioBaseUrl = ''
$localImageBaseUrl = ''
$localAudioModels = ''
$localImageDefaultModel = ''
$localTtsVoices = ''
$remoteOpenAiBaseUrl = 'https://api.openai.com'
$ollamaAudioExperimental = 'false'
$ollamaImageExperimental = 'true'
if ($audioBackendMode -eq 'piper') {
    $localAudioModels = Prompt-WithDefault -Prompt 'Piper TTS voices comma-separated' -Default 'ru_RU-irina-medium,en_US-lessac-medium'
    if (-not [string]::IsNullOrWhiteSpace($localAudioModels)) {
        $openAiTtsModel = ($localAudioModels.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })[0]
        $localTtsVoices = $localAudioModels
        $localAudioBaseUrl = 'http://local-audio-gateway:8091'
    }
    else {
        $audioBackendMode = 'none'
    }
}
elseif ($audioBackendMode -eq 'ollama') {
    $ollamaAudioExperimental = 'true'
    Write-Host '[warn] Ollama audio compatibility is experimental; choose only existing Ollama models.'
    $openAiTtsModel = Prompt-WithDefault -Prompt 'Ollama TTS model (optional)' -Default ''
    $openAiTtsModel = Normalize-OllamaModelName -Value $openAiTtsModel
    $openAiSttModel = Prompt-WithDefault -Prompt 'Ollama STT model (optional)' -Default ''
    $localTtsVoices = Prompt-WithDefault -Prompt 'Fallback TTS voices comma-separated (optional)' -Default ''
}
elseif ($audioBackendMode -eq 'custom') {
    $localAudioBaseUrl = Prompt-WithDefault -Prompt 'Local audio backend URL (inside docker network)' -Default 'http://host.docker.internal:8000'
    $openAiTtsModel = Prompt-WithDefault -Prompt 'Default TTS model (optional)' -Default ''
    $openAiTtsModel = Normalize-OllamaModelName -Value $openAiTtsModel
    $openAiSttModel = Prompt-WithDefault -Prompt 'Default STT model (optional)' -Default ''
    $localTtsVoices = Prompt-WithDefault -Prompt 'Fallback TTS voices comma-separated (optional)' -Default ''
}

Write-Host '[setup] Image backend'
$imageBackendMode = Prompt-Choice -Prompt 'Image backend mode (ollama/diffusers/custom/none)' -Default 'ollama' -Allowed @('ollama', 'diffusers', 'custom', 'none')
if ($imageBackendMode -eq 'ollama') {
    $ollamaImageExperimental = 'true'
    $openAiImageModel = Prompt-WithDefault -Prompt 'Ollama image model (optional)' -Default 'x/z-image-turbo'
}
elseif ($imageBackendMode -eq 'diffusers') {
    $ollamaImageExperimental = 'false'
    $openAiImageModel = Prompt-WithDefault -Prompt 'Local Diffusers image model' -Default 'local-sd-turbo'
    $localImageDefaultModel = $openAiImageModel
    if (-not [string]::IsNullOrWhiteSpace($openAiImageModel)) {
        $localImageBaseUrl = 'http://local-image-gateway:8092'
    }
    else {
        $imageBackendMode = 'none'
    }
}
elseif ($imageBackendMode -eq 'custom') {
    $ollamaImageExperimental = 'false'
    $localImageBaseUrl = Prompt-WithDefault -Prompt 'Local image backend URL (inside docker network)' -Default 'http://host.docker.internal:8188'
    $openAiImageModel = Prompt-WithDefault -Prompt 'Default image model (optional)' -Default ''
    $localImageDefaultModel = $openAiImageModel
}
else {
    $ollamaImageExperimental = 'false'
}

$ollamaGpuEnabled = 'false'
$ollamaVisibleGpus = 'all'
$gpuDeviceIds = Get-NvidiaDeviceIds
if (Test-DockerGpuAvailable) {
    $ollamaGpuEnabled = 'true'
    if ($gpuDeviceIds.Count -gt 1) {
        Write-Host "[info] Detected multiple GPUs: $($gpuDeviceIds -join ',')"
        $ollamaVisibleGpus = Prompt-WithDefault -Prompt 'GPU devices for Ollama (all or comma-separated indices)' -Default 'all'
    }
}
else {
    Write-Host '[warn] Docker GPU runtime is not available. Ollama will run on CPU.'
}

$POSTGRES_PORT = Get-NextFreePort -StartPort 5432
$REDIS_PORT = Get-NextFreePort -StartPort 6379
$AUTH_PORT = Get-NextFreePort -StartPort 8083
$USER_PORT = Get-NextFreePort -StartPort 8084
$CORE_PORT = Get-NextFreePort -StartPort 8085
$MEDIA_PORT = Get-NextFreePort -StartPort 8086
$IMPORT_PORT = Get-NextFreePort -StartPort 8087
$AI_PORT = Get-NextFreePort -StartPort 8088
$FRONTEND_PORT = Get-NextFreePort -StartPort 3005
$MINIO_API_PORT = Get-NextFreePort -StartPort 9000
$MINIO_CONSOLE_PORT = Get-NextFreePort -StartPort 9001
$OLLAMA_PORT = Get-NextFreePort -StartPort 11434
$LOCAL_AI_GATEWAY_PORT = Get-NextFreePort -StartPort 8090
$LOCAL_AUDIO_GATEWAY_PORT = Get-NextFreePort -StartPort 8091
$LOCAL_IMAGE_GATEWAY_PORT = Get-NextFreePort -StartPort 8092

Print-PortInfo -Name 'postgres' -DefaultPort 5432 -FinalPort $POSTGRES_PORT
Print-PortInfo -Name 'redis' -DefaultPort 6379 -FinalPort $REDIS_PORT
Print-PortInfo -Name 'auth' -DefaultPort 8083 -FinalPort $AUTH_PORT
Print-PortInfo -Name 'user' -DefaultPort 8084 -FinalPort $USER_PORT
Print-PortInfo -Name 'core' -DefaultPort 8085 -FinalPort $CORE_PORT
Print-PortInfo -Name 'media' -DefaultPort 8086 -FinalPort $MEDIA_PORT
Print-PortInfo -Name 'import' -DefaultPort 8087 -FinalPort $IMPORT_PORT
Print-PortInfo -Name 'ai' -DefaultPort 8088 -FinalPort $AI_PORT
Print-PortInfo -Name 'frontend' -DefaultPort 3005 -FinalPort $FRONTEND_PORT
Print-PortInfo -Name 'minio-api' -DefaultPort 9000 -FinalPort $MINIO_API_PORT
Print-PortInfo -Name 'minio-console' -DefaultPort 9001 -FinalPort $MINIO_CONSOLE_PORT
Print-PortInfo -Name 'ollama' -DefaultPort 11434 -FinalPort $OLLAMA_PORT
Print-PortInfo -Name 'local-ai-gateway' -DefaultPort 8090 -FinalPort $LOCAL_AI_GATEWAY_PORT
if ($audioBackendMode -eq 'piper') {
    Print-PortInfo -Name 'local-audio-gateway' -DefaultPort 8091 -FinalPort $LOCAL_AUDIO_GATEWAY_PORT
}
if ($imageBackendMode -eq 'diffusers') {
    Print-PortInfo -Name 'local-image-gateway' -DefaultPort 8092 -FinalPort $LOCAL_IMAGE_GATEWAY_PORT
}

$envContent = @"
POSTGRES_DB=$dbName
POSTGRES_USER=$dbUser
POSTGRES_PASSWORD=$dbPassword
POSTGRES_PORT=$POSTGRES_PORT

SPRING_DATASOURCE_USERNAME=$dbUser
SPRING_DATASOURCE_PASSWORD=$dbPassword

AUTH_ISSUER=http://localhost:$AUTH_PORT
AUTH_ISSUER_URI=http://localhost:$AUTH_PORT

GOOGLE_CLIENT_ID=disabled-google
GOOGLE_CLIENT_SECRET=disabled-google-secret
GH_CLIENT_ID=disabled-github
GH_CLIENT_SECRET=disabled-github-secret
YANDEX_CLIENT_ID=disabled-yandex
YANDEX_CLIENT_SECRET=disabled-yandex-secret

TURNSTILE_SITE_KEY=
TURNSTILE_SECRET_KEY=

MEDIA_INTERNAL_TOKEN=$(New-HexSecret -ByteCount 24)

AWS_REGION=us-east-1
AWS_BUCKET_NAME=mnema-local
AWS_ENDPOINT=http://minio:9000
AWS_PUBLIC_ENDPOINT=http://localhost:$MINIO_API_PORT
AWS_PATH_STYLE_ACCESS=true
AWS_ACCESS_KEY_ID=$minioUser
AWS_SECRET_ACCESS_KEY=$minioPassword

CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media

AI_PROVIDER=ollama
AI_SYSTEM_MANAGED_PROVIDER_ENABLED=true
AI_SYSTEM_PROVIDER_NAME=ollama
AI_OLLAMA_ENABLED=true
AI_VAULT_MASTER_KEY=$(New-Base64Secret -ByteCount 32)
AI_VAULT_KEY_ID=local-v1

OPENAI_BASE_URL=http://local-ai-gateway:8089
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_SYSTEM_API_KEY=
OPENAI_DEFAULT_MODEL=$openAiDefaultModel
OPENAI_TTS_MODEL=$openAiTtsModel
OPENAI_TTS_VOICE=
OPENAI_TTS_FORMAT=wav
OPENAI_STT_MODEL=$openAiSttModel
OPENAI_IMAGE_MODEL=$openAiImageModel
OPENAI_VIDEO_MODEL=

LOCAL_AI_GATEWAY_PORT=$LOCAL_AI_GATEWAY_PORT
LOCAL_AUDIO_GATEWAY_PORT=$LOCAL_AUDIO_GATEWAY_PORT
LOCAL_IMAGE_GATEWAY_PORT=$LOCAL_IMAGE_GATEWAY_PORT
LOCAL_AI_GATEWAY_TIMEOUT_SECONDS=600
LOCAL_AUDIO_BASE_URL=$localAudioBaseUrl
LOCAL_AUDIO_MODELS=$localAudioModels
LOCAL_AUDIO_DEFAULT_MODEL=$openAiTtsModel
LOCAL_AUDIO_DEFAULT_VOICE=$openAiTtsModel
LOCAL_AUDIO_PRELOAD=true
LOCAL_IMAGE_BASE_URL=$localImageBaseUrl
LOCAL_IMAGE_DEFAULT_MODEL=$localImageDefaultModel
LOCAL_VIDEO_BASE_URL=
LOCAL_TTS_VOICES=$localTtsVoices
REMOTE_OPENAI_BASE_URL=$remoteOpenAiBaseUrl
OLLAMA_AUDIO_EXPERIMENTAL=$ollamaAudioExperimental
OLLAMA_IMAGE_EXPERIMENTAL=$ollamaImageExperimental
OLLAMA_GPU_ENABLED=$ollamaGpuEnabled
OLLAMA_VISIBLE_GPUS=$ollamaVisibleGpus

GROK_BASE_URL=

SPRING_CACHE_TYPE=redis
REDIS_HOST=redis
REDIS_PORT=6379
APP_ENV=local

MINIO_ROOT_USER=$minioUser
MINIO_ROOT_PASSWORD=$minioPassword
"@

Set-Content -Path $EnvFile -Value $envContent -Encoding UTF8

$localAiGatewayExtraDepends = ''
$localAudioGatewayBlock = ''
$localImageGatewayBlock = ''
$extraVolumes = ''
if ($audioBackendMode -eq 'piper') {
    $localAiGatewayExtraDepends += @"
      local-audio-gateway:
        condition: service_healthy
"@
    $extraVolumes += "  mnema_piper_data:`n"
    $localAudioGatewayBlock = @"

  local-audio-gateway:
    networks: [ mnema_net ]
    build:
      context: ./scripts/local-audio-gateway
      dockerfile: Dockerfile
    environment:
      LOCAL_AUDIO_MODELS: "$localAudioModels"
      LOCAL_AUDIO_DEFAULT_MODEL: "$openAiTtsModel"
      LOCAL_AUDIO_DEFAULT_VOICE: "$openAiTtsModel"
      LOCAL_AUDIO_PRELOAD: "`${LOCAL_AUDIO_PRELOAD}"
      PIPER_DATA_DIR: "/models/piper"
      PIPER_DOWNLOAD_DIR: "/models/piper"
    ports: !override
      - "`${LOCAL_AUDIO_GATEWAY_PORT}:8091"
    volumes:
      - mnema_piper_data:/models/piper
    healthcheck:
      test: [ "CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8091/health', timeout=5)" ]
      interval: 10s
      timeout: 5s
      retries: 60
"@
}
if ($imageBackendMode -eq 'diffusers') {
    if (-not [string]::IsNullOrWhiteSpace($localAiGatewayExtraDepends)) {
        $localAiGatewayExtraDepends += "`n"
    }
    $localAiGatewayExtraDepends += @"
      local-image-gateway:
        condition: service_healthy
"@
    $extraVolumes += "  mnema_hf_cache:`n"
    $localImageGatewayBlock = @"

  local-image-gateway:
    networks: [ mnema_net ]
    build:
      context: ./scripts/local-image-gateway
      dockerfile: Dockerfile
    environment:
      IMAGE_DEFAULT_MODEL: "$localImageDefaultModel"
      HF_HOME: "/models/hf"
    ports: !override
      - "`${LOCAL_IMAGE_GATEWAY_PORT}:8092"
    volumes:
      - mnema_hf_cache:/models/hf
    healthcheck:
      test: [ "CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8092/health', timeout=5)" ]
      interval: 10s
      timeout: 5s
      retries: 60
"@
}

$overrideContent = @"
services:
  postgres:
    env_file:
      - "$EnvFileForCompose"
    ports: !override
      - "${POSTGRES_PORT}:5432"

  redis:
    ports: !override
      - "${REDIS_PORT}:6379"

  auth:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports: !override
      - "${AUTH_PORT}:8080"

  user:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports: !override
      - "${USER_PORT}:8080"

  core:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports: !override
      - "${CORE_PORT}:8080"

  media:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    depends_on:
      minio:
        condition: service_healthy
    ports: !override
      - "${MEDIA_PORT}:8080"

  import:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports: !override
      - "${IMPORT_PORT}:8080"

  ai:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
      AI_SYSTEM_MANAGED_PROVIDER_ENABLED: "true"
      AI_SYSTEM_PROVIDER_NAME: "ollama"
      AI_OLLAMA_ENABLED: "true"
      OLLAMA_BASE_URL: "http://ollama:11434"
      OPENAI_BASE_URL: "http://local-ai-gateway:8089"
      OPENAI_SYSTEM_API_KEY: ""
      OPENAI_DEFAULT_MODEL: "$openAiDefaultModel"
      OPENAI_TTS_MODEL: "$openAiTtsModel"
      OPENAI_TTS_VOICE: "`${OPENAI_TTS_VOICE}"
      OPENAI_TTS_FORMAT: "`${OPENAI_TTS_FORMAT}"
      OPENAI_STT_MODEL: "$openAiSttModel"
      OPENAI_IMAGE_MODEL: "`${OPENAI_IMAGE_MODEL}"
      OPENAI_VIDEO_MODEL: "`${OPENAI_VIDEO_MODEL}"
    ports: !override
      - "${AI_PORT}:8080"

  frontend:
    environment:
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED: "true"
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME: "ollama"
    ports: !override
      - "${FRONTEND_PORT}:80"

  minio:
    networks: [ mnema_net ]
    image: minio/minio:latest
    command: [ "server", "/data", "--console-address", ":9001" ]
    environment:
      MINIO_ROOT_USER: "$minioUser"
      MINIO_ROOT_PASSWORD: "$minioPassword"
    ports: !override
      - "${MINIO_API_PORT}:9000"
      - "${MINIO_CONSOLE_PORT}:9001"
    volumes:
      - mnema_minio_data:/data
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:9000/minio/health/live" ]
      interval: 5s
      timeout: 3s
      retries: 20

  local-ai-gateway:
    networks: [ mnema_net ]
    extra_hosts:
      - "host.docker.internal:host-gateway"
    build:
      context: ./scripts/local-ai-gateway
      dockerfile: Dockerfile
    environment:
      OLLAMA_BASE_URL: "http://ollama:11434"
      REMOTE_OPENAI_BASE_URL: "`${REMOTE_OPENAI_BASE_URL}"
      OLLAMA_AUDIO_EXPERIMENTAL: "`${OLLAMA_AUDIO_EXPERIMENTAL}"
      OLLAMA_IMAGE_EXPERIMENTAL: "`${OLLAMA_IMAGE_EXPERIMENTAL}"
      AUDIO_BASE_URL: "`${LOCAL_AUDIO_BASE_URL}"
      IMAGE_BASE_URL: "`${LOCAL_IMAGE_BASE_URL}"
      VIDEO_BASE_URL: "`${LOCAL_VIDEO_BASE_URL}"
      GATEWAY_DEFAULT_TEXT_MODEL: "$openAiDefaultModel"
      GATEWAY_DEFAULT_TTS_MODEL: "`${OPENAI_TTS_MODEL}"
      GATEWAY_DEFAULT_STT_MODEL: "`${OPENAI_STT_MODEL}"
      GATEWAY_DEFAULT_IMAGE_MODEL: "`${OPENAI_IMAGE_MODEL}"
      GATEWAY_DEFAULT_VIDEO_MODEL: "`${OPENAI_VIDEO_MODEL}"
      GATEWAY_TTS_VOICES: "`${LOCAL_TTS_VOICES}"
      GATEWAY_TIMEOUT_SECONDS: "`${LOCAL_AI_GATEWAY_TIMEOUT_SECONDS}"
    depends_on:
      ollama:
        condition: service_started
${localAiGatewayExtraDepends}
    ports: !override
      - "${LOCAL_AI_GATEWAY_PORT}:8089"
    healthcheck:
      test: [ "CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8089/health', timeout=5)" ]
      interval: 5s
      timeout: 3s
      retries: 20
${localAudioGatewayBlock}
${localImageGatewayBlock}

  minio-init:
    networks: [ mnema_net ]
    image: minio/mc:latest
    depends_on:
      minio:
        condition: service_healthy
    entrypoint:
      - /bin/sh
      - -c
      - >-
        mc alias set local http://minio:9000 $minioUser $minioPassword;
        mc mb -p local/mnema-local || true;
        mc anonymous set private local/mnema-local;

  ollama:
    networks: [ mnema_net ]
    image: ollama/ollama:latest
__OLLAMA_GPU_BLOCK__
    ports: !override
      - "${OLLAMA_PORT}:11434"
    volumes:
      - mnema_ollama_data:/root/.ollama

volumes:
  mnema_minio_data:
  mnema_ollama_data:
${extraVolumes}
"@

$ollamaGpuBlock = ''
if ($ollamaGpuEnabled -eq 'true') {
    $ollamaGpuBlock = @"
    gpus: all
    environment:
      NVIDIA_VISIBLE_DEVICES: "`${OLLAMA_VISIBLE_GPUS}"
      NVIDIA_DRIVER_CAPABILITIES: "compute,utility"
"@
}
$overrideContent = $overrideContent.Replace('__OLLAMA_GPU_BLOCK__', $ollamaGpuBlock)

Set-Content -Path $OverrideFile -Value $overrideContent -Encoding UTF8

Write-Host "[info] Generated: $EnvFile"
Write-Host "[info] Generated: $OverrideFile"
if ($DryRun) {
    Write-Host '[info] Dry run enabled (MNEMA_DRY_RUN=1), skipping docker compose up.'
    exit 0
}
Write-Host '[info] Starting containers...'

Push-Location $RootDir
& docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile up -d --build
Pop-Location

Write-Host '[ok] Mnema is running'
Write-Host "[ok] Frontend: http://localhost:$FRONTEND_PORT"
Write-Host "[ok] MinIO API: http://localhost:$MINIO_API_PORT"
Write-Host "[ok] MinIO Console: http://localhost:$MINIO_CONSOLE_PORT"
Write-Host "[ok] Ollama API: http://localhost:$OLLAMA_PORT"
Write-Host "[ok] Local AI Gateway: http://localhost:$LOCAL_AI_GATEWAY_PORT"
if ($audioBackendMode -eq 'piper') {
    Write-Host "[ok] Local Audio Gateway: http://localhost:$LOCAL_AUDIO_GATEWAY_PORT"
}
if ($imageBackendMode -eq 'diffusers') {
    Write-Host "[ok] Local Image Gateway: http://localhost:$LOCAL_IMAGE_GATEWAY_PORT"
}
Write-Host "[info] Audio backend mode: $audioBackendMode"
Write-Host "[info] Ollama GPU enabled: $ollamaGpuEnabled"
if ($ollamaGpuEnabled -eq 'true') {
    Write-Host "[info] Ollama visible GPUs: $ollamaVisibleGpus"
}
if ($audioBackendMode -eq 'piper') {
    $audioCheck = & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T local-ai-gateway python -c "import os,sys,urllib.request; u=os.getenv('AUDIO_BASE_URL','').rstrip('/'); sys.exit(0 if urllib.request.urlopen(u + '/v1/models', timeout=10).status < 500 else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '[ok] Local Piper audio backend is reachable from local-ai-gateway.'
    }
    else {
        Write-Host '[warn] Local Piper audio backend is not reachable from local-ai-gateway.'
    }
}
elseif ($audioBackendMode -eq 'custom') {
    Write-Host "[info] Audio backend URL: $localAudioBaseUrl"
    $audioCheck = & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T local-ai-gateway python -c "import os,sys,urllib.request; u=os.getenv('AUDIO_BASE_URL','').rstrip('/'); sys.exit(0 if (not u) else 0 if urllib.request.urlopen(u + '/v1/models', timeout=5).status < 500 else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '[ok] Audio backend URL is reachable from local-ai-gateway.'
    }
    else {
        Write-Host '[warn] Audio backend URL is not reachable from local-ai-gateway.'
        Write-Host '       Check LOCAL_AUDIO_BASE_URL in .env.local (for Linux host services use http://host.docker.internal:<port>).'
    }
}
if ($audioBackendMode -eq 'ollama') {
    $audioSupportCheck = & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T local-ai-gateway python -c "import json,sys,urllib.request; data=json.load(urllib.request.urlopen('http://localhost:8089/health', timeout=5)); sys.exit(0 if data.get('ollama_audio_supported') else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '[ok] Ollama /v1/audio endpoints are reachable.'
    }
    else {
        Write-Host '[warn] Ollama /v1/audio endpoints are unavailable in this runtime.'
        Write-Host '       Keep TTS model empty or switch audio backend mode to custom.'
    }
}
if ($imageBackendMode -eq 'diffusers') {
    Write-Host "[info] Image backend URL: $localImageBaseUrl"
    $imageCheck = & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T local-ai-gateway python -c "import os,sys,urllib.request; u=os.getenv('IMAGE_BASE_URL','').rstrip('/'); sys.exit(0 if urllib.request.urlopen(u + '/v1/models', timeout=10).status < 500 else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '[ok] Local Diffusers image backend is reachable from local-ai-gateway.'
    }
    else {
        Write-Host '[warn] Local Diffusers image backend is not reachable from local-ai-gateway.'
    }
}
elseif ($imageBackendMode -eq 'custom') {
    Write-Host "[info] Image backend URL: $localImageBaseUrl"
    $imageCheck = & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T local-ai-gateway python -c "import os,sys,urllib.request; u=os.getenv('IMAGE_BASE_URL','').rstrip('/'); sys.exit(0 if (not u) else 0 if urllib.request.urlopen(u + '/v1/models', timeout=5).status < 500 else 1)" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host '[ok] Image backend URL is reachable from local-ai-gateway.'
    }
    else {
        Write-Host '[warn] Image backend URL is not reachable from local-ai-gateway.'
        Write-Host '       Check LOCAL_IMAGE_BASE_URL in .env.local (for Linux host services use http://host.docker.internal:<port>).'
    }
}
$ollamaHealth = $null
try {
    $ollamaHealth = Invoke-RestMethod -Method Get -Uri "http://localhost:$OLLAMA_PORT/api/tags" -TimeoutSec 3
}
catch {
    $ollamaHealth = $null
}
if ($null -ne $ollamaHealth) {
    $recommended = $openAiDefaultModel
    $backupRecommended = Get-RecommendedOllamaBackupTextModel
    $visionRecommended = Get-RecommendedOllamaVisionModel
    Write-Host "[ok] Ollama is reachable."
    Write-Host "[info] Recommended starter text model: $recommended"
    Write-Host "[info] Optional backup text model: $backupRecommended"
    Write-Host "[info] Optional vision model (OCR/image understanding): $visionRecommended"
    Write-Host "[info] Notes:"
    Write-Host "       - Ollama OpenAI-compatible endpoints cover text/vision and experimental images."
    Write-Host "       - Audio endpoints are experimental in Ollama mode; prefer piper or custom audio backend for stability."
    $pullChoice = Read-Host 'Pull starter text model now? [y/N]'
    if ($pullChoice -match '^[Yy]$') {
        & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $recommended
    }
    $pullBackup = Read-Host "Pull backup text model ($backupRecommended) too? [y/N]"
    if ($pullBackup -match '^[Yy]$') {
        & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $backupRecommended
    }
    $pullVision = Read-Host "Pull vision model ($visionRecommended) too? [y/N]"
    if ($pullVision -match '^[Yy]$') {
        & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $visionRecommended
    }
    if ($audioBackendMode -eq 'ollama' -and -not [string]::IsNullOrWhiteSpace($openAiTtsModel)) {
        $pullTts = Read-Host "Pull TTS model ($openAiTtsModel) too? [y/N]"
        if ($pullTts -match '^[Yy]$') {
            & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $openAiTtsModel
        }
    }
    if ($audioBackendMode -eq 'ollama' -and -not [string]::IsNullOrWhiteSpace($openAiSttModel)) {
        $pullStt = Read-Host "Pull STT model ($openAiSttModel) too? [y/N]"
        if ($pullStt -match '^[Yy]$') {
            & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $openAiSttModel
        }
    }
    if ($imageBackendMode -eq 'ollama' -and -not [string]::IsNullOrWhiteSpace($openAiImageModel)) {
        $pullImage = Read-Host "Pull image model ($openAiImageModel) too? [y/N]"
        if ($pullImage -match '^[Yy]$') {
            & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $openAiImageModel
        }
    }
    Print-OllamaNextCommands
}
else {
    Write-Host '[warn] Ollama API is not reachable yet.'
    Write-Host "[warn] Later run: docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull qwen3:8b"
    Print-OllamaNextCommands
}
Write-Host "[ok] To stop: docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile down"
