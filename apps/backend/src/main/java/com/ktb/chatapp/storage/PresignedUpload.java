package com.ktb.chatapp.storage;

import java.net.URI;
import java.util.Map;

/**
 * 클라이언트가 스토리지로 직접 PUT할 때 사용할 URL과, 서명에 포함되어 반드시 그대로 실어야 하는 헤더.
 */
public record PresignedUpload(URI url, Map<String, String> requiredHeaders) {
}
