package com.odiousapps.z2mdash.mqtt

import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PayloadCacheEntry
import com.odiousapps.z2mdash.data.PayloadCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Cap on how many recent messages the Terminal tab keeps around, oldest dropped first. */
private const val MAX_LOGGED_MESSAGES = 300

/** See createConnection()'s own comment on why incoming messages are batched at all. */
private val BATCH_INTERVAL_MS = 200.milliseconds

/** One entry in the Terminal tab's rolling message log. */
data class LoggedMessage(val brokerId: String, val topic: String, val payload: String, val timestamp: Long)

/**
 * App-wide singleton (held by Z2mDashApplication) that owns one MqttConnection
 * per configured broker, keeps subscriptions in sync with whatever panels exist,
 * and aggregates incoming payloads/connection state into simple StateFlows the
 * Compose UI can collect directly.
 */
class MqttConnectionManager(
    private val scope: CoroutineScope,
    private val payloadCacheRepository: PayloadCacheRepository
) {

    private val connections = mutableMapOf<String, MqttConnection>()
    private val brokerById = mutableMapOf<String, Broker>()

    private val _latestPayloads = MutableStateFlow<Map<String, String>>(emptyMap())
    val latestPayloads: StateFlow<Map<String, String>> = _latestPayloads

    // When (device time, i.e. System.currentTimeMillis() at receipt) each topic's
    // latest payload arrived - used to show "updated 2 hours ago" on the dashboard.
    private val _latestPayloadTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestPayloadTimestamps: StateFlow<Map<String, Long>> = _latestPayloadTimestamps

    init {
        // Seeded from disk so the dashboard has correct, already-varied "updated N ago" data to
        // show, before any MQTT traffic (even retained messages) has arrived this session -
        // without it, every topic would show completely blank until fresh data trickles in.
        // Loaded on a background dispatcher rather than the old synchronous call in this
        // constructor (which ran on whatever thread constructs this class - the main thread,
        // during Application.onCreate()): payloadCacheRepository.load() is real file I/O plus
        // JSON parsing of every topic's full payload string, and for a household with a lot of
        // devices that accumulated cache is large enough to block the main thread long enough to
        // skip hundreds of frames at cold start. The brief window before this completes just
        // shows those topics the same way a topic that genuinely hasn't reported yet this
        // session would, rather than freezing the whole UI until the read finishes.
        scope.launch(Dispatchers.IO) {
            val cached = payloadCacheRepository.load()
            if (cached.isNotEmpty()) {
                _latestPayloads.update { it + cached.mapValues { entry -> entry.value.payload } }
                _latestPayloadTimestamps.update { it + cached.mapValues { entry -> entry.value.timestamp } }
            }
        }
    }

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates

    // Rolling log of the most recent messages across every broker, for the
    // Terminal tab - separate from latestPayloads, which only keeps the
    // single newest payload per topic and loses everything else.
    private val _messageLog = MutableStateFlow<List<LoggedMessage>>(emptyList())
    val messageLog: StateFlow<List<LoggedMessage>> = _messageLog

    private fun keyFor(brokerId: String, topic: String) = "$brokerId|$topic"

    /** "<baseTopic>/#", defensively trimmed/defaulted in case the field is ever blank. */
    private fun wildcardTopicFor(broker: Broker): String {
        val base = broker.baseTopic.trim().trim('/').ifBlank { "zigbee2mqtt" }
        return "$base/#"
    }

    /** Call whenever the persisted config changes (brokers added/removed, panels added/removed). */
    fun applyConfig(config: AppConfig) {
        val brokerIds = config.brokers.map { it.id }.toSet()
        connections.keys.filterNot { it in brokerIds }.toList().forEach { id ->
            connections.remove(id)?.disconnect()
            brokerById.remove(id)
        }

        config.brokers.forEach { broker ->
            brokerById[broker.id] = broker
            val conn = connections.getOrPut(broker.id) { createConnection(broker) }
            if (broker.autoConnect) conn.connect()
            // Subscribed continuously (not just when the Discover screen is
            // open) so new "<topic>/app" devices can be detected and prompted
            // for in the background - scoped to the broker's own base topic
            // rather than the whole broker, since most people only want their
            // Zigbee2MQTT namespace, not every topic a shared broker carries.
            conn.subscribe(wildcardTopicFor(broker))
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

        // Keep watching every autoconfigured device's own topics explicitly,
        // independent of whether any current panel happens to reference them -
        // otherwise a device that later drops its ideal-range fields (for
        // example) could silently stop being watched for config changes.
        config.autoConfiguredDevices.forEach { device ->
            connections[device.brokerId]?.subscribe(device.appConfigTopic)
            connections[device.brokerId]?.subscribe(device.sensorTopic)
        }
    }

    /**
     * Subscribes a broker to its own "<baseTopic>/#" so its retained messages
     * flow into [latestPayloads] for the Discover Sensors screen to scan. Safe
     * to call more than once - subscriptions are idempotent.
     */
    fun discoverAll(brokerId: String) {
        val broker = brokerById[brokerId] ?: return
        connections[brokerId]?.subscribe(wildcardTopicFor(broker))
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

    // Debounced so a burst of messages (e.g. right after subscribing to "#")
    // doesn't hammer disk, but the *first* write still lands within ~2 seconds
    // of data actually arriving - important because a force-stopped app gets
    // SIGKILLed with zero warning, so the only real protection against losing
    // a short test session is writing early and often, not relying solely on
    // the 20-second backstop above.
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
        // Confined to a single thread - not just Dispatchers.Default, which is a pool of several -
        // since both the message-collecting loop below AND every delayed flush() it schedules
        // touch the same plain (non-thread-safe) mutable buffers. Confirmed via a real crash on
        // real hardware: with those two running as separate coroutines on plain Dispatchers.Default,
        // the pool is free to run them concurrently on two different threads, so flush() could be
        // mid-iteration over pendingPayloadUpdates (inside Map.plus, building the batched update)
        // at the exact moment the collect loop mutated it from a fresh message on another thread -
        // a textbook ConcurrentModificationException, which is exactly what a busy broker's real
        // traffic triggered here. limitedParallelism(1) gives a dispatcher backed by the same pool
        // but which only ever runs one task from it at a time, so everything below is effectively
        // single-threaded and race-free without needing explicit locking.
        val messageProcessingDispatcher = Dispatchers.Default.limitedParallelism(1)
        scope.launch(messageProcessingDispatcher) {
            // Buffered and flushed in small batches rather than applying each message to the
            // three StateFlows below immediately - confirmed on an underpowered TV device via
            // `adb logcat`, with a household-sized broker's worth of real traffic (busy enough to
            // cycle the 300-message Terminal log every couple of seconds), that doing this per
            // message was a real, continuous source of GC pressure: _latestPayloads and
            // _latestPayloadTimestamps are immutable maps, so `it + (key to value)` allocates a
            // full copy of *every* tracked topic (100+ for a household this size) on every single
            // incoming message, and _messageLog does the same for up to 300 entries *twice* once
            // it's at its cap (once for `log + entry`, again for `takeLast`). A burst of N
            // messages arriving within BATCH_INTERVAL_MS of each other now costs one copy of each
            // collection instead of N - the UI still updates well within what anyone would
            // perceive as "live" (a fraction of a second of added latency at most).
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
                    // Standard MQTT convention: an empty retained message means
                    // "this topic's retained value was cleared" - drop it from
                    // our own state entirely rather than storing a blank value,
                    // so e.g. a deleted MQTT backup actually disappears from
                    // the restore list instead of lingering as an empty entry.
                    pendingPayloadUpdates.remove(key)
                    pendingTimestamps.remove(key)
                    pendingPayloadRemovals += key
                } else {
                    pendingPayloadRemovals -= key
                    pendingPayloadUpdates[key] = msg.payload
                    pendingTimestamps[key] = now
                }
                pendingLogEntries += LoggedMessage(broker.id, msg.topic, msg.payload, now)

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

    /** Subscribes to one specific topic on a broker - used by the MQTT backup/restore screen. */
    fun subscribe(brokerId: String, topic: String) {
        connections[brokerId]?.subscribe(topic)
    }

    fun reconnect(brokerId: String) {
        connections[brokerId]?.apply {
            disconnect()
            connect()
        }
    }

    /**
     * Disconnects every currently-known broker without forgetting them (unlike [applyConfig]'s
     * own removal path, `connections`/`brokerById` are left populated) - a later [applyConfig]
     * call (e.g. when the app returns to the foreground) reconnects and fully re-subscribes each
     * one from scratch, since [MqttConnection.disconnect] clears its own subscribed-topics set
     * and [MqttConnection.connect] is a no-op once already connected, making that resume-time
     * call always safe regardless of whether this was actually invoked first.
     *
     * Used when the app is backgrounded and the user has "Background Work" turned off - without
     * this, connections established in [com.odiousapps.z2mdash.Z2mDashApplication.onCreate] run
     * for as long as the process happens to survive regardless of that setting, since nothing
     * else in the app ever tears them down on its own.
     */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
    }
}
