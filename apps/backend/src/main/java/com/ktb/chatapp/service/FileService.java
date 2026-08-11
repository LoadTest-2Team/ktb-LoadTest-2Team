package com.ktb.chatapp.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    FileUploadResult uploadFile(MultipartFile file, String uploaderId);

    /**
     * 파일을 저장하고 <b>스토리지 key</b>({@code <subDirectory>/<name>})를 반환한다. URL 조립은 응답
     * 경계의 몫이므로 여기서는 하지 않는다.
     */
    String storeFile(MultipartFile file, String subDirectory);

    boolean deleteFile(String fileId, String requesterId);

    /**
     * 클라이언트가 스토리지로 직접 업로드할 수 있는 presigned URL을 발급한다. 스토리지가 이를 지원하지
     * 않으면(예: 로컬 스토리지) 예외를 던진다.
     */
    PresignedUploadResult presignUpload(String originalFilename, String mimetype, long size, String uploaderId);
}

