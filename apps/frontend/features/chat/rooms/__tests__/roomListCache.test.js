import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearRoomListCache,
  getCachedRoomList,
  getRoomListCacheKey,
  setCachedRoomList,
} from '../roomListCache';

describe('roomListCache', () => {
  beforeEach(() => {
    clearRoomListCache();
  });

  it('isolates cached room lists by user session', () => {
    const firstKey = getRoomListCacheKey({ id: 'user-1', sessionId: 'session-1' });
    const secondKey = getRoomListCacheKey({ id: 'user-2', sessionId: 'session-2' });

    setCachedRoomList(firstKey, [{ _id: 'room-1' }]);

    expect(getCachedRoomList(firstKey)).toEqual([{ _id: 'room-1' }]);
    expect(getCachedRoomList(secondKey)).toBeUndefined();
  });

  it('clears all cached room lists when authentication is removed', () => {
    const cacheKey = getRoomListCacheKey({ id: 'user-1', sessionId: 'session-1' });
    setCachedRoomList(cacheKey, [{ _id: 'room-1' }]);

    clearRoomListCache();

    expect(getCachedRoomList(cacheKey)).toBeUndefined();
  });
});

