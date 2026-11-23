# Nextcloud Health synchronization

Fit3 App can synchronize health data with the self-hosted
[Nextcloud Health](https://github.com/nextcloud/health) app. The integration uses Health's
versioned OCS API v2; it does not require a custom server endpoint.

## Requirements

- A Nextcloud server reachable through HTTPS.
- Nextcloud Health v3 with API v2 enabled.
- A device-specific app password created in the user's Nextcloud personal security settings.

The user enters the Nextcloud base URL, login name, and app password in Fit3 App. The app
password is encrypted with an AES-GCM key held by Android Keystore before it is saved. The
account password is neither requested nor stored.

Every request uses HTTP Basic authentication and the required OCS headers:

```http
Accept: application/json
OCS-APIRequest: true
Authorization: Basic <login-name-and-app-password>
```

Fit3 App checks the connection with:

```http
GET /ocs/v2.php/apps/health/api/v2/configuration
```

## Metric mapping

Only measurements with an equivalent Nextcloud Health metric are synchronized:

| Fit3 value | Nextcloud Health API |
| --- | --- |
| Daily steps | `PUT /ocs/v2.php/apps/health/api/v2/daily-values/steps/{date}` with unit `steps` |
| Heart rate | `POST /ocs/v2.php/apps/health/api/v2/measurements` as `pulse`, unit `bpm` |
| Blood oxygen | `POST /ocs/v2.php/apps/health/api/v2/measurements` as `oxygen_saturation`, unit `percent` |
| Stress | `POST /ocs/v2.php/apps/health/api/v2/entries` as the `stress` journal metric |

Fit3 stress is rescaled into five equal bands for Nextcloud Health:

- Fit3 1–20 → Nextcloud 1
- Fit3 21–40 → Nextcloud 2
- Fit3 41–60 → Nextcloud 3
- Fit3 61–80 → Nextcloud 4
- Fit3 81–100 → Nextcloud 5

A Fit3 value of 0 is treated as 1. Sleep, distance, floors, and active minutes are not currently
exposed as compatible Health v3 metrics. Active calories are a cumulative daily value, while
Nextcloud expects atomic kilocalorie measurements, so sending the Fit3 total repeatedly would
inflate the sum.

## Retry behavior

While the band is connected, Fit3 App requests health data approximately every five minutes.
Immediately after each successful watch health response, the newest supported values are queued
for Nextcloud. A failed upload remains queued and is retried every five minutes while the
connection service runs. The **Sync now** button queues the current values immediately.

Each run sends the step total for the Fit3 record's current day plus the latest pulse, oxygen
saturation, and stress readings held in the snapshot. It does not backfill older local measurement
history.

Daily steps use Nextcloud's idempotent daily-value `PUT`. Pulse, oxygen saturation, and stress
requests carry deterministic UUID v4 `operationId` values derived from their metric and watch
timestamp. This lets Nextcloud return an existing record when a request is retried instead of
creating a duplicate.

If newer Fit3 data arrives while an upload is in progress, it replaces the queued snapshot and is
sent immediately after the current request finishes.
