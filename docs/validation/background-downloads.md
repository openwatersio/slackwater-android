# Background downloads

On November 19, 2026, the debug build on the Android API 37 emulator continued Canadian downloads after leaving the screen. The device location was Victoria, British Columbia, with no saved models. Selecting **Within 25 km** in Downloads scheduled the user-initiated data transfer job while the app was visible. JobScheduler listed it at maximum priority with a foreground exemption. Its network constraint was `INTERNET & TRUSTED & VALIDATED`, with no `NOT_VPN`. The notification read "Downloading" and "Stations ready: 0 of 20", with Cancel.

After pressing Home, the count kept rising: 3 of 19 after two minutes. One candidate left the total because the queue cannot fit it. A run left alone went from 6 to 16 of 18 in five minutes and then finished on its own. Every station in the list then showed a reading, including ones that had shown "Not downloaded".

**Within 150 km** gave 100 candidates. Its run went on in the background for 13 minutes, past the ten minutes a location fix lasts. It held 97 to 99 stations throughout, measured from the held fix, and reached 20 ready.

Cancel in the notification removed the job and the notification, both with the app in the background and with it on screen. With the app on screen, no new job was scheduled during the next 80 seconds while the foreground queue went on downloading. Rotating the screen after Cancel did not schedule one either. Leaving the app and opening it again did.

`adb shell cmd jobscheduler timeout` stops the job as the system would. With the app on screen, a new run was running eight seconds later. With the app in the background, no job remained. After the app's process was killed with `kill -9` through `run-as`, as a low-memory kill would be, JobScheduler kept the job. Forcing it with `adb shell cmd jobscheduler run -f` started it in a fresh process, which ended it at once, with no notification and no job left.

With airplane mode on, selecting Within 25 km and pressing Home still scheduled the job, held by its unmet connectivity constraint. Turning airplane mode off started it as a user-initiated job, and it reached 1 of 20 within 90 seconds.

Not yet covered: a physical handset, the Task Manager's Stop, a quota limit, Data Saver on a metered network, an always-on VPN, and a station that keeps failing for the full 30 minutes. Android 13 and earlier never schedule the job.

## Reproducing

Use a disposable API 34 or later emulator and clear the app's data. Grant location and notifications, run `adb emu geo fix -123.37 48.42`, and open the app until the list shows Victoria. In Downloads, select Within 25 km, then press Home. Watch `adb shell dumpsys jobscheduler` for job 2403 and `adb shell dumpsys notification --noredact` for notification 2403. To stop the job as the system would, run `adb shell cmd jobscheduler timeout io.openwaters.slackwater.android 2403`. Counts depend on the catalog and the IWLS response.
