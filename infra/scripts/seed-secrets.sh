#!/usr/bin/env bash
# Fills in an environment's application secret (qualityos-<env>/app in Secrets Manager).
#
# Generated values (database passwords, the token signing key pair, the authenticator sealing key) are made the first
# time and kept after that: running again never replaces them, because changing one has steps of its own
# (docs/DEPLOYMENT.md). Provider keys are taken from the environment when set, and kept otherwise:
#
#   SENDGRID_API_KEY=... RAZORPAY_KEY_ID=... RAZORPAY_KEY_SECRET=... RAZORPAY_WEBHOOK_SECRET=... \
#     infra/scripts/seed-secrets.sh uat
#
# Values never touch the command line or a file outside a private temporary directory, and are never printed.
set -euo pipefail

env="${1:?usage: seed-secrets.sh <environment>}"
region="${AWS_REGION:-ap-south-1}"
secret="qualityos-${env}/app"

umask 077
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

aws secretsmanager get-secret-value --region "$region" --secret-id "$secret" \
  --query SecretString --output text >"$work/current.json"
cp "$work/current.json" "$work/next.json"

is_empty() { [ -z "$(jq -r --arg k "$1" '.[$k] // ""' "$work/next.json")" ]; }

set_from_file() {
  jq --arg k "$1" --rawfile v "$2" '.[$k] = $v' "$work/next.json" >"$work/tmp.json"
  mv "$work/tmp.json" "$work/next.json"
}

generate() {
  local key="$1"
  shift
  if is_empty "$key"; then
    "$@" >"$work/value"
    # Without the trailing newline openssl adds.
    printf '%s' "$(cat "$work/value")" >"$work/value.trimmed"
    set_from_file "$key" "$work/value.trimmed"
    echo "generated  $key"
  else
    echo "kept       $key"
  fi
}

generate DB_APP_PASSWORD openssl rand -hex 32
generate DB_MIGRATION_PASSWORD openssl rand -hex 32
generate SECOND_FACTOR_KEY openssl rand -base64 32

# The two halves of the signing key are made together, or not at all.
if is_empty JWT_PRIVATE_KEY && is_empty JWT_PUBLIC_KEY; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$work/jwt.pem" 2>/dev/null
  openssl pkey -in "$work/jwt.pem" -pubout -out "$work/jwt.pub"
  set_from_file JWT_PRIVATE_KEY "$work/jwt.pem"
  set_from_file JWT_PUBLIC_KEY "$work/jwt.pub"
  echo "generated  JWT_PRIVATE_KEY, JWT_PUBLIC_KEY"
elif is_empty JWT_PRIVATE_KEY || is_empty JWT_PUBLIC_KEY; then
  echo "Only one half of the signing key pair is set. Set both, or clear both to generate a new pair." >&2
  exit 1
else
  echo "kept       JWT_PRIVATE_KEY, JWT_PUBLIC_KEY"
fi

for key in SENDGRID_API_KEY RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET RAZORPAY_WEBHOOK_SECRET; do
  if [ -n "${!key:-}" ]; then
    printf '%s' "${!key}" >"$work/value"
    set_from_file "$key" "$work/value"
    echo "set        $key"
  elif is_empty "$key"; then
    echo "empty      $key"
  else
    echo "kept       $key"
  fi
done

if cmp -s <(jq -S . "$work/current.json") <(jq -S . "$work/next.json"); then
  echo "Nothing changed."
else
  aws secretsmanager put-secret-value --region "$region" --secret-id "$secret" \
    --secret-string "file://$work/next.json" >/dev/null
  echo "Saved a new version of $secret. Running instances read it when they next start."
fi

if is_empty SENDGRID_API_KEY; then
  echo "Warning: SENDGRID_API_KEY is empty, and $env sends email through SendGrid, so the backend will not start." >&2
fi
