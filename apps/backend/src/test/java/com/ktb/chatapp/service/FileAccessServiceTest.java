package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.storage.StoredObject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;

/**
 * 채팅 첨부 읽기 경로 단위 테스트. 프로필 이미지와 같은 원칙(파일명만 알면 조회 가능, 방 참가자 검증 없음 —
 * 2026-08-11 결정)이므로, 여기서 고정하는 건 오프로딩 스위치의 동작뿐이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileAccessService 단위 테스트")
class FileAccessServiceTest {

    private static final String FILE_NAME = "1700000000000_photo.png";
    private static final String KEY = "static/main/chat/" + FILE_NAME;
    private static final String FILE_ID = "file-id";
    private static final String ORIGINAL_NAME = "여행 사진.png";
    private static final long SIZE = 4242L;
    private static final URI OFFLOADED_URL = URI.create("https://cdn.example.test/" + KEY + "?sig=stub");
    private static final Resource STORED_BYTES =
            new ByteArrayResource("photo-bytes".getBytes(StandardCharsets.UTF_8));

    @Mock
    private FileRepository fileRepository;

    @Test
    @DisplayName("오프로딩 지원 스토리지 → Redirect로 오프로딩된다")
    void forDownload_offloadSupport_returnsRedirect() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        FileAccess access = service.forDownload(FILE_NAME);

        assertThat(access).isInstanceOf(FileAccess.Redirect.class);
        assertThat(((FileAccess.Redirect) access).location()).isEqualTo(OFFLOADED_URL);
        assertThat(storage.offloadedKey).isEqualTo(KEY);
        assertThat(storage.openCalls).isZero();
    }

    @Test
    @DisplayName("오프로딩 미지원 스토리지 → 앱이 중계하는 Stream을 조립한다")
    void forDownload_noOffloadSupport_returnsStream() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        FileAccess access = service.forDownload(FILE_NAME);

        assertThat(access).isInstanceOf(FileAccess.Stream.class);
        FileAccess.Stream stream = (FileAccess.Stream) access;
        assertThat(stream.resource()).isSameAs(STORED_BYTES);
        assertThat(stream.originalname()).isEqualTo(ORIGINAL_NAME);
        assertThat(stream.contentType()).isEqualTo("image/png");
        assertThat(stream.size()).isEqualTo(SIZE);
        assertThat(storage.openedKey).isEqualTo(KEY);
    }

    @Test
    @DisplayName("오프로딩 URL TTL은 유한한 짧은 값으로 전달된다")
    void forDownload_passesBoundedOffloadTtl() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forDownload(FILE_NAME);

        assertThat(storage.offloadedTtl).isEqualTo(FileAccessService.OFFLOAD_URL_TTL);
        assertThat(FileAccessService.OFFLOAD_URL_TTL)
                .isGreaterThan(Duration.ZERO)
                .isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("다운로드 오프로딩 URL에는 원본 파일명의 attachment 지시가 실린다")
    void forDownload_offloadUrlCarriesAttachmentDisposition() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forDownload(FILE_NAME);

        assertThat(storage.offloadedDisposition.isAttachment()).isTrue();
        assertThat(storage.offloadedDisposition.getFilename()).isEqualTo(ORIGINAL_NAME);
    }

    @Test
    @DisplayName("미리보기 오프로딩 URL은 inline 지시로 발급된다")
    void forView_offloadUrlCarriesInlineDisposition() {
        OffloadingStorage storage = new OffloadingStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        service.forView(FILE_NAME);

        assertThat(storage.offloadedDisposition.isInline()).isTrue();
        assertThat(storage.offloadedDisposition.getFilename()).isEqualTo(ORIGINAL_NAME);
    }

    @Test
    @DisplayName("미리보기 가능한 형식은 view에서도 Stream을 조립한다")
    void forView_previewableMimetype_returnsStream() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "image/png");

        assertThat(service.forView(FILE_NAME)).isInstanceOf(FileAccess.Stream.class);
    }

    @Test
    @DisplayName("미리보기 미지원 형식은 view에서 PreviewNotSupportedException")
    void forView_nonPreviewableMimetype_throwsPreviewNotSupported() {
        DirectStorage storage = new DirectStorage();
        FileAccessService service = serviceWith(storage, "application/zip");

        assertThatThrownBy(() -> service.forView(FILE_NAME))
                .isInstanceOf(PreviewNotSupportedException.class)
                .hasMessage("미리보기를 지원하지 않는 파일 형식입니다.");
    }

    @Test
    @DisplayName("파일 메타데이터가 없으면 404 계약 메시지로 거부된다")
    void forDownload_missingFileEntity_throwsNotFound() {
        when(fileRepository.findByFilename(FILE_NAME)).thenReturn(Optional.empty());
        FileAccessService service = new FileAccessService(new DirectStorage(), fileRepository);

        assertThatThrownBy(() -> service.forDownload(FILE_NAME))
                .hasMessage("파일을 찾을 수 없습니다: " + FILE_NAME);
    }

    @Test
    @DisplayName("스토리지에 실물이 없으면 404 계약 메시지로 거부된다")
    void forDownload_missingStoredObject_throwsNotFound() {
        DirectStorage storage = new DirectStorage();
        storage.stored = false;
        FileAccessService service = serviceWith(storage, "image/png");

        assertThatThrownBy(() -> service.forDownload(FILE_NAME))
                .hasMessage("파일을 찾을 수 없습니다: " + FILE_NAME);
    }

    private FileAccessService serviceWith(StoragePort storagePort, String mimetype) {
        when(fileRepository.findByFilename(FILE_NAME)).thenReturn(Optional.of(fileEntity(mimetype)));
        return new FileAccessService(storagePort, fileRepository);
    }

    private File fileEntity(String mimetype) {
        return File.builder()
                .id(FILE_ID)
                .filename(FILE_NAME)
                .originalname(ORIGINAL_NAME)
                .mimetype(mimetype)
                .size(SIZE)
                .path(KEY)
                .user("uploader-id")
                .build();
    }

    /** 오프로딩을 지원하지 않는 스토리지 — {@link StoragePort#offloadUrl}의 default를 그대로 쓴다. */
    private static final class DirectStorage implements StoragePort {

        private boolean stored = true;
        private String openedKey;
        private int openCalls;

        @Override
        public StoredObject put(InputStream content, String key, String contentType, long size) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<Resource> open(String key) {
            openCalls++;
            openedKey = key;
            return stored ? Optional.of(STORED_BYTES) : Optional.empty();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }
    }

    /** 오프로딩을 지원하는 스토리지 — 오프로딩 스위치가 켜진 상태를 모사한다. */
    private static final class OffloadingStorage implements StoragePort {

        private String offloadedKey;
        private Duration offloadedTtl;
        private ContentDisposition offloadedDisposition;
        private int offloadCalls;
        private int openCalls;

        @Override
        public StoredObject put(InputStream content, String key, String contentType, long size) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<Resource> open(String key) {
            openCalls++;
            return Optional.of(STORED_BYTES);
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("읽기 경로 테스트에서는 쓰지 않는다");
        }

        @Override
        public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
            offloadCalls++;
            offloadedKey = key;
            offloadedTtl = ttl;
            offloadedDisposition = disposition;
            return Optional.of(OFFLOADED_URL);
        }
    }
}
