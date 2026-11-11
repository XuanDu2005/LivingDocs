#!/bin/sh
# Creates a browser-test admin account with a known password.
set -e
SECRET="$APP_JWT_SECRET"
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s); exp=$((now + 3600))
h=$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
p=$(printf '%s' "{\"sub\":\"9b35bb4d-844b-4fc8-af5d-aa20ce41858f\",\"email\":\"admin@livingdocs.com\",\"iat\":$now,\"exp\":$exp,\"roles\":[\"ADMIN\",\"DEVELOPER\"]}" | b64url)
s=$(printf '%s' "$h.$p" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)
T="$h.$p.$s"

curl -s -X POST http://localhost:8080/api/v1/admin/users \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"email":"uitest-admin@example.com","displayName":"UI Test Admin","temporaryPassword":"BrowserTestPass123!","roles":["ADMIN","DEVELOPER"]}'
echo
