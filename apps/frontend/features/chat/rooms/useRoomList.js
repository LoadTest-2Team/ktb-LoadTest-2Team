import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';
import {
  getCachedRoomList,
  getRoomListCacheKey,
  setCachedRoomList,
} from './roomListCache';

const applySocketUpdatesWithoutDuplicates = (serverRooms, socketUpdaters) => {
  const updatedRooms = socketUpdaters.reduce(
    (currentRooms, updater) => updater(currentRooms),
    serverRooms
  );
  const seenRoomIds = new Set();

  return updatedRooms.filter((room) => {
    if (seenRoomIds.has(room._id)) return false;
    seenRoomIds.add(room._id);
    return true;
  });
};

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
  attemptConnection,
}) => {
  const cacheKey = getRoomListCacheKey(currentUser);
  const initialCachedRooms = getCachedRoomList(cacheKey);
  const [roomState, setRoomState] = useState(() => ({
    cacheKey,
    rooms: initialCachedRooms || [],
  }));
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(initialCachedRooms === undefined);
  const [refreshing, setRefreshing] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);

  const isLoadingRef = useRef(false);
  const socketUpdatersDuringRequestRef = useRef([]);
  const cachedRoomsForCurrentUser = getCachedRoomList(cacheKey);

  const rooms = roomState.cacheKey === cacheKey
    ? roomState.rooms
    : (cachedRoomsForCurrentUser || []);
  const effectiveLoading = roomState.cacheKey === cacheKey
    ? loading
    : cachedRoomsForCurrentUser === undefined;
  const effectiveRefreshing = roomState.cacheKey === cacheKey ? refreshing : false;

  const replaceRooms = useCallback((nextRooms) => {
    setRoomState({ cacheKey, rooms: nextRooms });
    setCachedRoomList(cacheKey, nextRooms);
  }, [cacheKey]);

  const setRooms = useCallback((updater) => {
    const socketUpdater = typeof updater === 'function' ? updater : () => updater;
    socketUpdatersDuringRequestRef.current.push(socketUpdater);

    setRoomState((previousState) => {
      const currentRooms = previousState.cacheKey === cacheKey
        ? previousState.rooms
        : (getCachedRoomList(cacheKey) || []);
      const nextRooms = socketUpdater(currentRooms);

      setCachedRoomList(cacheKey, nextRooms);
      return { cacheKey, rooms: nextRooms };
    });
  }, [cacheKey]);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });

    setConnectionStatus(CONNECTION_STATUS.ERROR);
  }, [isRetrying, setConnectionStatus]);

  const loadRooms = useCallback(async () => {
    socketUpdatersDuringRequestRef.current = [];
    const connectionPromise = attemptConnection();
    const roomsPromise = axiosInstance.get('/api/rooms');

    let response;
    try {
      response = await roomsPromise;
    } catch (roomsError) {
      await connectionPromise;
      throw roomsError;
    }

    await connectionPromise;

    if (!response?.data?.data) {
      throw new Error('INVALID_RESPONSE');
    }

    const socketUpdaters = socketUpdatersDuringRequestRef.current;
    socketUpdatersDuringRequestRef.current = [];
    replaceRooms(applySocketUpdatesWithoutDuplicates(response.data.data, socketUpdaters));
  }, [attemptConnection, replaceRooms]);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current) {
      return;
    }

    try {
      isLoadingRef.current = true;
      const hasCachedRooms = getCachedRoomList(cacheKey) !== undefined;

      setLoading(!hasCachedRooms);
      setRefreshing(hasCachedRooms);
      setError(null);

      await loadRooms();

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoading(false);
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, cacheKey, isInitialLoad, loadRooms, handleFetchError]);

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다.
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({ silent = false } = {}) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setRefreshing(true);

      await loadRooms();
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, loadRooms]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    error,
    setError,
    loading: effectiveLoading,
    refreshing: effectiveRefreshing,
    joiningRoom,
    fetchRooms,
    refreshRooms,
    handleJoinRoom,
  };
};

export default useRoomList;
