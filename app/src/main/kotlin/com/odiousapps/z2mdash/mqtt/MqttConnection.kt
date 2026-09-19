package com.odiousapps.z2mdash.mqtt

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.MqttProtocol
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class IncomingMessage(val topic: String, val payload: String)

/**
 * Wraps a single broker connection using the HiveMQ MQTT client.
 *
 * Fixes "websockets disconnect every 5 minutes": Paho's Android websocket transport has a
 * keepalive scheduling bug that silently drops the connection under Doze/background network
 * throttling. HiveMQ manages its own ping/reconnect state machine (jittered backoff) across
 * TCP+TLS and WS/WSS without that bug.
 */
class MqttConnection(private val broker: Broker) {

    private var client: Mqtt3AsyncClient? = null
    private val subscribedTopics = mutableSetOf<String>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 128)
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    fun connect() {
        if (client != null) return
        _connectionState.value = ConnectionState.CONNECTING

        val builder: Mqtt3ClientBuilder = Mqtt3Client.builder()
            .identifier(broker.clientId.ifBlank { "z2mdash-${System.currentTimeMillis()}" })
            .serverHost(broker.host)
            .serverPort(broker.port)

        builder.automaticReconnect()
            .initialDelay(1, TimeUnit.SECONDS)
            .maxDelay(30, TimeUnit.SECONDS)
            .applyAutomaticReconnect()

        when (broker.protocol) {
            MqttProtocol.TCP -> {}
            MqttProtocol.SSL -> applySsl(builder)
            MqttProtocol.WS -> applyWebSocket(builder)
            MqttProtocol.WSS -> {
                applyWebSocket(builder)
                applySsl(builder)
            }
        }

        if (broker.authEnabled) {
            builder.simpleAuth()
                .username(broker.username)
                .password(broker.password.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
        }

        val builtClient = builder.buildAsync()
        client = builtClient

        // Global callback covers every subscribed topic, so no per-subscribeWith() callback
        // is needed and resubscribing after a reconnect "just works".
        builtClient.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            _messages.tryEmit(IncomingMessage(publish.topic.toString(), payload))
        }

        builtClient.connectWith()
            .cleanSession(broker.cleanSession)
            .keepAlive(broker.keepAliveSeconds)
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    _connectionState.value = ConnectionState.FAILED
                } else {
                    _connectionState.value = ConnectionState.CONNECTED
                    subscribedTopics.toList().forEach { doSubscribe(it) }
                }
            }
    }

    private fun applyWebSocket(builder: Mqtt3ClientBuilder) {
        builder.webSocketConfig()
            .serverPath(broker.webSocketPath.ifBlank { "/mqtt" }.removePrefix("/"))
            .applyWebSocketConfig()
    }

    private fun applySsl(builder: Mqtt3ClientBuilder) {
        val certBase64 = broker.selfSignedCertBase64
        if (broker.selfSignedCert && !certBase64.isNullOrBlank()) {
            builder.sslConfig()
                .trustManagerFactory(SslUtils.trustManagerFactoryFromCertBase64(certBase64))
                .applySslConfig()
        } else {
            // sslWithDefaultConfig() mutates builder in place and returns the same
            // @CheckReturnValue-annotated builder; .let{} just consumes that return
            // value to satisfy the check, without changing behaviour.
            builder.sslWithDefaultConfig().let { }
        }
    }

    fun subscribe(topic: String) {
        if (topic.isBlank()) return
        // Skip resending SUBSCRIBE for an already-tracked topic - brokers redeliver every
        // matching retained message on every subscribe (even repeats), which was resetting
        // this app's "updated N ago" displays. Frequent during auto-discovery's config churn.
        val isNewSubscription = subscribedTopics.add(topic)
        if (isNewSubscription && _connectionState.value == ConnectionState.CONNECTED) {
            doSubscribe(topic)
        }
    }

    private fun doSubscribe(topic: String) {
        client?.subscribeWith()
            ?.topicFilter(topic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        if (topic.isBlank()) return
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray(StandardCharsets.UTF_8))
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.retain(retain)
            ?.send()
    }

    fun disconnect() {
        client?.disconnect()
        client = null
        subscribedTopics.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
