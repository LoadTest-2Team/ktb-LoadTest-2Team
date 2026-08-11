package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 클라이언트 직접 업로드를 위한 presigned URL 발급 요청. */
@Data
public class PresignUploadRequest {

    @NotBlank
    private String originalFilename;

    @NotBlank
    private String mimetype;

    @Positive
    private long size;
}
