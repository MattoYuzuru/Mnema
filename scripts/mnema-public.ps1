$ErrorActionPreference = 'Stop'

$RootDir = (Resolve-Path "$PSScriptRoot/..").Path
$WorkDir = Join-Path $RootDir '.mnema'
$EnvFile = Join-Path $RootDir '.env.public'
$EnvFileForCompose = $EnvFile -replace '\\', '/'
$OverrideFile = Join-Path $WorkDir 'compose.public.yml'
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

if (-not (Test-Command docker)) {
    throw '[error] Docker is not installed or not in PATH.'
}
$composeVersion = & docker compose version 2>$null
if ($LASTEXITCODE -ne 0) {
    throw '[error] Docker Compose plugin is not available (docker compose).'
}

Write-Host '== Mnema public self-host init =='
$webDomain = Prompt-WithDefault -Prompt 'Public web domain (without protocol)' -Default 'mnema.local'
$authDomain = Prompt-WithDefault -Prompt 'Auth domain (without protocol)' -Default "auth.$webDomain"
$webOrigin = "https://$webDomain"
$authIssuer = "https://$authDomain"

$federatedRaw = Read-Host 'Enable federated OAuth providers? [y/N]'
$authFederatedEnabled = if ($federatedRaw -match '^[Yy]$') { 'true' } else { 'false' }

$dbUser = Prompt-WithDefault -Prompt 'Postgres user' -Default 'mnema'
$dbPassword = Prompt-WithDefault -Prompt 'Postgres password' -Default 'mnema_public'
$dbName = Prompt-WithDefault -Prompt 'Postgres database' -Default 'mnema'
$minioUser = Prompt-WithDefault -Prompt 'MinIO root user' -Default 'mnema'
$minioPassword = Prompt-WithDefault -Prompt 'MinIO root password' -Default 'mnema_public_minio'

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

$authSwaggerRedirects = "$webOrigin/api/user/swagger-ui/oauth2-redirect.html,$webOrigin/api/core/swagger-ui/oauth2-redirect.html,$webOrigin/api/media/swagger-ui/oauth2-redirect.html,$webOrigin/api/import/swagger-ui/oauth2-redirect.html,$webOrigin/api/ai/swagger-ui/oauth2-redirect.html"

$envContent = @"
APP_ENV=public
POSTGRES_DB=$dbName
POSTGRES_USER=$dbUser
POSTGRES_PASSWORD=$dbPassword
POSTGRES_PORT=$POSTGRES_PORT
SPRING_DATASOURCE_USERNAME=$dbUser
SPRING_DATASOURCE_PASSWORD=$dbPassword

PUBLIC_WEB_ORIGIN=$webOrigin
AUTH_ISSUER=$authIssuer
AUTH_ISSUER_URI=$authIssuer
AUTH_LOGOUT_DEFAULT_REDIRECT=$webOrigin/
AUTH_WEB_REDIRECT_URIS=$webOrigin/
AUTH_SWAGGER_REDIRECT_URIS=$authSwaggerRedirects
AUTH_FEDERATED_ENABLED=$authFederatedEnabled
AUTH_REQUIRE_EMAIL_VERIFICATION=$authFederatedEnabled

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
AWS_BUCKET_NAME=mnema-public
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
OLLAMA_BASE_URL=http://ollama:11434
OPENAI_BASE_URL=http://ollama:11434/v1
OPENAI_SYSTEM_API_KEY=
OPENAI_DEFAULT_MODEL=qwen3:8b
AI_VAULT_MASTER_KEY=$(New-HexSecret -ByteCount 32)
AI_VAULT_KEY_ID=public-v1

REDIS_HOST=redis
REDIS_PORT=6379
SPRING_CACHE_TYPE=redis

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
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "$AUTH_PORT:8080"

  user:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "$USER_PORT:8080"

  core:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "$CORE_PORT:8080"

  media:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    depends_on:
      minio:
        condition: service_healthy
    ports:
      - "$MEDIA_PORT:8080"

  import:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "$IMPORT_PORT:8080"

  ai:
    environment:
      SPRING_PROFILES_ACTIVE: prod,selfhost-public
    ports:
      - "$AI_PORT:8080"

  frontend:
    environment:
      MNEMA_AUTH_SERVER_URL: $authIssuer
      MNEMA_FEATURE_FEDERATED_AUTH_ENABLED: $authFederatedEnabled
      MNEMA_FEATURE_SHOW_EMAIL_VERIFICATION_WARNING: $authFederatedEnabled
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_ENABLED: true
      MNEMA_FEATURE_AI_SYSTEM_PROVIDER_NAME: ollama
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
        mc mb -p local/mnema-public || true;
        mc anonymous set private local/mnema-public;

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

Write-Host "[ok] Generated $EnvFile"
Write-Host "[ok] Generated $OverrideFile"

$startCmd = "docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile up -d --build"
Write-Host '[next] Start stack:'
Write-Host "  $startCmd"

if ($DryRun) {
    Write-Host '[dry-run] done (compose up skipped).'
    exit 0
}

$startNow = Read-Host 'Start stack now? [Y/n]'
if ([string]::IsNullOrWhiteSpace($startNow) -or $startNow -match '^[Yy]$') {
    Push-Location $RootDir
    try {
        & docker compose --env-file $EnvFile -f docker-compose.yml -f $OverrideFile up -d --build
    }
    finally {
        Pop-Location
    }
    Write-Host '[done] Mnema public stack is running.'
    Write-Host "[done] Frontend: http://localhost:$FRONTEND_PORT"
}
else {
    Write-Host '[info] Skipped compose up.'
}

Write-Host '[next] Public self-host runbook: docs/deploy/selfhost-public.md'
