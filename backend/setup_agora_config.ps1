<#
.SYNOPSIS
  Configures Agora-Backup secrets, the database Vault webhook secret, and Auth settings.

.DESCRIPTION
  Required inputs may be supplied as parameters or environment variables. This script never
  stores credentials in the repository. It requires the Supabase CLI, psql, and a personal
  Supabase access token with project configuration permissions.

.EXAMPLE
  $env:SUPABASE_ACCESS_TOKEN = '<personal-access-token>'
  $env:SUPABASE_DB_URL = '<direct-postgres-uri>'
  $env:AGORA_PRODUCTION_SITE_URL = 'https://auth-agora.info'
  .\setup_agora_config.ps1 -FirebaseServiceAccountPath C:\secure\firebase-service-account.json `
    -WebhookSecret '<a-long-random-secret>' `
    -GoogleClientId '<google-web-client-id>' `
    -GoogleClientSecret '<google-client-secret>'
#>

[CmdletBinding()]
param(
    [string]$ProjectRef = 'sepvcatdqrnzjuvxabzh',
    [string]$FirebaseServiceAccountPath = $env:FIREBASE_SERVICE_ACCOUNT_PATH,
    [string]$WebhookSecret = $env:WEBHOOK_SECRET,
    [string]$ProductionSiteUrl = $env:AGORA_PRODUCTION_SITE_URL,
    [string]$GoogleClientId = $env:GOOGLE_OAUTH_CLIENT_ID,
    [string]$GoogleClientSecret = $env:GOOGLE_OAUTH_CLIENT_SECRET,
    [string]$SupabaseAccessToken = $env:SUPABASE_ACCESS_TOKEN,
    [string]$SupabaseDbUrl = $env:SUPABASE_DB_URL
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Value {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Missing $Name. Supply it as a parameter or environment variable."
    }
}

Require-Value 'SUPABASE_ACCESS_TOKEN' $SupabaseAccessToken
Require-Value 'SUPABASE_DB_URL' $SupabaseDbUrl
Require-Value 'ProductionSiteUrl / AGORA_PRODUCTION_SITE_URL' $ProductionSiteUrl
Require-Value 'GoogleClientId / GOOGLE_OAUTH_CLIENT_ID' $GoogleClientId
Require-Value 'GoogleClientSecret / GOOGLE_OAUTH_CLIENT_SECRET' $GoogleClientSecret
Require-Value 'WebhookSecret / WEBHOOK_SECRET' $WebhookSecret

if (-not (Get-Command npx -ErrorAction SilentlyContinue)) {
    throw 'npx is required. Install Node.js LTS first.'
}
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'psql is required to create the database Vault secret. Install PostgreSQL client tools first.'
}

if (-not [string]::IsNullOrWhiteSpace($FirebaseServiceAccountPath)) {
    if (-not (Test-Path -LiteralPath $FirebaseServiceAccountPath -PathType Leaf)) {
        throw "Firebase service account file was not found: $FirebaseServiceAccountPath"
    }
    $FirebaseServiceAccount = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $FirebaseServiceAccountPath))
} else {
    $FirebaseServiceAccount = $env:FIREBASE_SERVICE_ACCOUNT
}
Require-Value 'FirebaseServiceAccountPath / FIREBASE_SERVICE_ACCOUNT' $FirebaseServiceAccount

try {
    $serviceAccountJson = $FirebaseServiceAccount | ConvertFrom-Json
} catch {
    throw 'Firebase service account content is not valid JSON.'
}

# Catch the classic rotation mistake before the secret ever reaches Supabase:
# a key belonging to a different Firebase project, or a truncated JSON.
foreach ($field in @('project_id', 'private_key', 'private_key_id', 'client_email')) {
    if (-not ($serviceAccountJson.PSObject.Properties.Name -contains $field) -or
        [string]::IsNullOrWhiteSpace($serviceAccountJson.$field)) {
        throw "Firebase service account JSON is missing the '$field' field."
    }
}
if ($serviceAccountJson.project_id -ne 'agora-application') {
    throw "Firebase service account belongs to project '$($serviceAccountJson.project_id)'; the push pipeline targets 'agora-application'."
}
if (-not $serviceAccountJson.private_key.StartsWith('-----BEGIN PRIVATE KEY-----')) {
    throw 'Firebase service account private_key is not an unencrypted PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----").'
}

Write-Host "Firebase service account validated: project '$($serviceAccountJson.project_id)', key id '$($serviceAccountJson.private_key_id)'." -ForegroundColor Cyan

# Do not pass JSON/secret values as command-line arguments. The temporary env file is deleted
# immediately after the Supabase CLI consumes it.
$secretsEnvFile = [System.IO.Path]::GetTempFileName()
$vaultSqlFile = [System.IO.Path]::GetTempFileName()
$previousSupabaseAccessToken = $env:SUPABASE_ACCESS_TOKEN

try {
    # The CLI reads the access token from its environment. Keep this scoped to the script process.
    $env:SUPABASE_ACCESS_TOKEN = $SupabaseAccessToken
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    $secretsContent = "FIREBASE_SERVICE_ACCOUNT=$($FirebaseServiceAccount.Trim())`nWEBHOOK_SECRET=$WebhookSecret`n"
    [System.IO.File]::WriteAllText($secretsEnvFile, $secretsContent, $utf8NoBom)

    & npx supabase secrets set --project-ref $ProjectRef --env-file $secretsEnvFile
    if ($LASTEXITCODE -ne 0) { throw "supabase secrets set failed with exit code $LASTEXITCODE" }

    # The migration reads this value at trigger execution time. Deleting then recreating makes
    # reruns and deliberate secret rotation deterministic.
    $escapedWebhookSecret = $WebhookSecret.Replace("'", "''")
    $vaultSql = @"
CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;
DELETE FROM vault.secrets WHERE name = 'webhook_secret';
SELECT vault.create_secret('$escapedWebhookSecret', 'webhook_secret', 'Agora database-to-Edge-Function webhook secret');
"@
    [System.IO.File]::WriteAllText($vaultSqlFile, $vaultSql, $utf8NoBom)

    & psql --dbname $SupabaseDbUrl --set ON_ERROR_STOP=1 --file $vaultSqlFile
    if ($LASTEXITCODE -ne 0) { throw "Vault secret setup failed with exit code $LASTEXITCODE" }

    $authConfig = @{
        site_url                    = $ProductionSiteUrl
        uri_allow_list              = @('agora://**', 'https://auth-agora.info/**')
        external_google_enabled     = $true
        external_google_client_id   = $GoogleClientId
        external_google_secret      = $GoogleClientSecret
    } | ConvertTo-Json -Depth 4

    $headers = @{ Authorization = "Bearer $SupabaseAccessToken" }
    $authConfigUri = "https://api.supabase.com/v1/projects/$ProjectRef/config/auth"
    Invoke-RestMethod -Method Patch -Uri $authConfigUri -Headers $headers `
        -ContentType 'application/json' -Body $authConfig | Out-Null

    Write-Host "Agora-Backup secrets, Vault webhook secret, and Auth configuration updated." -ForegroundColor Green
    Write-Host "Next: from backend/, run 'npx supabase db push' and deploy both Edge Functions." -ForegroundColor Yellow
}
finally {
    $env:SUPABASE_ACCESS_TOKEN = $previousSupabaseAccessToken
    Remove-Item -LiteralPath $secretsEnvFile, $vaultSqlFile -Force -ErrorAction SilentlyContinue
}
