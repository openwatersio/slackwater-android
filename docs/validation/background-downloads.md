# Background downloads

On November 19, 2026, the debug build on the Android API 37 emulator continued Canadian downloads after leaving the screen. The device location was Victoria, British Columbia, with no saved models. Selecting **Within 25 km** in Downloads scheduled the user-initiated data transfer job while the app was visible. JobScheduler listed it at maximum priority with a foreground exemption. Its network constraint was `INTERNET & TRUSTED & VALIDATED`, with no `NOT_VPN`. The notification read "Downloading" and "Stations ready: 0 of 20", with Cancel.

After pressing Home, the count kept rising: 3 of 19 after two minutes. One candidate left the total because the queue cannot fit it. A run left alone went from 6 to 16 of 18 in five minutes and then finished on its own. Every station in the list then showed a reading, including ones that had shown "Not downloaded".

**Within 150 km** gave 100 candidates. Its run went on in the background for 13 minutes, past the ten minutes a location fix lasts. It held 97 to 99 stations throughout, measured from the held fix, and reached 20 ready.

Cancel in the notification removed the job and the notification, both with the app in the background and with it on screen. With the app on screen, no new job was scheduled during the next 80 seconds while the foreground queue went on downloading. Rotating the screen after Cancel did not schedule one either. Leaving the app and opening it again did.

`adb shell cmd jobscheduler timeout` stops the job as the system would. With the app on screen, a new run was running eight seconds later. With the app in the background, no job remained. After the app's process was killed with `kill -9` through `run-as`, as a low-memory kill would be, JobScheduler kept the job. Forcing it with `adb shell cmd jobscheduler run -f` started it in a fresh process, which ended it at once, with no notification and no job left.

With airplane mode on, selecting Within 25 km and pressing Home still scheduled the job, held by its unmet connectivity constraint. Turning airplane mode off started it as a user-initiated job, and it reached 1 of 20 within 90 seconds.

Not yet covered: a physical handset, the Task Manager's Stop, a quota limit, Data Saver on a metered network, an always-on VPN, and a station that keeps failing for the full 30 minutes. Android 13 and earlier never schedule the job.

## Behaviors kept as they are

These were raised as low-severity observations when the queue and the background run were reviewed. Each was looked at again and left alone.

- **A manual tide download at a reference port can start a run when only All current stations is selected.** A reference port's tide fit is what its derived gates are fitted through, so the queue counts it as current work whoever asked for it. The run then continues a download the person requested.
- **The held origin has no age limit while location is allowed.** iOS `LocationService.rankingAnchor` also uses its last fix for as long as location is authorized, with no age limit. The origin is dropped once the app reads location as no longer allowed, which it does only while on screen. A run going at that moment loses its radius.
- **A requested download that keeps failing on transient errors holds a run open until the 30-minute stall limit.** It counts as waiting to retry, as an automatic one does. While it waits, the notification and the list's Downloads row can show the count, not "Waiting to retry…".
- **Retry on a reference port's own page repeats only the reference fit after that fit failed for good.** That fit is the port's tide model, so the page gets its predictions from it. Cancel, then Download, also fetches the seven-day preview first.
- **If the app's network watcher and JobScheduler disagreed for a long time, a visible app would schedule a new run about once a minute.** Each run would end after its 60-second startup window. Both read the default network's validated state, and the job's constraint is `INTERNET & TRUSTED & VALIDATED`. No such disagreement has been seen, and it can only happen while the app is on screen.
- **The run, the scheduling and the Task Manager's Stop have no automated test.** A user-initiated job can be scheduled only by a visible app, and the run needs IWLS. `BackgroundDownloadsTest` covers the notification's text and progress and its Cancel; the live checks above cover the rest.

## Reproducing

Use a disposable API 34 or later emulator and clear the app's data. Grant location and notifications, run `adb emu geo fix -123.37 48.42`, and open the app until the list shows Victoria. In Downloads, select Within 25 km, then press Home. Watch `adb shell dumpsys jobscheduler` for job 2403 and `adb shell dumpsys notification --noredact` for notification 2403. To stop the job as the system would, run `adb shell cmd jobscheduler timeout io.openwaters.slackwater.android 2403`. Counts depend on the catalog and the IWLS response.
