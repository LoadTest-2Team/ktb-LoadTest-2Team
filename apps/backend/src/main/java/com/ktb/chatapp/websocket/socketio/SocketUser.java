package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Socket User Record
 * @param id user id
 * @param name user name
 * @param authSessionId user auth session id
 * @param socketId user websocket session id
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record SocketUser(String id, String name, String authSessionId, String socketId) {
}
