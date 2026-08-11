#! /bin/bash

SERVER_NAME=""

DOMAIN="chat.goorm-ktb-002.goorm.team"
S3_BUCKET_NAME="ktb-loadtest-team-2-bucket"
BASE_DIR="/home/ubuntu/images"
CURRENT_IMAGE_UUID_FILE_PATH="image.uuid"
CONFIG_FILE_PATH="static/main/config.json"

if [ -f /etc/environment ]; then
    export $(grep -v '^#' /etc/environment | xargs)
fi

INSTANCE_ID=$(ec2metadata --instance-id)
CONFIG_CONTEXT=$(curl -s "https://${DOMAIN}/${CONFIG_FILE_PATH}")

if [ -z "$CONFIG_CONTEXT" ]; then
    echo "오류: JSON 구성 파일을 가져올 수 없습니다."
    exit 1
fi

ROLE=$(echo "$CONFIG_CONTEXT" | jq -r ".\"$SERVER_NAME\"")

if [ "$ROLE" == "null" ] || [ -z "$ROLE" ]; then
    echo "오류: JSON에서 '$SERVER_NAME'에 매칭되는 역할을 찾을 수 없습니다."
    exit 1
fi

# 3. 해당 역할 내부의 모든 key-value를 Bash 변수로 변환 및 로드
# jq가 'KEY="value"' 형태의 문자열을 만들고 eval이 이를 실행합니다.
eval "$(echo "$CONFIG_CONTEXT" | jq -r ".\"$ROLE\" | to_entries | .[] | \"\(.key | ascii_upcase)=\\\"\(.value)\\\"\"")"

mkdir -p "$BASE_DIR"

CLOUDWATCH_AGENT_UUID_FILE_PATH="$BASE_DIR/cloudwatch-agent.uuid"
CURRENT_CLOUDWATCH_AGENT_UUID=$(
    cat "$CLOUDWATCH_AGENT_UUID_FILE_PATH" 2>/dev/null || echo "None"
)

if [ -n "${CLOUDWATCH_AGENT_UUID:-}" ] &&
   [ "$CURRENT_CLOUDWATCH_AGENT_UUID" != "$CLOUDWATCH_AGENT_UUID" ]; then

    if [ -z "${CLOUDWATCH_AGENT_INIT_BASH:-}" ]; then
        echo "오류: CLOUDWATCH_AGENT_INIT_BASH가 설정되지 않았습니다."
        exit 1
    fi

    echo "CloudWatch Agent 설치 또는 설정을 적용합니다."

    if ! eval "$CLOUDWATCH_AGENT_INIT_BASH"; then
        echo "오류: CloudWatch Agent 설치에 실패했습니다."
        exit 1
    fi

    echo "$CLOUDWATCH_AGENT_UUID" > "$CLOUDWATCH_AGENT_UUID_FILE_PATH"
    echo "CloudWatch Agent 적용 완료: $CLOUDWATCH_AGENT_UUID"
else
    echo "CloudWatch Agent가 이미 적용되어 있습니다."
fi

CURRENT_IMAGE_UUID=$(cat "$BASE_DIR/$CURRENT_IMAGE_UUID_FILE_PATH" 2>/dev/null || echo "None")
if [ "$CURRENT_IMAGE_UUID" == "$UUID" ]; then
    echo "이미 최신 이미지가 적용되어 있습니다. 업데이트가 필요하지 않습니다."
    exit 0
fi

aws s3 cp "s3://${S3_BUCKET_NAME}/${IMAGE_FILE_DIR_PATH}${IMAGE_FILE_NAME}" "$BASE_DIR/${IMAGE_FILE_NAME}"

docker load -i "$BASE_DIR/${IMAGE_FILE_NAME}"

docker rm -f "$(cat "$BASE_DIR/role.txt")" 2>/dev/null || true

eval "$INIT_BASH"

rm -f "$BASE_DIR/${IMAGE_FILE_NAME}"

aws ec2 create-tags --resources "$INSTANCE_ID" --tags Key=Role,Value="$ROLE" --region ap-northeast-2

mkdir -p "$BASE_DIR"
echo "$UUID" > "$BASE_DIR/$CURRENT_IMAGE_UUID_FILE_PATH"
echo "$ROLE" > "$BASE_DIR/role.txt"