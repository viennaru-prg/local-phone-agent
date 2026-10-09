package dev.localphone.agent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Run the same regression assertions on CPU, then restore the original preference. */
@RunWith(Suite::class)
@Suite.SuiteClasses(NativeOpenGoalTest::class,NativeGeneralTest::class,NativeNavigationTest::class,UpdateAndroidTest::class)
class NativeCpuSuite {
    companion object {
        private var originalGpu:Boolean?=null
        @BeforeClass @JvmStatic fun useCpu()=runBlocking {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.app
            originalGpu=app.prefs.gpu
            app.llm.unload()
            app.prefs.gpu=false
        }
        @AfterClass @JvmStatic fun restoreBackend()=runBlocking {
            val app=InstrumentationRegistry.getInstrumentation().targetContext.app
            try { app.llm.unload() } finally { originalGpu?.let {
                check(app.getSharedPreferences("agent",android.content.Context.MODE_PRIVATE).edit().putBoolean("gpu",it).commit())
                android.util.Log.i("AgentVerification","BACKEND_PREF_RESTORED gpu=$it")
            } }
        }
    }
}
