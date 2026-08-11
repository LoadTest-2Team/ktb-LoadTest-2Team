#!/usr/bin/env bash

set -euo pipefail

ROLE="${1:-backend}"
AWS_REGION_NAME="${AWS_REGION:-ap-northeast-2}"
LOG_GROUP_PREFIX="${LOG_GROUP_PREFIX:-/ktb/prod}"
BACKEND_HOME="${BACKEND_HOME:-/home/ubuntu/ktb-chat-backend}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_ETC="/opt/aws/amazon-cloudwatch-agent/etc"
AGENT_CTL="/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl"
CREDENTIALS_DEST="$AGENT_ETC/aws-credentials"
TEMP_PACKAGE=""

cleanup() {
  if [[ -n "$TEMP_PACKAGE" ]]; then
    rm -f "$TEMP_PACKAGE"
  fi
}
trap cleanup EXIT

if [[ "$EUID" -ne 0 ]]; then
  echo "Run as root: sudo $0 [backend|infra]" >&2
  exit 1
fi

if [[ "$ROLE" != "backend" && "$ROLE" != "infra" ]]; then
  echo "role must be backend or infra" >&2
  exit 1
fi

if [[ ! "$AWS_REGION_NAME" =~ ^[a-z]{2}(-[a-z]+)+-[0-9]$ ]]; then
  echo "invalid AWS_REGION: $AWS_REGION_NAME" >&2
  exit 1
fi

install_agent_package() {
  if [[ -x "$AGENT_CTL" ]]; then
    return
  fi

  local machine_arch package_arch platform extension package_url
  machine_arch="$(uname -m)"
  case "$machine_arch" in
    x86_64) package_arch="amd64" ;;
    aarch64|arm64) package_arch="arm64" ;;
    *) echo "unsupported architecture: $machine_arch" >&2; exit 1 ;;
  esac

  # shellcheck disable=SC1091
  source /etc/os-release
  case "${ID:-}" in
    ubuntu|debian)
      platform="${ID}"
      extension="deb"
      ;;
    amzn)
      platform="amazon_linux"
      extension="rpm"
      ;;
    rhel|rocky|almalinux|centos)
      platform="redhat"
      extension="rpm"
      ;;
    *) echo "unsupported Linux distribution: ${ID:-unknown}" >&2; exit 1 ;;
  esac

  TEMP_PACKAGE="$(mktemp "/tmp/amazon-cloudwatch-agent.XXXXXX.$extension")"
  package_url="https://amazoncloudwatch-agent.s3.amazonaws.com/$platform/$package_arch/latest/amazon-cloudwatch-agent.$extension"
  curl --fail --silent --show-error --location "$package_url" --output "$TEMP_PACKAGE"

  if [[ "$extension" == "deb" ]]; then
    dpkg -i "$TEMP_PACKAGE"
  else
    rpm -U "$TEMP_PACKAGE"
  fi
}

configure_credentials() {
  install -d -m 700 "$AGENT_ETC"

  if [[ -n "${CLOUDWATCH_CREDENTIALS_FILE:-}" ]]; then
    install -m 600 "$CLOUDWATCH_CREDENTIALS_FILE" "$CREDENTIALS_DEST"
  elif [[ ! -s "$CREDENTIALS_DEST" ]]; then
    local access_key secret_key session_token
    read -r -p "AWS access key ID: " access_key
    read -r -s -p "AWS secret access key: " secret_key
    echo
    read -r -s -p "AWS session token (empty for IAM user key): " session_token
    echo

    umask 077
    {
      printf '[AmazonCloudWatchAgent]\n'
      printf 'aws_access_key_id=%s\n' "$access_key"
      printf 'aws_secret_access_key=%s\n' "$secret_key"
      if [[ -n "$session_token" ]]; then
        printf 'aws_session_token=%s\n' "$session_token"
      fi
    } > "$CREDENTIALS_DEST"
    unset access_key secret_key session_token
  fi

  install -m 600 "$SOURCE_DIR/common-config.toml" "$AGENT_ETC/common-config.toml"
}

install_configuration() {
  local config_source config_dest local_hostname
  config_source="$SOURCE_DIR/cloudwatch-agent-$ROLE.json"
  config_dest="$AGENT_ETC/cloudwatch-agent.json"
  install -m 600 "$config_source" "$config_dest"
  sed -i \
    -e "s|__AWS_REGION__|$AWS_REGION_NAME|g" \
    -e "s|__LOG_GROUP_PREFIX__|$LOG_GROUP_PREFIX|g" \
    -e "s|__BACKEND_HOME__|$BACKEND_HOME|g" \
    "$config_dest"

  if [[ "$ROLE" == "backend" ]]; then
    local_hostname="$(hostname -s | tr -cd 'A-Za-z0-9._-')"
    local_hostname="${local_hostname:-unknown}"
    install -m 644 "$SOURCE_DIR/prometheus.yaml" "$AGENT_ETC/prometheus.yaml"
    sed -i "s|__HOSTNAME__|$local_hostname|g" "$AGENT_ETC/prometheus.yaml"
  fi
}

install_agent_package
configure_credentials
install_configuration

"$AGENT_CTL" \
  -a fetch-config \
  -m ec2 \
  -s \
  -c "file:$AGENT_ETC/cloudwatch-agent.json"

"$AGENT_CTL" -m ec2 -a status
