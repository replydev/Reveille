#!/usr/bin/env bash
# Creates the release signing key and (optionally) stores it as GitHub Actions secrets.
#
#   scripts/create-release-keystore.sh [output.jks] [owner/repo]
#
# Back up the keystore and its password somewhere safe (e.g. a password manager): every future
# release must be signed with the same key, or Android refuses to install it as an update.
set -euo pipefail

KEYSTORE="${1:-$HOME/reveille-release.jks}"
REPO="${2:-replydev/Reveille}"
ALIAS="reveille"

if [[ -e "$KEYSTORE" ]]; then
  echo "Refusing to overwrite existing $KEYSTORE" >&2
  exit 1
fi

read -r -s -p "Keystore password (min 6 chars): " PASSWORD; echo
read -r -s -p "Repeat password: " PASSWORD2; echo
[[ "$PASSWORD" == "$PASSWORD2" ]] || { echo "Passwords differ" >&2; exit 1; }
[[ ${#PASSWORD} -ge 6 ]] || { echo "Password too short" >&2; exit 1; }

# PKCS12 keystores use one password for the store and the key.
keytool -genkeypair -v \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=Reveille, O=replydev"

echo
echo "Keystore written to $KEYSTORE. Certificate fingerprint:"
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" | grep -E "SHA256:"

echo
read -r -p "Store it as GitHub Actions secrets in $REPO now? [y/N] " ANSWER
if [[ "$ANSWER" =~ ^[Yy]$ ]]; then
  base64 < "$KEYSTORE" | tr -d '\n' | gh secret set REVEILLE_KEYSTORE_BASE64 --repo "$REPO"
  printf '%s' "$PASSWORD" | gh secret set REVEILLE_KEYSTORE_PASSWORD --repo "$REPO"
  printf '%s' "$ALIAS" | gh secret set REVEILLE_KEY_ALIAS --repo "$REPO"
  printf '%s' "$PASSWORD" | gh secret set REVEILLE_KEY_PASSWORD --repo "$REPO"
  echo "Secrets set: REVEILLE_KEYSTORE_BASE64, REVEILLE_KEYSTORE_PASSWORD, REVEILLE_KEY_ALIAS, REVEILLE_KEY_PASSWORD"
else
  echo "Set these secrets in $REPO yourself:"
  echo "  REVEILLE_KEYSTORE_BASE64  = base64 of $KEYSTORE"
  echo "  REVEILLE_KEYSTORE_PASSWORD, REVEILLE_KEY_PASSWORD = the password you chose"
  echo "  REVEILLE_KEY_ALIAS = $ALIAS"
fi
echo "Now back up $KEYSTORE and the password. Losing them means no more updates for installed copies."
