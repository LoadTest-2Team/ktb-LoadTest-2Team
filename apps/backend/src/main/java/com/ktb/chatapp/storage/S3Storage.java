package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;
    private final String cdnDomain;

    public S3Storage(
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.s3.cdn-domain}") String cdnDomain) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
        this.cdnDomain = cdnDomain;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (Exception ex) {
            throw new RuntimeException("파일 저장에 실패했습니다: " + ex.getMessage(), ex);
        }
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        try {
            InputStream objectStream = s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new InputStreamResource(objectStream));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * 서명 없는 CloudFront URL을 그대로 돌려준다(부하테스트 목적, 2026-08-11 결정) — {@code ttl}/
     * {@code disposition}은 쓰지 않는다. 이 배포의 CloudFront 배포(EF7I3Z3IO9194)는 {@code /static/*}
     * 캐시 비헤이비어를 이 버킷(OAI)으로 이미 라우팅하고 있어서, 버킷 정책이나 public-access-block을
     * 건드리지 않고도 {@code key}(항상 {@code static/main/...}로 시작)를 그대로 공개 서빙할 수 있다.
     */
    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        return Optional.of(URI.create("https://" + cdnDomain + "/" + key));
    }

    @Override
    public Optional<PresignedUpload> presignUpload(String key, String contentType, long size, Duration ttl) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return Optional.of(new PresignedUpload(
                URI.create(presigned.url().toString()),
                Map.of("Content-Type", contentType)));
    }
}
