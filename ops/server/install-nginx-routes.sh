#!/usr/bin/env bash
set -euo pipefail

# Install AnalyticsHub nginx routes.

DOMAIN="${DOMAIN:-analytics.example.com}"
EXTRA_DOMAIN="${EXTRA_DOMAIN:-}"
CONF_FILE="${CONF_FILE:-/etc/nginx/conf.d/analyticshub.conf}"
SSL_CERTIFICATE="${SSL_CERTIFICATE:-/etc/letsencrypt/live/${DOMAIN}/fullchain.pem}"
SSL_CERTIFICATE_KEY="${SSL_CERTIFICATE_KEY:-/etc/letsencrypt/live/${DOMAIN}/privkey.pem}"
SSL_OPTIONS="${SSL_OPTIONS:-/etc/letsencrypt/options-ssl-nginx.conf}"
SSL_DHPARAM="${SSL_DHPARAM:-/etc/letsencrypt/ssl-dhparams.pem}"
ANALYTICSHUB_WEB_ROOT="${ANALYTICSHUB_WEB_ROOT:-/usr/share/nginx/html/analyticshub-frontend/dist}"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

require_root

install -d -m 755 "$ANALYTICSHUB_WEB_ROOT"

for ssl_file in "$SSL_CERTIFICATE" "$SSL_CERTIFICATE_KEY"; do
  if [[ ! -f "$ssl_file" ]]; then
    echo "Missing nginx SSL file: $ssl_file" >&2
    echo "Run setup-certbot.sh first, or override SSL_CERTIFICATE/SSL_CERTIFICATE_KEY." >&2
    exit 1
  fi
done

SSL_OPTIONS_LINE=""
if [[ -f "$SSL_OPTIONS" ]]; then
  SSL_OPTIONS_LINE="    include ${SSL_OPTIONS};"
fi

SSL_DHPARAM_LINE=""
if [[ -f "$SSL_DHPARAM" ]]; then
  SSL_DHPARAM_LINE="    ssl_dhparam ${SSL_DHPARAM};"
fi

SERVER_NAMES="$DOMAIN"
if [[ -n "$EXTRA_DOMAIN" ]]; then
  SERVER_NAMES="$SERVER_NAMES $EXTRA_DOMAIN"
fi

cat >"$CONF_FILE" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name ${SERVER_NAMES};
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${SERVER_NAMES};

    ssl_certificate ${SSL_CERTIFICATE};
    ssl_certificate_key ${SSL_CERTIFICATE_KEY};
${SSL_OPTIONS_LINE}
${SSL_DHPARAM_LINE}

    charset utf-8;

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
    }

    location ^~ /analyticshub/v1/ {
        proxy_pass http://127.0.0.1:3001/api/v1/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
    }

    location ^~ /analyticshub/admin/ {
        proxy_pass http://127.0.0.1:3001/api/admin/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
    }

    location ^~ /analyticshub/public/ {
        proxy_pass http://127.0.0.1:3001/api/public/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
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
}
EOF

nginx -t
systemctl reload nginx
echo "Nginx routes installed: $CONF_FILE"
