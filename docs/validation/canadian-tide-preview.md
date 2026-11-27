# Canadian tide preview downloads

On November 7, 2026, the Android API 37 emulator fetched official water-level predictions directly from IWLS for Victoria (Inner Harbour) and Vancouver (Burrard Inlet). Each response supplied 673 samples at 15-minute intervals, from November 5 at 08:00 UTC through November 12 at 08:00 UTC. This is the seven-day request window anchored 48 elapsed hours before the station’s local midnight.

Victoria’s request was saved from its station detail in airplane mode. The app was force-stopped, networking restored, and the app reopened. Its restored queue published the preview. After another force-stop, with airplane mode enabled and Wi-Fi and mobile data disabled, opening the station displayed its chart from the same saved samples.

Vancouver’s request was saved on metered cellular with Android Data Saver enabled. The queue reported that it was waiting for an unrestricted network. After a force-stop it resumed on Wi-Fi, with Data Saver still enabled. A fresh process displayed Vancouver’s chart with both radios disabled. The test checks the stored coverage and finds the chart through the station list and search.

## Reproducing the live checks

Use a disposable emulator where the selected station has no saved model. The normal CI suite uses fixtures. To run the live check, select `CanadianTidePreviewDeviceTest` with the instrumentation argument `previewPhase`. Use `queueOffline` with both radios disabled, then force-stop, enable Wi-Fi and run `download`. Force-stop again, disable both radios and run `offline`. For the metered variant, pass `previewStation=vancouver` for all phases and use `queueRestricted` with Wi-Fi off, mobile data on, and Data Saver enabled. Restore the device’s network settings afterward.

The queue runs while a Slackwater activity is started. Durable requests survive the app leaving the foreground or the process stopping. A preview supports the dates covered by its samples. Preview requests have their own persisted key. Completing, cancelling or retrying a preview leaves a gate’s request for its paired tide fit intact, including requests saved by earlier versions. On upgrade, unambiguous direct tide requests also enroll once for a preview. The store remembers the general request IDs last written by a preview-capable build, so a new direct request made during a downgrade enrolls on the next upgrade. A legacy request for a tide shared with a gate remains a fit request, and the station page can request its preview separately. An incomplete IWLS response preserves the existing model and exposes a manual retry. The foreground queue fits Canadian tides and eligible current gates. A derived gate can request its reference tide fit without a preview. Cancelling the station in the Downloads manager clears both requests. Bulk download tiers remain part of [Android #9](https://github.com/openwatersio/slackwater-android/issues/9).

Cancel a station inside an active download radius, then reopen the app. Its automatic fit stays cancelled. Tap Download on the station to resume it.

An automatic fit that fails on a transient error shows "Waiting to retry" in Downloads and on its station page, with Retry now and Cancel. It retries after 60 seconds, doubling to 15 minutes, or as soon as a newly validated connection arrives. The row leaves when the station leaves the selection or the app leaves the foreground, and returns while its backoff runs. Retrying a reference tide that a derived gate needs repeats only the gate's reference fit. All open activities share one automatic queue.

A download the person requested shows the same "Waiting to retry" row after a transient error, with the same backoff, and Retry now restarts it at once. Its "Download failed" row means the download will not retry by itself.

A station whose automatic fit failed for good also shows "Download failed". The automatic queue tries it once more each time the person comes back to the app, including while a background run is still going. During a background run a rotation is not a return; without one, a rotation restarts the queue and tries the station again. iOS retries a failed station only from its Retry button.

While a requested download for a station is running or waiting, the automatic queue leaves that station alone and does not count it as ready. When the request ends, the queue checks the station and fits it if the request left it without a fitted model, as a preview-only request does. A request that failed for good is not tried again then: its station counts as failed until the next return to the app.
