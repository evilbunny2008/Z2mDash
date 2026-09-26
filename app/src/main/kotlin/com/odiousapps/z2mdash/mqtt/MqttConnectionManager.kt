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
            // Subscribed continuously, not just while Discover is open, so new "<topic>/app"
            // devices are detected in the background. Scoped to the broker's base topic, not
            // "#", since most people only want their Zigbee2MQTT namespace on a shared broker.
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

        // Watch every autoconfigured device's topics explicitly, independent of current
        // panels - otherwise a device that later drops a field (e.g. ideal-range) would
        // silently stop being watched for config changes.
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
                val entry = LoggedMessage(broker.id, "⚠ connection error", reason, System.currentTimeMillis())
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
     * Disconnects every broker without forgetting it (`connections`/`brokerById` stay populated,
     * unlike [applyConfig]'s removal path) - a later [applyConfig] call (e.g. on foreground
     * resume) reconnects and re-subscribes each one from scratch safely either way.
     *
     * Used when backgrounded with "Background Work" off, since otherwise connections from
     * [com.odiousapps.z2mdash.Z2mDashApplication.onCreate] would run as long as the process survives.
     */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
    }
}
