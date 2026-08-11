package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import java.net.URI;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * 클라이언트 직접 업로드용 presigned URL 발급 결과. {@code file}은 업로드 완료 전에도 이미 저장된
 * 메타데이터로, Socket.IO 메시지가 참조할 {@code _id}를 여기서 얻는다.
 */
@Data
@Builder
public class PresignedUploadResult {
    private File file;
    private URI uploadUrl;
    private Map<String, String> requiredHeaders;
}
