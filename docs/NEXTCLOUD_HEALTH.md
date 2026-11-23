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

Fit3 stress is not sent because its 0–100 score is not equivalent to Nextcloud Health's 1–5
journal scale. Sleep, distance, floors, and active minutes are not currently exposed as compatible
Health v3 metrics. Active calories are a cumulative daily value, while Nextcloud expects atomic
kilocalorie measurements, so sending the Fit3 total repeatedly would inflate the sum.

## Retry behavior

After every successful watch health sync, the newest supported Fit3 values are queued. A failed
upload remains queued and is retried every five minutes while the connection service runs.

Daily steps use Nextcloud's idempotent daily-value `PUT`. Pulse and oxygen saturation requests
carry deterministic UUID v4 `operationId` values derived from their metric and watch timestamp.
This lets Nextcloud return an existing measurement when a request is retried instead of creating
a duplicate.

If newer Fit3 data arrives while an upload is in progress, it replaces the queued snapshot and is
sent immediately after the current request finishes.
