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

function New-HexSecret {
    param([int]$ByteCount)
    $bytes = New-Object byte[] $ByteCount
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
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

if (-not (Test-Command docker)) {
    throw '[error] Docker is not installed or not in PATH.'
}

$composeVersion = & docker compose version 2>$null
if ($LASTEXITCODE -ne 0) {
    throw '[error] Docker Compose plugin is not available (docker compose).'
}

Write-Host '== Mnema local bootstrap =='
Write-Host 'The script will detect busy ports and move to the next free port automatically.'

$dbUser = Prompt-WithDefault -Prompt 'Postgres user' -Default 'mnema'
$dbName = Prompt-WithDefault -Prompt 'Postgres database' -Default 'mnema'
$dbPassword = Prompt-WithDefault -Prompt 'Postgres password' -Default 'mnema_local'
$minioUser = Prompt-WithDefault -Prompt 'MinIO root user' -Default 'mnema'
$minioPassword = Prompt-WithDefault -Prompt 'MinIO root password' -Default 'mnema_minio_local'

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

$envContent = @"
POSTGRES_DB=$dbName
POSTGRES_USER=$dbUser
POSTGRES_PASSWORD=$dbPassword
POSTGRES_PORT=$POSTGRES_PORT

SPRING_DATASOURCE_USERNAME=$dbUser
SPRING_DATASOURCE_PASSWORD=$dbPassword

AUTH_ISSUER=http://localhost:$AUTH_PORT
AUTH_ISSUER_URI=http://localhost:$AUTH_PORT

GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GH_CLIENT_ID=
GH_CLIENT_SECRET=
YANDEX_CLIENT_ID=
YANDEX_CLIENT_SECRET=

TURNSTILE_SITE_KEY=
TURNSTILE_SECRET_KEY=

MEDIA_INTERNAL_TOKEN=$(New-HexSecret -ByteCount 24)

AWS_REGION=us-east-1
AWS_BUCKET_NAME=mnema-local
AWS_ENDPOINT=http://minio:9000
AWS_PATH_STYLE_ACCESS=true
AWS_ACCESS_KEY_ID=$minioUser
AWS_SECRET_ACCESS_KEY=$minioPassword

CORE_BASE_URL=http://core:8080/api/core
MEDIA_BASE_URL=http://media:8080/api/media

AI_PROVIDER=openai
AI_SYSTEM_MANAGED_PROVIDER_ENABLED=true
AI_SYSTEM_PROVIDER_NAME=ollama
AI_OLLAMA_ENABLED=true
AI_VAULT_MASTER_KEY=$(New-HexSecret -ByteCount 32)
AI_VAULT_KEY_ID=local-v1

OPENAI_BASE_URL=http://ollama:11434/v1
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_DEFAULT_MODEL=qwen3:8b
OPENAI_TTS_MODEL=
OPENAI_STT_MODEL=

GEMINI_BASE_URL=
GEMINI_DEFAULT_MODEL=

ANTHROPIC_BASE_URL=
ANTHROPIC_API_VERSION=2023-06-01
ANTHROPIC_DEFAULT_MODEL=

QWEN_BASE_URL=
QWEN_DASHSCOPE_BASE_URL=

GROK_BASE_URL=

SPRING_CACHE_TYPE=redis
REDIS_HOST=redis
REDIS_PORT=6379
APP_ENV=local

MINIO_ROOT_USER=$minioUser
MINIO_ROOT_PASSWORD=$minioPassword
"@

Set-Content -Path $EnvFile -Value $envContent -Encoding UTF8

$overrideContent = @"
services:
  postgres:
    env_file:
      - "$EnvFileForCompose"
    ports:
      - "$POSTGRES_PORT:5432"

  redis:
    ports:
      - "$REDIS_PORT:6379"

  auth:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "$AUTH_PORT:8080"

  user:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "$USER_PORT:8080"

  core:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "$CORE_PORT:8080"

  media:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    depends_on:
      minio:
        condition: service_healthy
    ports:
      - "$MEDIA_PORT:8080"

  import:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "$IMPORT_PORT:8080"

  ai:
    environment:
      SPRING_PROFILES_ACTIVE: dev,selfhost-local
    ports:
      - "$AI_PORT:8080"

  frontend:
    ports:
      - "$FRONTEND_PORT:80"

  minio:
    networks: [ mnema_net ]
    image: minio/minio:latest
    command: [ "server", "/data", "--console-address", ":9001" ]
    environment:
      MINIO_ROOT_USER: "$minioUser"
      MINIO_ROOT_PASSWORD: "$minioPassword"
    ports:
      - "$MINIO_API_PORT:9000"
      - "$MINIO_CONSOLE_PORT:9001"
    volumes:
      - mnema_minio_data:/data
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:9000/minio/health/live" ]
      interval: 5s
      timeout: 3s
      retries: 20

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
    ports:
      - "$OLLAMA_PORT:11434"
    volumes:
      - mnema_ollama_data:/root/.ollama

volumes:
  mnema_minio_data:
  mnema_ollama_data:
"@

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
$ollamaHealth = $null
try {
    $ollamaHealth = Invoke-RestMethod -Method Get -Uri "http://localhost:$OLLAMA_PORT/api/tags" -TimeoutSec 3
}
catch {
    $ollamaHealth = $null
}
if ($null -ne $ollamaHealth) {
    $recommended = Get-RecommendedOllamaModel
    Write-Host "[ok] Ollama is reachable."
    Write-Host "[info] Recommended starter model for this host: $recommended"
    $pullChoice = Read-Host 'Pull recommended model now? [y/N]'
    if ($pullChoice -match '^[Yy]$') {
        & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull $recommended
    }
}
else {
    Write-Host '[warn] Ollama API is not reachable yet.'
    Write-Host "[warn] Later run: docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile exec -T ollama ollama pull qwen3:8b"
}
Write-Host "[ok] To stop: docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile down"
