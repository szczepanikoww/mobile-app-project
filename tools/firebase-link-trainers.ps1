<#
.SYNOPSIS
  Tworzy konta Firebase dla trenerów (i opcjonalnie klientów) z danych demo w lokalnej bazie i podpina je pod
  istniejące rekordy, żeby można było się na nich zalogować z prawdziwym Firebase (docs/plans/firebase-auth.md).

.DESCRIPTION
  Dla każdego konta z bazy bez adresu e-mail (dane demo: demo-trainer, tr-anna, ...):
    1. tworzy użytkownika w Firebase: <auth_subject>@example.com z hasłem -Password,
    2. ustawia w identity.app_user auth_subject = UID z Firebase i ten e-mail.
  Dane trenera (klienci, sesje, cennik) zostają, bo są powiązane z app_user.id, a nie z Firebase.

  Bez -Apply skrypt tylko pokazuje, co zrobi. Można go uruchomić ponownie: konta już podpięte mają e-mail i są
  pomijane, a istniejący użytkownik Firebase (np. po przerwanym uruchomieniu) zostaje użyty ponownie.

  Tylko dla lokalnej bazy deweloperskiej z danymi demo (kontener server-postgres-1). Po podpięciu konta demo
  logują się przez Firebase (e-mail i hasło z -Password); cofnięcie: zresetuj bazę
  (docker compose -f server/docker-compose.yml down -v) i uruchom serwer ponownie.

.EXAMPLE
  ./tools/firebase-link-trainers.ps1                     # podgląd
  ./tools/firebase-link-trainers.ps1 -Apply              # wykonanie
  ./tools/firebase-link-trainers.ps1 -Apply -IncludeClients -Password "InneHaslo123"
#>
param(
    [switch]$Apply,
    [switch]$IncludeClients,
    [string]$Password = "Haslo12345",
    [string]$Container = "server-postgres-1",
    [string]$Database = "trainer"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

if ($Password.Length -lt 8) { throw "Haslo musi miec co najmniej 8 znakow." }

# Web API Key z local.properties (ten sam, którego używa aplikacja).
$propsFile = Join-Path $root "local.properties"
if (-not (Test-Path $propsFile)) { throw "Brak pliku $propsFile" }
$key = ((Get-Content $propsFile) -match '^firebase\.apiKey=') -replace '^firebase\.apiKey=', ''
if ([string]::IsNullOrWhiteSpace($key)) { throw "Brak firebase.apiKey w local.properties." }
$key = $key.Trim()

function Invoke-Sql([string]$sql) {
    $out = & docker exec $Container psql -U postgres -d $Database -At -F "|" -v ON_ERROR_STOP=1 -c $sql
    if ($LASTEXITCODE -ne 0) { throw "Blad SQL (kontener $Container dziala?)" }
    return $out
}

function Invoke-Firebase([string]$method, [hashtable]$body) {
    $uri = "https://identitytoolkit.googleapis.com/v1/accounts:${method}?key=$key"
    try {
        return Invoke-RestMethod -Method Post -Uri $uri -ContentType "application/json" -Body ($body | ConvertTo-Json)
    } catch {
        $message = $null
        $response = $_.Exception.Response
        if ($response) {
            try {
                $reader = New-Object IO.StreamReader($response.GetResponseStream())
                $message = (($reader.ReadToEnd() | ConvertFrom-Json).error.message -split ' ')[0]
            } catch { }
        }
        if (-not $message) { $message = $_.Exception.Message }
        throw "FIREBASE:$message"
    }
}

# Konta do podpięcia: bez e-maila (dane demo), nieusunięte, z rolą trenera (lub klienta z -IncludeClients).
$roleFilter = "EXISTS (SELECT 1 FROM identity.trainer_profile t WHERE t.user_id = u.id)"
if ($IncludeClients) { $roleFilter = "($roleFilter OR EXISTS (SELECT 1 FROM identity.client_profile c WHERE c.user_id = u.id))" }
$rows = Invoke-Sql @"
SELECT u.auth_subject, u.display_name,
       CASE WHEN EXISTS (SELECT 1 FROM identity.trainer_profile t WHERE t.user_id = u.id) THEN 'trener' ELSE '' END ||
       CASE WHEN EXISTS (SELECT 1 FROM identity.client_profile c WHERE c.user_id = u.id) THEN ' klient' ELSE '' END
FROM identity.app_user u
WHERE u.email IS NULL AND u.deleted_at IS NULL AND $roleFilter
ORDER BY u.auth_subject;
"@

$accounts = @($rows | Where-Object { $_ } | ForEach-Object {
    $f = $_ -split '\|'
    [pscustomobject]@{ Subject = $f[0]; Name = $f[1]; Roles = $f[2].Trim(); Email = ($f[0].ToLower() + "@example.com") }
})

if ($accounts.Count -eq 0) { "Brak kont do podpiecia (wszystkie maja juz e-mail)."; return }

if (-not $Apply) {
    "PODGLAD - nic nie zostalo zmienione. Uruchom z -Apply, aby wykonac."
    ""
    $accounts | Format-Table @{ n = "E-mail (login)"; e = { $_.Email } }, @{ n = "Haslo"; e = { $Password } }, Name, Roles, Subject -AutoSize
    return
}

$results = foreach ($a in $accounts) {
    try {
        # 1. Użytkownik w Firebase (albo istniejący, jeśli poprzednie uruchomienie zostało przerwane).
        try {
            $fb = Invoke-Firebase "signUp" @{ email = $a.Email; password = $Password; displayName = $a.Name; returnSecureToken = $true }
        } catch {
            if ("$_" -notlike "FIREBASE:EMAIL_EXISTS*") { throw }
            $fb = Invoke-Firebase "signInWithPassword" @{ email = $a.Email; password = $Password; returnSecureToken = $true }
        }
        $uid = $fb.localId
        if ($uid -notmatch '^[A-Za-z0-9_-]{1,128}$') { throw "Nieoczekiwany UID z Firebase." }

        # 2. Podpięcie rekordu w bazie. Warunek email IS NULL chroni przed podwójnym podpięciem.
        $subject = $a.Subject.Replace("'", "''")
        $email = $a.Email.Replace("'", "''")
        $updated = Invoke-Sql "UPDATE identity.app_user SET auth_subject = '$uid', email = '$email' WHERE auth_subject = '$subject' AND email IS NULL;"
        $status = if ("$updated" -match 'UPDATE 1') { "podpiete" } else { "pominiete (zmienione w miedzyczasie)" }
        [pscustomobject]@{ Email = $a.Email; Name = $a.Name; Roles = $a.Roles; Status = $status }
    } catch {
        $reason = "$_" -replace '^FIREBASE:', 'Firebase: '
        if ($reason -like "*INVALID_LOGIN_CREDENTIALS*" -or $reason -like "*INVALID_PASSWORD*") {
            $reason = "e-mail jest juz w Firebase z innym haslem"
        }
        [pscustomobject]@{ Email = $a.Email; Name = $a.Name; Roles = $a.Roles; Status = "BLAD: $reason" }
    }
}

$results | Format-Table Email, Name, Roles, Status -AutoSize
"Haslo do wszystkich podpietych kont: $Password"
"Konta sa widoczne w konsoli Firebase: Authentication -> Users."
