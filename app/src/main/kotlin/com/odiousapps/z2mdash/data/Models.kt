package com.odiousapps.z2mdash.data

import kotlinx.serialization.Serializable

@Serializable
enum class MqttProtocol { TCP, SSL, WS, WSS }

@Serializable
data class Broker(
    val id: String,
    val name: String,
    val host: String,
    val protocol: MqttProtocol = MqttProtocol.TCP,
    val port: Int = 1883,
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val selfSignedCert: Boolean = false,
    // Raw certificate bytes (PEM or DER), Base64-encoded to round-trip through plain JSON.
    val selfSignedCertBase64: String? = null,
    val webSocketPath: String = "/mqtt",
    val clientId: String = "android_dashboard_${(10000..99999).random()}",
    val cleanSession: Boolean = false,
    val keepAliveSeconds: Int = 60,
    val connectionTimeoutSeconds: Int = 30,
    val autoConnect: Boolean = true,
    val showReconnectionStatus: Boolean = true,
    // Scopes the app's automatic "#" discovery/watch subscription to "<baseTopic>/#" instead of
    // the entire broker, since most people only want their Zigbee2MQTT namespace.
    val baseTopic: String = "zigbee2mqtt",
    // When on, a newly-seen "<topic>/app" on this broker is auto-configured immediately
    // (DeviceAutoConfigManager) instead of prompting via the Home screen banner. Off by default
    // so an unfamiliar broker's traffic doesn't populate the dashboard unasked. Settable via a
    // credential import's "AutoAccept" field - see CredentialImportDialog.
    val autoAcceptDiscoveredDevices: Boolean = false,
    // Friendly name last used for the "Permit Join" toggle's optional "device" field (see
    // AddEditBrokerScreen) - blank permits joining via every router and the coordinator at once.
    // Remembered per broker purely for convenience.
    val permitJoinDevice: String = ""
)

// New entries only ever get appended - stored panels reference these by name (see PanelStore/
// Backup), so reordering or renaming an existing entry would silently break saved configs.
@Serializable
enum class TileIcon {
    HUMIDITY, MOISTURE, TEMPERATURE, SIGNAL, POWER, GAUGE, BATTERY, LIGHT, PRESENCE,
    CONTACT, LOCK, WATER_LEAK, SMOKE, GAS, VIBRATION, AIR_QUALITY, SIREN, WARNING,
    BUTTON, OUTLET, ENERGY, COLOR, COVER, CLIMATE, FAN, ROUTER, VALVE, DOORBELL
}

@Serializable
sealed class Panel {
    abstract val id: String
    abstract val label: String
    abstract val brokerId: String
    // Optional. Panels sharing the same non-blank clusterName within a group render together in
    // one bordered card named underneath - e.g. all tiles for one physical sensor device.
    abstract val clusterName: String
    // Left-to-right/top-to-bottom position within a group (lower sorts first). Unordered
    // panels/clusters default to Int.MAX_VALUE, falling in after anything explicitly ordered.
    abstract val displayOrder: Int

    @Serializable
    @Suppress("unused", "RedundantSuppression")
    data class Sensor(
        override val id: String,
        override val label: String,
        override val brokerId: String,
        val topic: String,
        // Dot path into a JSON payload, e.g. "temperature" or "state.battery". Blank displays
        // the raw payload as-is.
        val jsonPath: String = "",
        val unit: String = "",
        val icon: TileIcon = TileIcon.GAUGE,
        val decimals: Int = 0,
        // Optional companion topic publishing the ideal range (retained) - tile flashes red
        // below min / green within / blue above max. Defaults to {"min":x,"max":y};
        // idealMinPath/idealMaxPath let it point at differently-named sibling fields instead.
        val idealRangeTopic: String = "",
        val idealMinPath: String = "min",
        val idealMaxPath: String = "max",
        // When true, this tile's value isn't a live hardware reading but a fixed preference an
        // automation script published (e.g. a soil moisture min/max threshold) - tapping it opens
        // a number entry that republishes the new value, retained, to this same topic/jsonPath.
        // Off by default: flipping it on for a genuine sensor reading would just get overwritten
        // by the next real update, so it's an opt-in per panel via AddPanelScreen.
        val editable: Boolean = false,
        override val clusterName: String = "",
        override val displayOrder: Int = Int.MAX_VALUE
    ) : Panel()

    @Serializable
    @Suppress("unused", "RedundantSuppression")
    data class Toggle(
        override val id: String,
        override val label: String,
        override val brokerId: String,
        val commandTopic: String,
        val onPayload: String = "ON",
        val offPayload: String = "OFF",
        // Optional feedback topic/path so the switch reflects the device's real state
        // rather than only the last command sent.
        val stateTopic: String = "",
        val stateJsonPath: String = "",
        val icon: TileIcon = TileIcon.POWER,
        override val clusterName: String = "",
        override val displayOrder: Int = Int.MAX_VALUE
    ) : Panel()

    // A momentary action with no on/off state, e.g. "Stop" on a blind motor - unlike Toggle,
    // tapping it just sends payload to commandTopic every time.
    @Serializable
    @Suppress("unused", "RedundantSuppression")
    data class Button(
        override val id: String,
        override val label: String,
        override val brokerId: String,
        val commandTopic: String,
        val payload: String = "",
        val icon: TileIcon = TileIcon.POWER,
        override val clusterName: String = "",
        override val displayOrder: Int = Int.MAX_VALUE
    ) : Panel()
}

@Serializable
data class PanelGroup(
    val id: String,
    val name: String,
    val panels: List<Panel> = emptyList(),
    val collapsed: Boolean = false
)

/**
 * Tracks a device configured via its own "<topic>/app" payload, so the app can keep watching
 * that topic and regenerate panels if the device republishes a changed config.
 */
@Serializable
data class AutoConfiguredDevice(
    val brokerId: String,
    val sensorTopic: String,
    val appConfigTopic: String,
    // Raw payload last applied - compared against the live value to detect changes.
    val lastAppliedPayload: String,
    // IDs of the panels this device currently owns, so a reconfigure can cleanly remove the
    // old set before adding the new one.
    val createdPanelIds: List<String> = emptyList(),
    // Highest "order_version" (publish-time epoch-millis) adopted for this device's
    // group_order/panel_order - lets phones sharing a broker do last-writer-wins on
    // drag-reorder: an incoming payload's order is only adopted when its order_version is
    // strictly newer, so a stale retained redelivery can't undo a more recent reorder.
    // Defaults to 0 so any versioned payload is adopted the first time a phone sees it.
    val lastKnownOrderVersion: Long = 0L,
    // Last-adopted "dashboard_order" (see SensorDiscovery.DeviceAppConfig.dashboardOrder) for
    // this device's own top-level dashboard group - gated by lastKnownOrderVersion the same way,
    // so it only changes on a genuinely newer payload, never a stale retained redelivery. Kept
    // here (rather than re-parsed from lastAppliedPayload on demand) so
    // ConfigRepository.resyncDashboardGroupOrder can resort every dashboard group from these
    // already-vetted per-device values, converging to the same result regardless of the order
    // devices/payloads happen to arrive in - notably right after wiping app data, when a flood of
    // retained "/app" messages can arrive in any order. Null until a payload with one is adopted.
    val lastKnownDashboardOrder: Int? = null
)

/**
 * A "<topic>/app" seen for the first time (parses successfully, not already autoconfigured or
 * dismissed) - waiting on the user to accept or ignore it via a Home screen banner/notification.
 */
@Serializable
data class PendingAutoConfigDevice(
    val brokerId: String,
    val sensorTopic: String,
    val appConfigTopic: String,
    val deviceName: String
)

@Serializable
data class AppConfig(
    val brokers: List<Broker> = emptyList(),
    val groups: List<PanelGroup> = emptyList(),
    // Off by default: since payloads are retained, the broker delivers current state on connect
    // regardless of background running, so a persistent foreground connection is opt-in, not
    // required for correct data.
    val backgroundWorkEnabled: Boolean = false,
    val autoConfiguredDevices: List<AutoConfiguredDevice> = emptyList(),
    val pendingAutoConfigDevices: List<PendingAutoConfigDevice> = emptyList(),
    // "brokerId|appConfigTopic" keys the user has dismissed, so a declined device isn't
    // re-prompted on every scan.
    val ignoredAppConfigTopics: List<String> = emptyList(),
    // Watches every incoming payload, any topic, for a JSON "smoke": true field - not scoped to
    // a configured panel, so nothing is missed just because a detector wasn't added. On by default.
    val smokeAlertsEnabled: Boolean = true,
    val smokeAlertSoundEnabled: Boolean = true,
    // Whether the one-time decimals migration (see ConfigRepository.load) has already run -
    // without this flag it would re-apply every launch and overwrite a deliberate later choice.
    val decimalsMigrationApplied: Boolean = false,
    // Remembered so the export password dialog is pre-filled next time. Stored in plaintext,
    // same as broker passwords elsewhere in this config file.
    val rememberedExportPassword: String? = null,
    // Red/blue flash on a sensor tile outside its ideal range. Does NOT control the cluster
    // card's stale-data indicator (that blink wasn't noticeable enough to be worth it, and is
    // now always static). When false, an out-of-range tile still shows its warning colour, static
    // rather than pulsing.
    val staleDataBlinkEnabled: Boolean = true,
    // Caps each tile's width on the Home screen; the smaller of this or the even-fill-at-3-per-row
    // width is used. 110 was this app's original hardcoded constant, kept as the default.
    val tileWidthDp: Int = 110,
    // How long the "Tile moved" / "Cluster moved" / "Group moved" undo snackbar stays on screen
    // after a drag-reorder, in seconds - long enough to catch an accidental drag without lingering
    // forever. 0 disables the undo snackbar entirely.
    val undoToastSeconds: Int = 10
)
