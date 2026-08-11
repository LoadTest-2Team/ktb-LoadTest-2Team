const roomListsByUser = new Map();

export const getRoomListCacheKey = (user) => {
  if (!user) return null;

  const userKey = user.id || user._id || user.email;
  const sessionKey = user.sessionId || user.token;

  return userKey && sessionKey ? `${userKey}:${sessionKey}` : null;
};

export const getCachedRoomList = (cacheKey) => {
  if (!cacheKey || !roomListsByUser.has(cacheKey)) return undefined;
  return roomListsByUser.get(cacheKey);
};

export const setCachedRoomList = (cacheKey, rooms) => {
  if (!cacheKey) return;
  roomListsByUser.set(cacheKey, rooms);
};

export const clearRoomListCache = (user) => {
  if (!user) {
    roomListsByUser.clear();
    return;
  }

  const cacheKey = getRoomListCacheKey(user);
  if (cacheKey) roomListsByUser.delete(cacheKey);
};

