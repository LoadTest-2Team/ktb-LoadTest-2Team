package com.ktb.chatapp.storage;

import java.net.URI;
import java.util.Map;

/** 스토리지 구현체가 발급한 직접 업로드 URL과 서명에 포함된 필수 헤더. */
public record PresignedUpload(URI url, Map<String, String> requiredHeaders) {}
