package com.swarmknowledge.ospbridge

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Language submenu, end to end, through the REAL UI: open the toolbar
 * overflow, tap Language, tap Français, and expect the recreated activity
 * to render French strings. This exercises exactly the production click
 * path — AppCompatDelegate.setApplicationLocales called from a resumed
 * appcompat activity, which routes to the framework per-app locale manager
 * on API 33+ and recreates the activity.
 *
 * The device under test must run with an English system locale (the
 * overflow button's content description is the localized "More options").
 */
@RunWith(AndroidJUnit4::class)
class LanguageMenuInstrumentedTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @org.junit.Before
    fun wakeAndUnlock() {
        // The device under test may be on a lock screen / dozing between runs.
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("svc power stayon usb")
        // A fresh install shows the POST_NOTIFICATIONS dialog, which covers the
        // whole UI: grant it from the shell so the real menu is reachable.
        device.executeShellCommand(
            "pm grant com.tree4five.osp android.permission.POST_NOTIFICATIONS"
        )
        // Driving the real menu needs an interactive screen: when the device
        // is dozing (mAwake=false), skip instead of reporting a false failure.
        org.junit.Assume.assumeTrue(
            "device screen is off — test needs an interactive display",
            device.isScreenOn
        )
    }

    /** Waits for the app UI, then for the toolbar overflow button.
     *  The button's content description is appcompat-localized, so after a
     *  switch to French it reads "Options supplémentaires" (One UI) or
     *  "Plus d'options" (AOSP) — match any known label. */
    private val overflowDesc = java.util.regex.Pattern.compile(
        "More options|Plus d'options|Options supplémentaires"
    )

    private fun overflowButton(timeoutMs: Long = 10_000): androidx.test.uiautomator.UiObject2? {
        device.wait(Until.hasObject(By.desc(overflowDesc)), timeoutMs)
        return device.findObject(By.desc(overflowDesc))
    }

    private fun tapText(text: String, timeoutMs: Long = 5_000): Boolean {
        device.wait(Until.hasObject(By.text(text)), timeoutMs) ?: return false
        val target = device.findObjects(By.text(text)).firstOrNull() ?: return false
        target.click()
        return true
    }

    private fun waitGone(text: String, timeoutMs: Long = 5_000) {
        device.wait(Until.gone(By.text(text)), timeoutMs)
    }

    @Test
    fun toolbarCarriesTheLanguageSubmenu() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val toolbar = act.findViewById<MaterialToolbar>(R.id.toolbar)
                assertNotNull(toolbar)
                val item = toolbar.menu.findItem(R.id.action_language)
                assertNotNull("Language item missing from the toolbar menu", item)
                val sub = item!!.subMenu
                assertNotNull("Language entry should open a submenu", sub)
                assertEquals("Language submenu should list 5 entries", 5, sub!!.size())
                assertNotNull(sub.findItem(R.id.lang_system))
                assertNotNull(sub.findItem(R.id.lang_en))
                assertNotNull(sub.findItem(R.id.lang_fr))
                assertNotNull(sub.findItem(R.id.lang_zh))
                assertNotNull(sub.findItem(R.id.lang_ar))
            }
        }
    }

    @Test
    fun frenchSwitchThroughTheRealMenuTranslatesTheUi() {
        ActivityScenario.launch(MainActivity::class.java)
        // Settle: the node-status card must be on screen before we drive the menu.
        device.wait(Until.hasObject(By.text("Start node")), 15_000)

        // Toolbar overflow → Language → Français
        val overflow = overflowButton()
        if (overflow == null) {
            // Leave a dump for diagnosis, then fail with context.
            val f = java.io.File(
                InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
                "ui_dump.xml"
            )
            device.dumpWindowHierarchy(f)
            println("UI HIERARCHY DUMP:\n${f.readText()}")
            org.junit.Assert.fail("overflow button not found")
        }
        overflow!!.click()
        assertTrue("Language entry missing", tapText("Language"))
        assertTrue("Français entry missing", tapText("Français"))

        // The activity recreates and re-resolves every string in French.
        val french = device.wait(Until.hasObject(By.text("Démarrer le nœud")), 15_000)
        assertNotNull("UI should render in French after the switch", french)

        // Restore through the same path: Language → System default.
        overflowButton()!!.click()
        assertTrue(tapText("Langue"))
        assertTrue(tapText("Valeur du système"))
        val restored = device.wait(Until.hasObject(By.text("Start node")), 15_000)
        assertNotNull("UI should fall back to the system locale", restored)
    }
}
