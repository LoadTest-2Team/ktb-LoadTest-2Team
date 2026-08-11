import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';
import { clearRoomListCache } from '../roomListCache';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms) => ({ data: { data: rooms } });

const renderRoomList = (currentUser = { id: 'user-1', token: 'token-1', sessionId: 'session-1' }) =>
  renderHook(() =>
    useRoomList({
      currentUser,
      router: { push: vi.fn() },
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearRoomListCache();
  });

  it('shows a cached list immediately on remount and revalidates it', async () => {
    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse([{ _id: 'room-1', name: 'cached' }]))
      .mockResolvedValueOnce(roomsResponse([{ _id: 'room-1', name: 'fresh' }]));

    const firstMount = renderRoomList();
    await act(async () => {
      await firstMount.result.current.fetchRooms();
    });
    firstMount.unmount();

    const secondMount = renderRoomList();
    expect(secondMount.result.current.rooms).toEqual([{ _id: 'room-1', name: 'cached' }]);
    expect(secondMount.result.current.loading).toBe(false);

    await act(async () => {
      await secondMount.result.current.fetchRooms();
    });

    expect(axiosInstance.get).toHaveBeenCalledTimes(2);
    expect(secondMount.result.current.rooms).toEqual([{ _id: 'room-1', name: 'fresh' }]);
  });

  it('does not expose one user cache to another user', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const firstUser = renderRoomList();
    await act(async () => {
      await firstUser.result.current.fetchRooms();
    });
    firstUser.unmount();

    const secondUser = renderRoomList({ id: 'user-2', token: 'token-2', sessionId: 'session-2' });

    expect(secondUser.result.current.rooms).toEqual([]);
    expect(secondUser.result.current.loading).toBe(true);
  });

  it('preserves socket updates that arrive during revalidation without duplicating rooms', async () => {
    let resolveRequest;
    axiosInstance.get.mockReturnValue(new Promise((resolve) => {
      resolveRequest = resolve;
    }));

    const { result } = renderRoomList();
    let requestPromise;
    act(() => {
      requestPromise = result.current.fetchRooms();
    });

    act(() => {
      result.current.setRooms(() => [
        { _id: 'room-2', name: 'socket-created' },
        { _id: 'room-1', name: 'socket-updated', recentMessageCount: 2 },
      ]);
    });

    await act(async () => {
      resolveRequest(roomsResponse([
        { _id: 'room-1', name: 'server-old', recentMessageCount: 1 },
      ]));
      await requestPromise;
    });

    expect(result.current.rooms).toEqual([
      { _id: 'room-2', name: 'socket-created' },
      { _id: 'room-1', name: 'socket-updated', recentMessageCount: 2 },
    ]);
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });
});
