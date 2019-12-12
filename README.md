# BLEFingerprinting
This Android app was developed in the context of a masters dissertation, with the objective of creating a navigation and localization system for a museum. Its purpose was to collect data in the museum space, to be used for the Fingerprinting training phase.

The UI allows the user to insert the fingerprint's location coordinates and start the data collection and registration. For each position, 30 fingerprints are collected with 100 ms interval. Each fingerprint has the following info:
- No; RSSI Beacon 1, ..., Beacon 4; accelerometer readings; gyroscope readings; magnetometer readings; azimuth

All the fingerprints collected are stored in a txt. file in the Downloads directory.

Note: Only the 4 selected BLE beacons measured RSSI value is part of the fingerprints. The devices' MAC address is stored in a separate file.
