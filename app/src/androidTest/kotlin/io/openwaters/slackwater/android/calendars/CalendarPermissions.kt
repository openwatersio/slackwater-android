package io.openwaters.slackwater.android.calendars

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

internal fun grantCalendarPermissions(app: Context) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
        val output = automation.executeShellCommand("pm grant ${app.packageName} $permission")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        assertEquals(permission, PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission))
    }
}
