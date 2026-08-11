package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import java.net.URI;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 저장된 파일 메타데이터와 클라이언트 직접 업로드 정보를 함께 반환한다. */
@Data
@Builder
public class PresignedUploadResult {
    private File file;
    private URI uploadUrl;
    private Map<String, String> requiredHeaders;
}
