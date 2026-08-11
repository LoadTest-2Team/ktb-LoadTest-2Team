package com.ktb.chatapp.storage;

/**
 * 스토리지 key 규약: {@code static/main/profiles/<name>}(공개), {@code static/main/chat/<name>}(인가
 * 필요). 접두사는 실제 S3 버킷의 기존 경로 구조({@code ktb-loadtest-team-2-bucket}의
 * {@code static/main/chat/}, {@code static/main/profiles/})와 맞춘 것이다.
 */
public final class StorageKey {

    private static final String ROOT_PREFIX = "static/main/";
    private static final String PROFILE_PREFIX = ROOT_PREFIX + "profiles/";
    private static final String CHAT_PREFIX = ROOT_PREFIX + "chat/";

    private StorageKey() {
    }

    public static String profile(String fileName) {
        return PROFILE_PREFIX + fileName;
    }

    public static String chat(String fileName) {
        return CHAT_PREFIX + fileName;
    }

    /**
     * 임의의 서브디렉토리를 {@code chat}/{@code profile}과 같은 루트 접두사 규약으로 조립한다.
     * {@link com.ktb.chatapp.service.LocalFileService#storeFile}처럼 subDirectory를 문자열로
     * 받는 범용 저장 경로에서 쓴다 — 여기서도 {@code chat()}/{@code profile()}과 다른 접두사가 생기면
     * 실제 버킷 경로와 어긋난다.
     */
    public static String of(String subDirectory, String fileName) {
        if (subDirectory == null || subDirectory.trim().isEmpty()) {
            return ROOT_PREFIX + fileName;
        }
        return ROOT_PREFIX + subDirectory + "/" + fileName;
    }

    public static boolean isProfile(String key) {
        return key != null && key.startsWith(PROFILE_PREFIX);
    }

    public static boolean isChat(String key) {
        return key != null && key.startsWith(CHAT_PREFIX);
    }

    public static String nameOf(String key) {
        if (isProfile(key)) {
            return key.substring(PROFILE_PREFIX.length());
        }
        if (isChat(key)) {
            return key.substring(CHAT_PREFIX.length());
        }
        return key;
    }
}
