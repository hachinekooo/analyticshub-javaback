#!/usr/bin/env bash
set -euo pipefail

# Install AnalyticsHub nginx route fragment.
#
# This script writes only location blocks. Include the generated file from the
# active HTTPS server block for your domain:
#
#   include /etc/nginx/conf.d/analyticshub.conf;
#
# Do not include this file at nginx http level.

CONF_FILE="${CONF_FILE:-/etc/nginx/conf.d/analyticshub.conf}"
ANALYTICSHUB_WEB_ROOT="${ANALYTICSHUB_WEB_ROOT:-/usr/share/nginx/html/analyticshub-frontend/dist}"
CONF_DIR="$(dirname "$CONF_FILE")"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

require_root

regex_escape() {
  printf "%s" "$1" | sed 's/[][\/.^$*+?{}()|]/\\&/g'
}

config_files_have_active_regex() {
  local pattern="$1"
  local file
  while IFS= read -r -d '' file; do
    [[ "$file" == "$CONF_FILE" ]] && continue
    [[ "$file" == *.default || "$file" == *~ || "$file" == *.bak ]] && continue
    [[ "$file" == /etc/nginx/dist/* || "$file" == /etc/nginx/__MACOSX/* ]] && continue
    if grep -Ev '^[[:space:]]*#' "$file" | grep -Eq "$pattern"; then
      return 0
    fi
  done < <(find /etc/nginx -type f -print0 2>/dev/null)
  return 1
}

conf_file_regex="$(regex_escape "$CONF_FILE")"

if config_files_have_active_regex '^[[:space:]]*include[[:space:]]+/etc/nginx/conf\.d/\*\.conf[[:space:]]*;'; then
  cat >&2 <<EOF
Refusing to write $CONF_FILE because nginx still includes /etc/nginx/conf.d/*.conf at http level.
This file is a location fragment and must be included only inside the active HTTPS server block:

    include ${CONF_FILE};

Remove or narrow the http-level wildcard include first.
EOF
  exit 1
fi

if ! config_files_have_active_regex "^[[:space:]]*include[[:space:]]+${conf_file_regex}[[:space:]]*;"; then
  cat >&2 <<EOF
Refusing to write $CONF_FILE because it is not explicitly included by nginx.
Add this line inside the active HTTPS server block for your domain, then rerun this script:

    include ${CONF_FILE};

Do not include this location fragment at nginx http level.
EOF
  exit 1
fi

install -d -m 755 "$ANALYTICSHUB_WEB_ROOT"
install -d -m 755 "$CONF_DIR"

cat >"$CONF_FILE" <<EOF
# AnalyticsHub is mounted at /analyticshub under the active HTTPS server.
# Frontend pages are served from dist; API paths are proxied to the backend.
location = /analyticshub {
    return 301 /analyticshub/;
}

location ^~ /analyticshub/api/ {
    proxy_pass http://127.0.0.1:3001/api/;
    proxy_http_version 1.1;
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_read_timeout 30s;
    proxy_next_upstream error timeout invalid_header http_500 http_502 http_503 http_504;
}

location ^~ /analyticshub/assets/ {
    alias ${ANALYTICSHUB_WEB_ROOT%/}/assets/;
    expires 7d;
    add_header Cache-Control "public, max-age=604800";
}

location ^~ /analyticshub/ {
    alias ${ANALYTICSHUB_WEB_ROOT%/}/;
    index index.html;
    try_files \$uri \$uri/ /analyticshub/index.html;
}
EOF

nginx -t

if ! nginx -T 2>&1 | grep -Fq "# configuration file ${CONF_FILE}:"; then
  cat >&2 <<EOF
Nginx config is valid, but $CONF_FILE is not loaded.
Include it inside the active HTTPS server block for your domain:

    include ${CONF_FILE};

Do not include this location fragment at nginx http level.
EOF
  exit 1
fi

nginx -s reload
echo "Nginx route fragment installed: $CONF_FILE"
