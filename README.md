## Z2M Dash

An Android dashboard app for Zigbee2MQTT, built to replace closed-source apps.
No cloud, no account, no lock-in - your whole configuration is either one plain
JSON file you can export and re-import or auto-configure settings stored in MQTT.

## Features

### Dashboard
- Groups of clusters, each cluster grouping the related tiles for one physical
  device (sensor readings, toggles/buttons) under a shared name
- Sensor tiles with configurable decimal places, units, and an optional ideal
  min/max range that colours the tile red/green/blue when out of range
- Toggle tiles reflecting live device state (not just the last command sent),
  and momentary button tiles for one-shot commands like a blind motor's STOP
- Presence/occupancy sensor tiles - recognises both Zigbee2MQTT's "occupancy"
  and "presence" conventions, shown as "Detected"/"Clear" with a tinted icon
  rather than a raw true/false
- Drag-and-drop reordering for groups, clusters within a group, and panels
  within a cluster (long-press to start dragging) - group reordering and
  renaming both happen directly on the dashboard via each group's own
  header, no separate screen needed
- Groups can be collapsed/expanded, and deleted along with their panels
- New-device and new-cluster prompts (see Auto-configure below) show as
  banners on the dashboard as well as system notifications

### Auto-configure via MQTT
Devices (or a small companion script) can publish their own dashboard config
to a `<topic>/app` MQTT topic - the app detects it automatically and prompts
to add the described panels, no manual per-device setup required. Supports:
- Sensor panels for any field in the device's regular state payload
- Toggle and momentary-button controls (a control without an `off_payload` is
  treated as a single-press button rather than an on/off switch)
- Per-field decimal places, custom ordering within and across clusters, and
  splitting one physical device's fields across multiple clusters
- A manual reorder of auto-configured panels/clusters republishes an updated,
  retained `/app` message reflecting the new arrangement, so the device's own
  config stays in sync rather than being silently reverted on its next update

### Smoke alerts
Watches every incoming MQTT payload, across every topic, for a `"smoke": true`
field - no per-device registration needed. Posts a high-priority notification
(with an optional loud, alarm-style sound) the moment any topic's smoke state
transitions to true, and clears it automatically once smoke is no longer
reported. Both the alert and the sound can be turned off independent of
their own Alarm/Alert screen (Settings), which also has a test button that
fires a real notification (and sound, if enabled) so you can confirm it'll
actually get your attention before you need it to.

### Temperature alerts
Tap a temperature tile's reading to set a high and/or low limit, and get a
notification when the reading rises or drops to it - e.g. a sensor in a window,
so it can be shut before the room heats up. Each limit notifies once per
excursion, then stays quiet until the reading has come back by 1° (and for at
least 8 hours).

While the app is closed, these alerts keep working one of two ways:
- **Background Work** (Settings) keeps a live broker connection open, so alerts
  are instant - at the cost of a permanent notification and some battery
- **Periodic Checks** (Settings, on by default) instead connects roughly every
  15 minutes (Android can stretch this to hours overnight while the phone is
  idle), reads each sensor's last reading, then disconnects again

#### Periodic Checks need retain turned on in Zigbee2MQTT
A periodic check only sees a reading the broker kept, which Zigbee2MQTT does
only for devices with `retain` turned on - otherwise nothing arrives until the
sensor next reports, long after the check has finished. The app detects sensors
that aren't retained and warns about them (on the dashboard, and under Alerts
in Settings). To turn it on in Zigbee2MQTT, either:
- per device: Devices > (the sensor) > Settings > Retain, or
- for every device, in `configuration.yaml`:

  ```yaml
  device_options:
    retain: true
  ```

### Terminal
A live, filterable log of every MQTT message across all configured brokers -
useful for debugging payloads and topic structures without a separate MQTT
client.

### Backup & restore
Its own screen (Settings > Backup & Restore), separate for export and restore:
- Export/import the whole configuration, or just the brokers list on its own
  (for moving connection details between devices without touching groups or
  panels), as a single gzip-compressed JSON file
- A restore can replace the whole configuration, or - independently of how
  the file was originally exported - merge in just its brokers (matched by
  id: updates existing ones, adds new ones, leaves the rest of your setup
  untouched)
- Full-configuration exports can optionally be encrypted (AES-256-GCM) using
  one of your broker's own passwords, rather than a separate password to
  remember - restoring auto-detects an encrypted file and prompts for the
  password before decrypting
- Publish/restore a backup via a retained MQTT message instead, for
  moving configuration between devices without a file transfer - since that
  path never includes brokers in the backup at all, restoring from it always
  preserves whatever brokers are already configured on this device rather
  than needing to import them separately

### Brokers & connections
- Multiple brokers, each over TCP, SSL, WS, or WSS (including self-signed
  certificates)
- A persistent background connection (optional, toggle in Settings) keeps
  data fresh even when the app isn't open - though since MQTT topics are
  typically retained, this isn't strictly required for correctness, just for
  live updates while backgrounded
- Manual sensor discovery, scanning a broker's traffic for numeric fields to
  help build panels for devices that don't publish their own `/app` config -
  launched per-broker (from that broker's row in Settings > Brokers), since
  you may only want to scan one specific broker rather than every one at once

## Only tested using Zigbee2MQTT

I have only tested this against Zigbee2MQTT, I have no idea how much it will work
when there is other software in the mix, like Home Assistant

## Screenshots

<img width="300px" src="https://github.com/evilbunny2008/Z2mDash/blob/main/design/Screenshot_20260823-223346.png?raw=true"> <img width="300px" src="https://github.com/evilbunny2008/Z2mDash/blob/main/design/Screenshot_20260823-231443.png?raw=true"><br><br><br>
<img width="600px" src="https://github.com/evilbunny2008/Z2mDash/blob/main/design/Screenshot_20260825-034747.png?raw=true">

## Copyright/License

As this was entirely vibe coded using [Claude.ai](https://claude.ai) the code isn't copyrightable by me.
