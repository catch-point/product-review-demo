#!/usr/bin/env bash
# Realm settings that realm/reviews-realm.json cannot hold. Compose
# runs this (service keycloak-config) each time Keycloak starts; it is safe to
# run again.
set -euo pipefail

kcadm=/opt/keycloak/bin/kcadm.sh
config=$(mktemp)
trap 'rm -f "$config"' EXIT

$kcadm config credentials --config "$config" --server "${KEYCLOAK_INTERNAL_URL:-http://keycloak:8080}" \
  --realm master --user "${KC_BOOTSTRAP_ADMIN_USERNAME:?}" --password "${KC_BOOTSTRAP_ADMIN_PASSWORD:?}"

# People choose their role (reviewer or owner) when they register, and must
# not change it afterwards. The user profile lets users edit "role" so that the
# register page can set it; taking account management away from them keeps it
# from changing later (the account console and its REST API). Importing the
# realm adds manage-account to its default roles, so it is removed here.
$kcadm remove-roles --config "$config" -r reviews --rname default-roles-reviews \
  --cclientid account --rolename manage-account --rolename manage-account-links

# Site administrators: the "admin" realm role, which reaches the storage
# service in the access token's "roles" claim. It is kept for future use: no
# operation allows administrators more yet. Only accounts that exist
# can be granted it, so run this again (scripts/stack.sh identity admin EMAIL)
# once a new administrator has registered. Revoke it in the admin console.
IFS=, read -ra admins <<<"${REVIEWS_ADMIN_EMAILS:-}"
for email in "${admins[@]}"; do
  email=$(tr -d '[:space:]' <<<"$email" | tr '[:upper:]' '[:lower:]')
  [[ -n $email ]] || continue
  if [[ $($kcadm get users --config "$config" -r reviews -q username="$email" -q exact=true --fields id) == *'"id"'* ]]; then
    $kcadm add-roles --config "$config" -r reviews --uusername "$email" --rolename admin
    echo "keycloak-config: $email is an administrator"
  else
    echo "keycloak-config: $email has not registered yet; run this again once they have" >&2
  fi
done

echo "keycloak-config: the reviews realm is configured"
