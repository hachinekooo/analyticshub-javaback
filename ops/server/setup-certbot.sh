#!/usr/bin/env bash
set -euo pipefail

# Install certbot and optionally issue a certificate.

DOMAIN="${DOMAIN:-analytics.example.com}"
EXTRA_DOMAIN="${EXTRA_DOMAIN:-}"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-}"
ISSUE_CERT="${ISSUE_CERT:-false}"
STAGING="${STAGING:-false}"
CERT_DIR="${CERT_DIR:-/etc/letsencrypt/live/${DOMAIN}}"

command_exists() { command -v "$1" >/dev/null 2>&1; }

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

detect_pkg_manager() {
  if command_exists dnf; then echo dnf; return; fi
  if command_exists yum; then echo yum; return; fi
  if command_exists apt-get; then echo apt-get; return; fi
  echo ""
}

install_certbot() {
  local manager="$1"
  case "$manager" in
    dnf|yum) "$manager" install -y certbot python3-certbot-nginx || "$manager" install -y certbot ;;
    apt-get) apt-get update; apt-get install -y certbot python3-certbot-nginx ;;
    *) echo "No supported package manager found." >&2; exit 1 ;;
  esac
}

certificate_exists() {
  [[ -f "$CERT_DIR/fullchain.pem" && -f "$CERT_DIR/privkey.pem" ]]
}

print_next_step() {
  cat <<EOF
Certificate is missing: $CERT_DIR

After DNS points to this server and nginx is reachable on port 80, run:
  sudo -E env ISSUE_CERT=true CERTBOT_EMAIL=admin@example.com DOMAIN=${DOMAIN} bash ops/analyticshub web
EOF
}

issue_certificate() {
  if [[ -z "$CERTBOT_EMAIL" ]]; then
    echo "CERTBOT_EMAIL is required when ISSUE_CERT=true." >&2
    print_next_step >&2
    exit 1
  fi

  local args=(certonly --nginx --non-interactive --agree-tos --no-eff-email --email "$CERTBOT_EMAIL" -d "$DOMAIN")
  if [[ -n "$EXTRA_DOMAIN" ]]; then
    args+=(-d "$EXTRA_DOMAIN")
  fi
  if [[ "$STAGING" == "true" ]]; then
    args+=(--staging)
  fi
  certbot "${args[@]}"
}

require_root
manager="$(detect_pkg_manager)"
install_certbot "$manager"

if certificate_exists; then
  echo "Certificate exists: $CERT_DIR"
  certbot certificates -d "$DOMAIN" || true
  exit 0
fi

if [[ "$ISSUE_CERT" != "true" ]]; then
  print_next_step >&2
  exit 1
fi

issue_certificate
certificate_exists && echo "Certificate issued: $CERT_DIR"
