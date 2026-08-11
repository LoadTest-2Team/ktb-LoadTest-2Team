package com.ktb.chatapp.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StorageKey 단위 테스트")
class StorageKeyTest {

    @Test
    @DisplayName("profile()은 static/main/profiles/ 접두사를 붙인다")
    void profile_prependsProfilesPrefix() {
        assertThat(StorageKey.profile("avatar.png")).isEqualTo("static/main/profiles/avatar.png");
    }

    @Test
    @DisplayName("chat()은 static/main/chat/ 접두사를 붙인다")
    void chat_prependsChatPrefix() {
        assertThat(StorageKey.chat("photo.jpg")).isEqualTo("static/main/chat/photo.jpg");
    }

    @Test
    @DisplayName("isProfile()은 static/main/profiles/ 접두사 키만 참으로 판별한다")
    void isProfile_detectsProfilePrefixOnly() {
        assertThat(StorageKey.isProfile("static/main/profiles/avatar.png")).isTrue();
        assertThat(StorageKey.isProfile("static/main/chat/photo.jpg")).isFalse();
        assertThat(StorageKey.isProfile(null)).isFalse();
    }

    @Test
    @DisplayName("isChat()은 static/main/chat/ 접두사 키만 참으로 판별한다")
    void isChat_detectsChatPrefixOnly() {
        assertThat(StorageKey.isChat("static/main/chat/photo.jpg")).isTrue();
        assertThat(StorageKey.isChat("static/main/profiles/avatar.png")).isFalse();
        assertThat(StorageKey.isChat(null)).isFalse();
    }

    @Test
    @DisplayName("nameOf()는 알려진 접두사를 제거한 파일명을 반환한다")
    void nameOf_stripsKnownPrefix() {
        assertThat(StorageKey.nameOf("static/main/profiles/avatar.png")).isEqualTo("avatar.png");
        assertThat(StorageKey.nameOf("static/main/chat/photo.jpg")).isEqualTo("photo.jpg");
    }

    @Test
    @DisplayName("nameOf()는 알려진 접두사가 없으면 원본을 그대로 반환한다")
    void nameOf_returnsOriginalWhenPrefixUnknown() {
        assertThat(StorageKey.nameOf("plain.txt")).isEqualTo("plain.txt");
    }
}
