#!/usr/bin/env node

const axios = require('axios');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');

const argv = yargs(hideBin(process.argv))
  .option('users', {
    alias: 'u',
    description: 'Concurrent virtual users',
    type: 'number',
    demandOption: true
  })
  .option('duration', {
    alias: 'd',
    description: 'Measured duration in seconds',
    type: 'number',
    default: 60
  })
  .option('api-url', {
    description: 'Backend REST API URL',
    type: 'string',
    default: 'http://localhost:5001'
  })
  .option('prefix', {
    description: 'Seeded room-list user prefix',
    type: 'string',
    default: 'loadtest-room-list'
  })
  .option('rooms', {
    description: 'Number of seeded rooms used to discover login users',
    type: 'number',
    default: 30
  })
  .option('max-participants', {
    description: 'Maximum seeded participants per room',
    type: 'number',
    default: 10
  })
  .option('password', {
    description: 'Password assigned by room-list-test-data.sh',
    type: 'string',
    default: 'Test1234!'
  })
  .option('login-concurrency', {
    description: 'Concurrent logins during unmeasured setup',
    type: 'number',
    default: 10
  })
  .option('request-timeout', {
    description: 'GET request timeout in milliseconds',
    type: 'number',
    default: 30000
  })
  .option('think-time', {
    description: 'Delay between GET requests per VU in milliseconds',
    type: 'number',
    default: 0
  })
  .option('xff-per-vu', {
    description: 'Send a stable benchmark X-Forwarded-For address per VU',
    type: 'boolean',
    default: true
  })
  .strict()
  .help()
  .alias('help', 'h')
  .parse();

validatePositiveInteger('users', argv.users);
validatePositiveInteger('duration', argv.duration);
validatePositiveInteger('rooms', argv.rooms);
validatePositiveInteger('max-participants', argv.maxParticipants);
validatePositiveInteger('login-concurrency', argv.loginConcurrency);
validatePositiveInteger('request-timeout', argv.requestTimeout);
validateNonNegativeInteger('think-time', argv.thinkTime);

const api = axios.create({
  baseURL: argv.apiUrl.replace(/\/$/, ''),
  timeout: argv.requestTimeout,
  validateStatus: () => true
});

main().catch((error) => {
  console.error(`\nFatal: ${error.message}`);
  process.exitCode = 1;
});

async function main() {
  printConfiguration();
  console.log('\nPreparing authentication sessions (excluded from measurements)...');

  const candidates = buildCandidateUsers();
  const credentials = await loginUsers(candidates, Math.min(argv.users, candidates.length));
  if (credentials.length === 0) {
    throw new Error('no seeded users could log in; check prefix, password, Backend, and seeded data');
  }
  if (credentials.length < argv.users) {
    console.log(
      `Using ${credentials.length} distinct authenticated sessions for ${argv.users} VUs; ` +
      'sessions are assigned round-robin without additional logins.'
    );
  } else {
    console.log(`Prepared ${credentials.length} distinct authenticated sessions.`);
  }

  console.log('Authentication complete. Starting measured GET-only phase in 2 seconds...');
  await sleep(2000);

  const stats = createStats();
  const measuredStartNs = process.hrtime.bigint();
  const deadlineNs = measuredStartNs + BigInt(argv.duration) * 1_000_000_000n;
  await Promise.all(
    Array.from({ length: argv.users }, (_, vuIndex) =>
      runVirtualUser(vuIndex, credentials[vuIndex % credentials.length], deadlineNs, stats)
    )
  );
  const measuredEndNs = process.hrtime.bigint();

  printResults(stats, Number(measuredEndNs - measuredStartNs) / 1e9);
}

function buildCandidateUsers() {
  const candidates = [];
  for (let room = 1; room <= argv.rooms; room += 1) {
    for (let user = 1; user <= argv.maxParticipants; user += 1) {
      candidates.push(`${argv.prefix}-r${pad(room)}-u${pad(user)}@loadtest.invalid`);
    }
  }
  return candidates;
}

async function loginUsers(candidates, desiredCount) {
  const credentials = [];
  let nextIndex = 0;

  async function worker() {
    while (credentials.length < desiredCount) {
      const candidateIndex = nextIndex;
      nextIndex += 1;
      if (candidateIndex >= candidates.length) return;

      const email = candidates[candidateIndex];
      try {
        const response = await api.post('/api/auth/login', { email, password: argv.password });
        if (response.status === 200 && response.data?.token && response.data?.sessionId) {
          credentials.push({
            email,
            token: response.data.token,
            sessionId: response.data.sessionId
          });
        } else if (response.status !== 401 && response.status !== 404) {
          console.error(`Login skipped ${email}: HTTP ${response.status}`);
        }
      } catch (error) {
        console.error(`Login skipped ${email}: ${error.code || error.message}`);
      }
    }
  }

  await Promise.all(Array.from({ length: argv.loginConcurrency }, () => worker()));
  return credentials.slice(0, desiredCount);
}

async function runVirtualUser(vuIndex, credential, deadlineNs, stats) {
  const headers = {
    Authorization: `Bearer ${credential.token}`,
    'x-session-id': credential.sessionId,
    Accept: 'application/json'
  };
  if (argv.xffPerVu) {
    headers['X-Forwarded-For'] = benchmarkIp(vuIndex);
  }

  while (process.hrtime.bigint() < deadlineNs) {
    const requestStartNs = process.hrtime.bigint();
    try {
      const response = await api.get('/api/rooms', { headers });
      const latencyMs = Number(process.hrtime.bigint() - requestStartNs) / 1e6;
      stats.total += 1;
      increment(stats.statusCounts, String(response.status));

      if (response.status === 200 && response.data?.success === true) {
        stats.success += 1;
        stats.successLatencies.push(latencyMs);
      } else {
        stats.failed += 1;
        if (response.status === 429) stats.rateLimited += 1;
      }
    } catch (error) {
      const latencyMs = Number(process.hrtime.bigint() - requestStartNs) / 1e6;
      stats.total += 1;
      stats.failed += 1;
      stats.transportFailureLatencies.push(latencyMs);
      increment(stats.errorCounts, error.code || error.name || 'UNKNOWN');
    }

    if (argv.thinkTime > 0) await sleep(argv.thinkTime);
  }
}

function createStats() {
  return {
    total: 0,
    success: 0,
    failed: 0,
    rateLimited: 0,
    successLatencies: [],
    transportFailureLatencies: [],
    statusCounts: {},
    errorCounts: {}
  };
}

function printResults(stats, elapsedSeconds) {
  const sortedLatencies = [...stats.successLatencies].sort((a, b) => a - b);
  const latencySum = sortedLatencies.reduce((sum, latency) => sum + latency, 0);
  const successRate = stats.total === 0 ? 0 : stats.success / stats.total * 100;
  const average = sortedLatencies.length === 0 ? 0 : latencySum / sortedLatencies.length;

  console.log('\n=== GET /api/rooms REST Load Test Result ===');
  console.log(`Configured duration : ${argv.duration.toFixed(0)} s`);
  console.log(`Actual elapsed      : ${elapsedSeconds.toFixed(3)} s`);
  console.log(`Virtual users       : ${argv.users}`);
  console.log(`Total requests      : ${stats.total}`);
  console.log(`Successful          : ${stats.success}`);
  console.log(`Failed              : ${stats.failed}`);
  console.log(`HTTP 429            : ${stats.rateLimited}`);
  console.log(`Success rate        : ${successRate.toFixed(2)}%`);
  console.log(`Average response    : ${average.toFixed(2)} ms`);
  console.log(`P95 response        : ${percentile(sortedLatencies, 95).toFixed(2)} ms`);
  console.log(`P99 response        : ${percentile(sortedLatencies, 99).toFixed(2)} ms`);
  console.log(`RPS                  : ${(stats.total / elapsedSeconds).toFixed(2)}`);
  console.log(`HTTP statuses       : ${formatCounts(stats.statusCounts)}`);
  console.log(`Transport errors    : ${formatCounts(stats.errorCounts)}`);
  console.log('\nLatency statistics include successful HTTP 200 responses with success=true only.');
  if (stats.rateLimited > 0) {
    console.log('WARNING: HTTP 429 responses occurred; this run is rate-limit distorted.');
  }
}

function printConfiguration() {
  console.log('=== GET /api/rooms REST Load Test ===');
  console.log(`Backend             : ${argv.apiUrl}`);
  console.log(`Virtual users       : ${argv.users}`);
  console.log(`Duration            : ${argv.duration} s`);
  console.log(`Seed user prefix    : ${argv.prefix}`);
  console.log(`Per-VU XFF          : ${argv.xffPerVu ? 'enabled' : 'disabled'}`);
  console.log(`Think time          : ${argv.thinkTime} ms`);
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) return 0;
  const rank = Math.ceil(percentileValue / 100 * sortedValues.length) - 1;
  return sortedValues[Math.max(0, rank)];
}

function benchmarkIp(vuIndex) {
  const value = vuIndex + 1;
  const thirdOctet = Math.floor((value - 1) / 254);
  const fourthOctet = (value - 1) % 254 + 1;
  return `198.18.${thirdOctet}.${fourthOctet}`;
}

function increment(counts, key) {
  counts[key] = (counts[key] || 0) + 1;
}

function formatCounts(counts) {
  const entries = Object.entries(counts).sort(([left], [right]) => left.localeCompare(right));
  return entries.length === 0 ? 'none' : entries.map(([key, count]) => `${key}=${count}`).join(', ');
}

function validatePositiveInteger(name, value) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`--${name} must be a positive integer`);
  }
}

function validateNonNegativeInteger(name, value) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`--${name} must be a non-negative integer`);
  }
}

function pad(value) {
  return String(value).padStart(3, '0');
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
