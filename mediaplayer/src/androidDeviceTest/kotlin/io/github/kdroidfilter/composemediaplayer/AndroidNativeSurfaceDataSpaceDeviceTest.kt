package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidNativeSurfaceDataSpaceDeviceTest {
    @Test
    fun packagesNativeWindowDataSpaceBridgeOnSupportedAndroidVersions() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)

        assertTrue(AndroidNativeSurfaceDataSpaceBridge.isAvailable())
    }

    @Test
    fun readsDataSpaceFromARealSurface() {
        assumeTrue(Build.VERSION.SDK_INT in Build.VERSION_CODES.P until Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent =
            Intent()
                .setClassName(instrumentation.context.packageName, SurfaceDataSpaceTestActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SurfaceDataSpaceTestActivity
        try {
            val surfaceReady = CountDownLatch(1)
            var readBackDataSpace: Int? = null
            activity.runOnUiThread {
                val callback =
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            readBackDataSpace = AndroidNativeSurfaceDataSpaceBridge.read(holder.surface)
                            surfaceReady.countDown()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    }
                activity.surfaceView.holder.addCallback(callback)
                if (activity.surfaceView.holder.surface.isValid) callback.surfaceCreated(activity.surfaceView.holder)
            }

            assertTrue("Surface was not created.", surfaceReady.await(10, TimeUnit.SECONDS))
            assertNotNull("The native-window dataspace readback failed.", readBackDataSpace)
            assertTrue("The native-window was invalid: $readBackDataSpace", readBackDataSpace!! >= 0)
        } finally {
            activity.finish()
        }
    }
}

class SurfaceDataSpaceTestActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }
}
