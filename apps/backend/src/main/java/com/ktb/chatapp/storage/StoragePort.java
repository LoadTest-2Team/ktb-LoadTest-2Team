package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;

public interface StoragePort {
    StoredObject put(InputStream content, String key, String contentType, long size);
    Optional<Resource> open(String key);
    void delete(String key);
    /**
     * 오프로딩 확장 지점. 지원하지 않으면 앱이 바이트를 중계한다.
     *
     * <p>{@code disposition}은 앱이 직접 서빙할 때 붙이는 것과 같은 헤더다. 오프로딩된 응답에도 실어야
     * 다운로드가 조용히 미리보기로 바뀌지 않는다.
     */
    default Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        return Optional.empty();
    }

    /**
     * 클라이언트 직접 업로드 확장 지점. 지원하지 않으면 앱이 바이트를 중계해야 한다(멀티파트 업로드).
     *
     * <p>{@code contentType}은 서명에 포함되므로, 발급받은 값과 다른 Content-Type으로 업로드를 시도하면
     * 스토리지가 서명 불일치로 거부한다. 반면 {@code size}는 presigned URL 자체가 강제하지 않는다 —
     * 애플리케이션 레벨(FileUtil) 검증만 통과 여부를 가른다. 실제 업로드 바이트 수까지 강제하려면 버킷
     * 정책의 content-length-range 조건이 필요하며, 이는 인프라팀 영역이다.
     */
    default Optional<PresignedUpload> presignUpload(String key, String contentType, long size, Duration ttl) {
        return Optional.empty();
    }
}
