package com.odiousapps.z2mdash.mqtt

import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PayloadCacheEntry
import com.odiousapps.z2mdash.data.PayloadCacheRepository
import com.odiousapps.z2mdash.data.PermitJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Cap on how many recent messages the Terminal tab keeps around, oldest dropped first. */
internal const val MAX_LOGGED_MESSAGES = 300

/** See createConnection()'s own comment on why incoming messages are batched at all. */
private val BATCH_INTERVAL_MS = 200.milliseconds

/** How long [MqttConnectionManager.watchRetain]'s probe waits for the broker's retained copy. */
private val RETAIN_PROBE_TIMEOUT = 10.seconds

/** One entry in the Terminal tab's rolling message log. */
// Hands out LoggedMessage.id - process-wide, so ids never repeat across brokers either.
private val nextLoggedMessageId = java.util.concurrent.atomic.AtomicLong()

data class LoggedMessage(
    val brokerId: String,
    val topic: String,
    val payload: String,
    val timestamp: Long,
    // Unique per message - the Terminal list's item key. (timestamp, topic, broker) isn't: a
    // device publishing twice within the same millisecond (common for a Zigbee2MQTT state update
    // and its follow-up) produced two identical keys, and LazyColumn crashes the app on that.
    val id: Long = nextLoggedMessageId.getAndIncrement()
)

/**
 * App-wide singleton (held by Z2mDashApplication) that owns one MqttConnection
 * per configured broker, keeps subscriptions in sync with whatever panels exist,
 * and aggregates incoming payloads/connection state into simple StateFlows the
 * Compose UI can collect directly.
 */
class MqttConnectionManager(
    private val scope: CoroutineScope,
    private val payloadCacheRepository: PayloadCacheRepository,
    // Shown in the Terminal in place of a topic for a connection failure (a string resource,
    // passed in since this class otherwise has no Context).
    private val connectionErrorLabel: String
) {

    private val connections = mutableMapOf<String, MqttConnection>()
    private val brokerById = mutableMapOf<String, Broker>()
    // The "<base>/#" filters each broker was last subscribed to by applyConfig, so a base topic
    // removed from the broker's settings can be unsubscribed rather than left delivering forever.
    private val wildcardsByBrokerId = mutableMapOf<String, List<String>>()
    // Set once applyConfig has run - pruning before then would wipe the whole on-disk cache, since
    // nothing is subscribed yet.
    @Volatile private var configApplied = false

    private val _latestPayloads = MutableStateFlow<Map<String, String>>(emptyMap())
    val latestPayloads: StateFlow<Map<String, String>> = _latestPayloads

    // When (device time, i.e. System.currentTimeMillis() at receipt) each topic's
    // latest payload arrived - used to show "updated 2 hours ago" on the dashboard.
    private val _latestPayloadTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestPayloadTimestamps: StateFlow<Map<String, Long>> = _latestPayloadTimestamps

    // Anything in latestPayloads timestamped before this was only replayed from the on-disk cache,
    // not (yet) received from a broker this session - see isLive.
    private val sessionStartMillis = System.currentTimeMillis()

    /**
     * Whether [key]'s payload has actually arrived from its broker since the app started, rather
     * than only being replayed from the on-disk cache. A retained topic cleared on the broker
     * while this phone was offline never gets its empty "cleared" message redelivered, so its
     * stale cached payload would otherwise linger forever looking current - a broker redelivers
     * every retained topic that still exists the moment it's subscribed to, so one not refreshed
     * this session is either gone or not yet redelivered.
     */
    fun isLive(key: String): Boolean = (_latestPayloadTimestamps.value[key] ?: 0L) >= sessionStartMillis

    // When disconnectAll last ran - see isFromCurrentConnection.
    @Volatile private var disconnectedAllAtMillis = 0L

    /**
     * Like [isLive], but also excludes payloads from before the last [disconnectAll] - i.e. whether
     * [key] has arrived since its broker was last (re)connected, not just at some point this run.
     */
    fun isFromCurrentConnection(key: String): Boolean =
        (_latestPayloadTimestamps.value[key] ?: 0L) >= maxOf(sessionStartMillis, disconnectedAllAtMillis)

    init {
        // Seeded from disk so the dashboard shows correct "updated N ago" data before any MQTT
        // traffic arrives this session, instead of blank. Loaded on a background dispatcher, not
        // synchronously in this constructor (main thread, during Application.onCreate()) - the
        // file I/O plus JSON parsing of a large household's cache is enough to skip frames at
        // cold start. Topics just look "not yet reported" until the load completes.
        scope.launch(Dispatchers.IO) {
            val cached = payloadCacheRepository.load()
            if (cached.isNotEmpty()) {
                _latestPayloads.update { it + cached.mapValues { entry -> entry.value.payload } }
                _latestPayloadTimestamps.update { it + cached.mapValues { entry -> entry.value.timestamp } }
                // The cache can finish loading after applyConfig already pruned - prune its
                // contents too, or a removed base topic's devices would come straight back.
                if (configApplied) scope.launch(Dispatchers.Main) { pruneUnwatchedPayloads() }
            }
        }
    }

    // See watchRetain. probePayloads holds each key's latest live payload while its probe runs.
    @Volatile private var retainWatchKeys: Set<String> = emptySet()
    private val probedKeys = ConcurrentHashMap.newKeySet<String>()
    private val probePayloads = ConcurrentHashMap<String, String>()
    private val _unretainedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Keys passed to [watchRetain] whose broker turned out not to keep their latest message. */
    val unretainedKeys: StateFlow<Set<String>> = _unretainedKeys

    /**
     * Checks whether each of [keys] ("brokerId|topic") is retained on its broker - periodic
     * background checks (see TemperatureCheckWorker) only ever see a reading the broker kept.
     * Probed once per key per app run, on its first live message: its exact topic is resubscribed,
     * and a broker replays a stored message on every SUBSCRIBE with the RETAIN flag set. A replay
     * matching that live payload proves it's retained; none within [RETAIN_PROBE_TIMEOUT], or a
     * different (stale, left over from back when retain was on) one, proves it isn't. Waiting for
     * a live message rather than judging from the connect-time replay alone avoids flagging a
     * sensor that simply hasn't reported since the broker or Zigbee2MQTT last restarted.
     */
    fun watchRetain(keys: Set<String>) {
        retainWatchKeys = keys
        _unretainedKeys.update { it intersect keys }
    }

    private fun onRetainProbeMessage(conn: MqttConnection, key: String, msg: IncomingMessage) {
        if (msg.retained) {
            val livePayload = probePayloads.remove(key) ?: return
            val retained = msg.payload == livePayload
            _unretainedKeys.update { if (retained) it - key else it + key }
        } else if (probePayloads.replace(key, msg.payload) == null && key in retainWatchKeys && probedKeys.add(key)) {
            probePayloads[key] = msg.payload
            scope.launch(Dispatchers.Main) {
                conn.resubscribe(msg.topic)
                delay(RETAIN_PROBE_TIMEOUT)
                if (probePayloads.remove(key) != null) _unretainedKeys.update { it + key }
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates

    // Rolling log of the most recent messages, for the Terminal tab - separate from
    // latestPayloads, which keeps only the single newest payload per topic.
    private val _messageLog = MutableStateFlow<List<LoggedMessage>>(emptyList())
    val messageLog: StateFlow<List<LoggedMessage>> = _messageLog

    private fun keyFor(brokerId: String, topic: String) = "$brokerId|$topic"

    /**
     * "<baseTopic>/#" for every comma-separated base topic the broker declares - see
     * PermitJoin.parseBaseTopics's own doc on why a broker can watch more than one Zigbee2MQTT
     * namespace at once. A plain single value with no comma yields the same one-element list as
     * before.
     */
    private fun wildcardTopicsFor(broker: Broker): List<String> =
        PermitJoin.parseBaseTopics(broker.baseTopic).map { "$it/#" }

    /** Call whenever the persisted config changes (brokers added/removed, panels added/removed). */
    fun applyConfig(config: AppConfig) {
        val brokerIds = config.brokers.map { it.id }.toSet()
        connections.keys.filterNot { it in brokerIds }.toList().forEach { id ->
            connections.remove(id)?.disconnect()
            brokerById.remove(id)
            wildcardsByBrokerId.remove(id)
        }

        config.brokers.forEach { broker ->
            brokerById[broker.id] = broker
            val conn = connections.getOrPut(broker.id) { createConnection(broker) }
            if (broker.autoConnect) conn.connect()
            // Subscribed continuously, not just while Discover is open, so new "<topic>/app"
            // devices are detected in the background. Scoped to the broker's base topic(s), not
            // "#", since most people only want their Zigbee2MQTT namespace(s) on a shared broker.
            val wildcards = wildcardTopicsFor(broker)
            wildcardsByBrokerId[broker.id].orEmpty().filterNot { it in wildcards }.forEach { conn.unsubscribe(it) }
            wildcardsByBrokerId[broker.id] = wildcards
            wildcards.forEach { conn.subscribe(it) }
        }

        config.groups.flatMap { it.panels }.forEach { panel ->
            when (panel) {
                is Panel.Sensor -> {
                    connections[panel.brokerId]?.subscribe(panel.topic)
                    if (panel.idealRangeTopic.isNotBlank()) {
                        connections[panel.brokerId]?.subscribe(panel.idealRangeTopic)
                    }
                }
                is Panel.Toggle -> if (panel.stateTopic.isNotBlank()) {
                    connections[panel.brokerId]?.subscribe(panel.stateTopic)
                }
                // Momentary buttons only ever publish (on press) - there's no
                // feedback topic to subscribe to.
                is Panel.Button -> {}
            }
        }

        // Watch every autoconfigured device's topics explicitly, independent of current
        // panels - otherwise a device that later drops a field (e.g. ideal-range) would
        // silently stop being watched for config changes.
        config.autoConfiguredDevices.forEach { device ->
            connections[device.brokerId]?.subscribe(device.appConfigTopic)
            connections[device.brokerId]?.subscribe(device.sensorTopic)
        }

        configApplied = true
        pruneUnwatchedPayloads()
    }

    /**
     * Drops every cached payload of a configured broker that none of its current subscriptions
     * cover - e.g. one under a base topic since removed from the broker's settings. Nothing would
     * ever refresh or clear those again, yet Discover and new-device detection kept treating them
     * as live devices. Payloads of brokers without a connection are left alone: applyConfig also
     * runs at startup before the saved config has loaded (no brokers yet), and pruning against
     * that would wipe the whole cache.
     */
    private fun pruneUnwatchedPayloads() {
        // A connection not currently subscribed to its own base topics is between disconnectAll()
        // (backgrounded) and the next applyConfig - its empty subscription set means "not running",
        // not "watching nothing", so its payloads are left alone like an unconnected broker's.
        val filtersByBrokerId = connections
            .mapValues { (_, conn) -> conn.currentSubscriptions() }
            .filter { (id, filters) -> wildcardsByBrokerId[id].orEmpty().all { it in filters } && filters.isNotEmpty() }
        fun isWatched(key: String): Boolean {
            val filters = filtersByBrokerId[key.substringBefore('|')] ?: return true
            val topic = key.substringAfter('|')
            return filters.any { topicMatchesFilter(topic, it) }
        }
        val stale = _latestPayloads.value.keys.filterNot(::isWatched).toSet()
        if (stale.isEmpty()) return
        _latestPayloads.update { it - stale }
        _latestPayloadTimestamps.update { it - stale }
        schedulePersist()
    }

    /**
     * Subscribes a broker to its own "<baseTopic>/#" (every comma-separated one) so its retained
     * messages flow into [latestPayloads] for the Discover Sensors screen to scan. Safe to call
     * more than once - subscriptions are idempotent.
     */
    fun discoverAll(brokerId: String) {
        val broker = brokerById[brokerId] ?: return
        wildcardTopicsFor(broker).forEach { connections[brokerId]?.subscribe(it) }
    }

    /**
     * Discover's refresh button: drops payloads nothing watches any more, then re-sends SUBSCRIBE
     * for the broker's base topics so the broker replays every retained message under them (a
     * plain [discoverAll] is a no-op once subscribed). Connects first if the broker isn't.
     */
    fun rescan(brokerId: String) {
        val broker = brokerById[brokerId] ?: return
        val conn = connections[brokerId] ?: return
        pruneUnwatchedPayloads()
        if (conn.connectionState.value != ConnectionState.CONNECTED) conn.connect()
        wildcardTopicsFor(broker).forEach { conn.resubscribe(it) }
    }

    /** Backstop: keeps the cache fresh during long, quiet steady-state periods. */
    fun startPersistingCache() {
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(20_000.milliseconds)
                persistCacheNow()
            }
        }
    }

    // Debounced to ~2s so a burst (e.g. subscribing to "#") doesn't hammer disk, but still
    // writes soon after data arrives - a force-stopped app is SIGKILLed with no warning, so
    // writing early is the only real protection against losing a short session.
    private var persistJob: Job? = null

    private fun schedulePersist() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch(Dispatchers.Default) {
            delay(2_000.milliseconds)
            persistCacheNow()
        }
    }

    private fun persistCacheNow() {
        val payloads = _latestPayloads.value
        val timestamps = _latestPayloadTimestamps.value
        val merged = payloads.mapNotNull { (key, payload) ->
            val timestamp = timestamps[key] ?: return@mapNotNull null
            key to PayloadCacheEntry(payload, timestamp)
        }.toMap()
        payloadCacheRepository.save(merged)
    }

    private fun createConnection(broker: Broker): MqttConnection {
        val conn = MqttConnection(broker)
        scope.launch(Dispatchers.Default) {
            conn.connectionState.collect { state ->
                _connectionStates.update { it + (broker.id to state) }
            }
        }
        // Surfaces connection failures (initial and every background reconnect attempt) on the
        // Terminal tab, since that's otherwise the only place this app logs anything visible on
        // a TV with no logcat access - the Brokers list's state chip alone gave no reason why.
        scope.launch(Dispatchers.Default) {
            conn.connectionErrors.collect { reason ->
                val entry = LoggedMessage(broker.id, connectionErrorLabel, reason, System.currentTimeMillis())
                _messageLog.update { log ->
                    val updated = log + entry
                    if (updated.size > MAX_LOGGED_MESSAGES) updated.takeLast(MAX_LOGGED_MESSAGES) else updated
                }
            }
        }
        // limitedParallelism(1), not plain Dispatchers.Default, because the collect loop below
        // and its delayed flush() share plain (non-thread-safe) mutable buffers. On real hardware,
        // plain Default let them run concurrently and flush() hit a ConcurrentModificationException
        // mid-iteration over pendingPayloadUpdates while collect mutated it from another thread -
        // this confines both to one thread, race-free with no explicit locking.
        val messageProcessingDispatcher = Dispatchers.Default.limitedParallelism(1)
        scope.launch(messageProcessingDispatcher) {
            // Batched rather than updating the StateFlows per message - confirmed via adb logcat
            // on an underpowered TV under real household traffic that per-message updates caused
            // continuous GC pressure (each immutable-map `+` copies every tracked topic;
            // _messageLog copies up to 300 entries twice at cap). Batching a burst into one copy
            // per collection costs only a fraction of a second of added latency.
            val pendingPayloadUpdates = mutableMapOf<String, String>()
            val pendingPayloadRemovals = mutableSetOf<String>()
            val pendingTimestamps = mutableMapOf<String, Long>()
            val pendingLogEntries = mutableListOf<LoggedMessage>()
            var flushJob: Job? = null

            fun flush() {
                if (pendingPayloadUpdates.isNotEmpty() || pendingPayloadRemovals.isNotEmpty()) {
                    _latestPayloads.update { (it - pendingPayloadRemovals) + pendingPayloadUpdates }
                    _latestPayloadTimestamps.update { (it - pendingPayloadRemovals) + pendingTimestamps }
                    pendingPayloadUpdates.clear()
                    pendingPayloadRemovals.clear()
                    pendingTimestamps.clear()
                }
                if (pendingLogEntries.isNotEmpty()) {
                    _messageLog.update { log ->
                        val updated = log + pendingLogEntries
                        if (updated.size > MAX_LOGGED_MESSAGES) updated.takeLast(MAX_LOGGED_MESSAGES) else updated
                    }
                    pendingLogEntries.clear()
                }
                schedulePersist()
            }

            conn.messages.collect { msg ->
                val key = keyFor(broker.id, msg.topic)
                val now = System.currentTimeMillis()
                if (msg.payload.isEmpty()) {
                    // MQTT convention: an empty retained message means the topic's value was
                    // cleared - drop it entirely rather than storing blank, so e.g. a deleted
                    // backup disappears from the restore list.
                    pendingPayloadUpdates.remove(key)
                    pendingTimestamps.remove(key)
                    pendingPayloadRemovals += key
                } else {
                    pendingPayloadRemovals -= key
                    pendingPayloadUpdates[key] = msg.payload
                    pendingTimestamps[key] = now
                }
                pendingLogEntries += LoggedMessage(broker.id, msg.topic, msg.payload, now)
                onRetainProbeMessage(conn, key, msg)

                if (flushJob?.isActive != true) {
                    flushJob = scope.launch(messageProcessingDispatcher) {
                        delay(BATCH_INTERVAL_MS)
                        flush()
                    }
                }
            }
        }
        return conn
    }

    fun clearMessageLog() {
        _messageLog.value = emptyList()
    }

    fun publish(brokerId: String, topic: String, payload: String, retain: Boolean = false) {
        connections[brokerId]?.publish(topic, payload, retain)
    }

    /**
     * Drops [topic]'s cached payload straight away, exactly as if its empty "cleared" retained
     * message had just arrived - for a caller that is itself clearing that topic and can't afford
     * to wait for the broker's echo (plus the batching delay) before every reconcile pass stops
     * seeing it. See AutoConfigPush.retopicClusterAndPublish.
     */
    fun forgetPayload(brokerId: String, topic: String) {
        val key = keyFor(brokerId, topic)
        _latestPayloads.update { it - key }
        _latestPayloadTimestamps.update { it - key }
        schedulePersist()
    }

    /** Subscribes to one specific topic on a broker - used by the MQTT backup/restore screen. */
    fun subscribe(brokerId: String, topic: String) {
        connections[brokerId]?.subscribe(topic)
    }

    fun reconnect(brokerId: String) {
        connections[brokerId]?.reconnect()
    }

    /**
     * Restarts every auto-connect broker straight away instead of waiting out HiveMQ's reconnect
     * backoff - used once the local network permission is granted, since every attempt made
     * before then failed.
     */
    fun reconnectAll() {
        brokerById.values.filter { it.autoConnect }.forEach { connections[it.id]?.reconnect() }
    }

    /**
     * Disconnects every broker without forgetting it (`connections`/`brokerById` stay populated,
     * unlike [applyConfig]'s removal path) - a later [applyConfig] call (e.g. on foreground
     * resume) reconnects and re-subscribes each one from scratch safely either way.
     *
     * Used when backgrounded with "Background Work" off, since otherwise connections from
     * [com.odiousapps.z2mdash.Z2mDashApplication.onCreate] would run as long as the process survives.
     */
    fun disconnectAll() {
        disconnectedAllAtMillis = System.currentTimeMillis()
        connections.values.forEach { it.disconnect() }
    }
}

/** Standard MQTT topic filter matching - "+" matches one level, a trailing "#" any number (even none). */
internal fun topicMatchesFilter(topic: String, filter: String): Boolean {
    val topicLevels = topic.split('/')
    val filterLevels = filter.split('/')
    filterLevels.forEachIndexed { i, level ->
        if (level == "#") return true
        val topicLevel = topicLevels.getOrNull(i) ?: return false
        if (level != "+" && level != topicLevel) return false
    }
    return topicLevels.size == filterLevels.size
}
