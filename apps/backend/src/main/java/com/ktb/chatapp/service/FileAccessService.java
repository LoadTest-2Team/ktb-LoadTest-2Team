package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StoragePort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;

/**
 * 채팅 첨부 읽기 경로. 프로필 이미지({@link com.ktb.chatapp.controller.ProfileImageController})와 같은
 * 원칙 — 파일명(안전한 랜덤 생성 파일명이라 추측 불가능)만 알면 누구나 조회 가능하고, 방 참가자 검증은 하지
 * 않는다. 인가 체크를 없앤 것은 의도된 결정이다(2026-08-11) — 필요해지면 이 클래스에 다시 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAccessService {

    /** 오프로딩 URL 수명. 발급 직후 곧바로 소비되는 흐름이므로 유출 창을 짧게 유지한다. */
    static final Duration OFFLOAD_URL_TTL = Duration.ofMinutes(5);

    private final StoragePort storagePort;
    private final FileRepository fileRepository;

    public FileAccess forDownload(String fileName) {
        return issue(findFile(fileName), Delivery.ATTACHMENT, fileName);
    }

    public FileAccess forView(String fileName) {
        File fileEntity = findFile(fileName);
        if (!fileEntity.isPreviewable()) {
            throw new PreviewNotSupportedException("미리보기를 지원하지 않는 파일 형식입니다.");
        }
        return issue(fileEntity, Delivery.INLINE, fileName);
    }

    /**
     * 브라우저에 파일을 내보내는 두 방식. 오프로딩 URL에도 같은 방식을 실어야 다운로드 요청이 미리보기로
     * 바뀌지 않는다 — 스토리지가 직접 응답하면 컨트롤러가 헤더를 붙일 기회가 없다.
     */
    private enum Delivery {
        ATTACHMENT,
        INLINE;

        ContentDisposition of(String filename) {
            ContentDisposition.Builder builder =
                    this == ATTACHMENT ? ContentDisposition.attachment() : ContentDisposition.inline();
            return builder.filename(filename, StandardCharsets.UTF_8).build();
        }
    }

    private File findFile(String fileName) {
        return fileRepository.findByFilename(fileName)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));
    }

    private FileAccess issue(File fileEntity, Delivery delivery, String fileName) {
        Optional<URI> offloadUrl = storagePort.offloadUrl(
                fileEntity.getPath(), OFFLOAD_URL_TTL, delivery.of(fileEntity.getOriginalname()));
        if (offloadUrl.isPresent()) {
            log.info("파일 오프로딩 URL 발급: {}", fileName);
            return new FileAccess.Redirect(offloadUrl.get());
        }

        Resource resource = storagePort.open(fileEntity.getPath())
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));
        log.info("파일 로드 성공: {}", fileName);
        return new FileAccess.Stream(
                resource,
                fileEntity.getOriginalname(),
                fileEntity.getMimetype(),
                fileEntity.getSize());
    }
}
