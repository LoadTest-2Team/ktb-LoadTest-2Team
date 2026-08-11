/*
 * mongosh seed/verify/cleanup script for GET /api/rooms load tests.
 * Configuration is supplied by room-list-test-data.sh through LOADTEST_* env vars.
 */

const roomsCount = positiveInt('LOADTEST_ROOMS', 30);
const minParticipants = positiveInt('LOADTEST_MIN_PARTICIPANTS', 5);
const maxParticipants = positiveInt('LOADTEST_MAX_PARTICIPANTS', 10);
const messagesCount = nonNegativeInt('LOADTEST_MESSAGES', 100);
const randomSeed = nonNegativeInt('LOADTEST_RANDOM_SEED', 42);
const prefix = process.env.LOADTEST_PREFIX || 'loadtest-room-list';
const mode = process.env.LOADTEST_MODE || 'seed';
const passwordHash = process.env.LOADTEST_PASSWORD_HASH;
const databaseName = process.env.LOADTEST_DATABASE || db.getName();
const targetDb = db.getSiblingDB(databaseName);

if (!/^[a-z0-9][a-z0-9-]{2,39}$/.test(prefix)) {
  throw new Error('prefix must be 3-40 lowercase letters, digits, or hyphens');
}
if (!['seed', 'verify', 'cleanup'].includes(mode)) {
  throw new Error(`unsupported mode: ${mode}`);
}
if (minParticipants > maxParticipants) {
  throw new Error('minimum participants cannot exceed maximum participants');
}
if (randomSeed > 0xffffffff) {
  throw new Error('LOADTEST_RANDOM_SEED must be between 0 and 4294967295');
}
if (mode === 'seed' && !passwordHash) {
  throw new Error('LOADTEST_PASSWORD_HASH is required in seed mode');
}

const escapedPrefix = escapeRegex(prefix);
const roomNamePattern = new RegExp(`^${escapedPrefix}-room-\\d{3}$`);
const userEmailPattern = new RegExp(`^${escapedPrefix}-r\\d{3}-u\\d{3}@loadtest\\.invalid$`);

if (mode === 'cleanup') {
  cleanup();
} else if (mode === 'verify') {
  verify();
} else {
  seed();
  verify();
}

function seed() {
  const existingRooms = targetDb.rooms.countDocuments({ name: roomNamePattern });
  const existingUsers = targetDb.users.countDocuments({ email: userEmailPattern });
  const existingMessages = targetDb.messages.countDocuments({ 'metadata.loadtestPrefix': prefix });
  if (existingRooms > 0 || existingUsers > 0 || existingMessages > 0) {
    throw new Error(
      `prefix=${prefix} already exists (rooms=${existingRooms}, users=${existingUsers}, messages=${existingMessages}); ` +
      'reuse it with --verify, or explicitly run --cleanup before creating a new dataset'
    );
  }

  const now = new Date();
  const userIdsByRoom = [];
  const random = mulberry32(randomSeed);
  const participantCounts = Array.from(
    { length: roomsCount },
    () => minParticipants + Math.floor(random() * (maxParticipants - minParticipants + 1))
  );

  for (let roomIndex = 1; roomIndex <= roomsCount; roomIndex += 1) {
    const roomUserIds = [];
    const participantsCount = participantCounts[roomIndex - 1];

    for (let userIndex = 1; userIndex <= participantsCount; userIndex += 1) {
      const email = userEmail(roomIndex, userIndex);
      const existing = targetDb.users.findOne({ email }, { _id: 1 });
      const userId = existing ? existing._id : new ObjectId();

      targetDb.users.updateOne(
        { email },
        {
          $set: {
            name: `${prefix} Room ${pad(roomIndex)} User ${pad(userIndex)}`,
            email,
            password: passwordHash,
            profileImage: '',
            updatedAt: now,
            lastActive: now,
            isOnline: false
          },
          $setOnInsert: {
            _id: userId,
            createdAt: now
          }
        },
        { upsert: true }
      );

      roomUserIds.push(userId.toString());
    }

    userIdsByRoom.push(roomUserIds);
  }

  const roomIds = [];
  for (let roomIndex = 1; roomIndex <= roomsCount; roomIndex += 1) {
    const name = roomName(roomIndex);
    const existing = targetDb.rooms.findOne({ name }, { _id: 1 });
    const roomId = existing ? existing._id : new ObjectId();
    const participantIds = userIdsByRoom[roomIndex - 1];

    targetDb.rooms.updateOne(
      { name },
      {
        $set: {
          name,
          creator: participantIds[0],
          hasPassword: false,
          password: null,
          participantIds,
          createdAt: new Date(now.getTime() - roomIndex * 1000)
        },
        $setOnInsert: { _id: roomId }
      },
      { upsert: true }
    );

    roomIds.push(roomId.toString());
  }

  const messages = [];
  const newestOffsetMs = 60 * 1000;
  const spreadMs = 28 * 60 * 1000;
  for (let roomIndex = 1; roomIndex <= roomsCount; roomIndex += 1) {
    const roomId = roomIds[roomIndex - 1];
    const participantIds = userIdsByRoom[roomIndex - 1];

    for (let messageIndex = 1; messageIndex <= messagesCount; messageIndex += 1) {
      const ratio = messagesCount <= 1 ? 0 : (messageIndex - 1) / (messagesCount - 1);
      messages.push({
        _id: new ObjectId(),
        room: roomId,
        content: `${prefix} message ${pad(messageIndex)} for room ${pad(roomIndex)}`,
        sender: participantIds[(messageIndex - 1) % participantIds.length],
        type: 'text',
        file: null,
        aiType: null,
        mentions: [],
        timestamp: new Date(now.getTime() - newestOffsetMs - Math.floor(spreadMs * ratio)),
        reactions: {},
        readers: [],
        metadata: {
          loadtestPrefix: prefix,
          roomNumber: roomIndex,
          messageNumber: messageIndex
        }
      });
    }
  }

  if (messages.length > 0) {
    targetDb.messages.insertMany(messages, { ordered: false });
  }

  print(`Seeded prefix=${prefix}: rooms=${roomsCount}, participants/room=${minParticipants}-${maxParticipants}, messages/room=${messagesCount}, seed=${randomSeed}`);
  print(`API verification user: ${userEmail(1, 1)}`);
}

function verify() {
  const since = new Date(Date.now() - 30 * 60 * 1000);
  const rooms = targetDb.rooms.find({ name: roomNamePattern }).sort({ name: 1 }).toArray();
  const roomIds = rooms.map((room) => room._id.toString());
  const referencedUserIds = [...new Set(rooms.flatMap((room) => [room.creator, ...(room.participantIds || [])]))];
  const existingUserIds = new Set(
    targetDb.users.find({ _id: { $in: referencedUserIds.map(toObjectId) } }, { _id: 1 })
      .toArray()
      .map((user) => user._id.toString())
  );

  const recentCounts = new Map(
    targetDb.messages.aggregate([
      { $match: { room: { $in: roomIds }, timestamp: { $gte: since } } },
      { $group: { _id: '$room', count: { $sum: 1 } } }
    ]).toArray().map((entry) => [entry._id, entry.count])
  );

  const failures = [];
  const participantCounts = [];
  print(`Verification prefix=${prefix}`);
  for (const room of rooms) {
    const participants = room.participantIds || [];
    const uniqueParticipantCount = new Set(participants).size;
    const recentMessages = recentCounts.get(room._id.toString()) || 0;
    const missingUsers = [room.creator, ...participants].filter((id) => !existingUserIds.has(id));
    participantCounts.push(participants.length);

    print(`  ${room.name}: participants=${participants.length}, recentMessages=${recentMessages}`);
    if (participants.length < minParticipants || participants.length > maxParticipants) {
      failures.push(`${room.name}: participants=${participants.length}, expected range=${minParticipants}-${maxParticipants}`);
    }
    if (uniqueParticipantCount !== participants.length) {
      failures.push(`${room.name}: participants=${participants.length}, unique=${uniqueParticipantCount}`);
    }
    if (!participants.includes(room.creator)) {
      failures.push(`${room.name}: creator is not a participant`);
    }
    if (missingUsers.length > 0) {
      failures.push(`${room.name}: missing user references=${missingUsers.join(',')}`);
    }
    if (recentMessages !== messagesCount) {
      failures.push(`${room.name}: recentMessages=${recentMessages}`);
    }
  }

  print(`  rooms: ${rooms.length} (expected ${roomsCount})`);
  print(`  referenced existing users: ${existingUserIds.size}/${referencedUserIds.length}`);
  print(`  participant count range: expected ${minParticipants}-${maxParticipants}`);
  print(`  recent messages per room: expected ${messagesCount}`);
  if (participantCounts.length > 0) {
    const totalParticipants = participantCounts.reduce((sum, count) => sum + count, 0);
    print(`  participant statistics: min=${Math.min(...participantCounts)}, max=${Math.max(...participantCounts)}, average=${(totalParticipants / participantCounts.length).toFixed(2)}`);
  }

  if (rooms.length !== roomsCount) {
    failures.push(`rooms=${rooms.length}`);
  }
  if (failures.length > 0) {
    failures.forEach((failure) => print(`  FAIL: ${failure}`));
    throw new Error(`verification failed with ${failures.length} issue(s)`);
  }
  print('  OK: all database checks passed');
}

function cleanup() {
  const rooms = targetDb.rooms.find({ name: roomNamePattern }, { _id: 1 }).toArray();
  const roomIds = rooms.map((room) => room._id.toString());
  const users = targetDb.users.find({ email: userEmailPattern }, { _id: 1 }).toArray();
  const userIds = users.map((user) => user._id.toString());

  const messageResult = targetDb.messages.deleteMany({
    $or: [
      { 'metadata.loadtestPrefix': prefix },
      { room: { $in: roomIds } }
    ]
  });
  const roomResult = targetDb.rooms.deleteMany({ name: roomNamePattern });

  // Do not delete a prefixed user if any non-prefixed room still references it.
  const externallyReferenced = new Set(
    targetDb.rooms.find(
      { $or: [{ creator: { $in: userIds } }, { participantIds: { $in: userIds } }] },
      { creator: 1, participantIds: 1 }
    ).toArray().flatMap((room) => [room.creator, ...(room.participantIds || [])])
  );
  const deletableUsers = users.filter((user) => !externallyReferenced.has(user._id.toString()));
  const deletableUserObjectIds = deletableUsers.map((user) => user._id);
  const deletableUserIds = deletableUsers.map((user) => user._id.toString());

  const sessionResult = targetDb.sessions.deleteMany({ userId: { $in: deletableUserIds } });
  const userResult = targetDb.users.deleteMany({ _id: { $in: deletableUserObjectIds }, email: userEmailPattern });

  print(`Cleaned prefix=${prefix}: messages=${messageResult.deletedCount}, rooms=${roomResult.deletedCount}, sessions=${sessionResult.deletedCount}, users=${userResult.deletedCount}`);
  if (externallyReferenced.size > 0) {
    print(`Skipped ${users.length - deletableUsers.length} user(s) still referenced by non-prefixed rooms`);
  }
}

function positiveInt(name, fallback) {
  const value = Number(process.env[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name} must be a positive integer`);
  return value;
}

function nonNegativeInt(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer`);
  return value;
}

function pad(value) {
  return String(value).padStart(3, '0');
}

function roomName(roomIndex) {
  return `${prefix}-room-${pad(roomIndex)}`;
}

function userEmail(roomIndex, userIndex) {
  return `${prefix}-r${pad(roomIndex)}-u${pad(userIndex)}@loadtest.invalid`;
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function toObjectId(value) {
  if (!ObjectId.isValid(value)) throw new Error(`invalid referenced user id: ${value}`);
  return new ObjectId(value);
}

// Small deterministic 32-bit PRNG. The same seed and room count produce the
// same participant-count sequence without adding a runtime dependency.
function mulberry32(seed) {
  let state = seed >>> 0;
  return function next() {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}
