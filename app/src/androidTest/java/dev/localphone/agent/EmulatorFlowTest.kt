package dev.localphone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.localphone.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Destructive test data setup is restricted to the emulator created for this project. */
@RunWith(AndroidJUnit4::class)
class EmulatorFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as AgentApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val synthetic = Coordinates(36.123456, 128.123456)

    @Before fun before() {
        Assume.assumeTrue("Run UI fixtures only on the dedicated AVD", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        runBlocking { graph.places.all().forEach { graph.places.delete(it.id) } }
        graph.settings.put("music_package", "")
        graph.settings.put("search_enabled", "")
        Intents.init()
    }
    @After fun after() {
        scenario?.close()
        Intents.release()
        runBlocking { graph.places.all().forEach { graph.places.delete(it.id) } }
        graph.settings.put("music_package", "")
    }
    private fun launch(share: String? = null) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (share != null) intent.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, share)
        scenario = ActivityScenario.launch(intent)
        if (share == null) {
            onView(withId(R.id.agent_settings)).perform(click())
            await { onView(withText("미리 등록하지 않아도 지도 앱의 저장 장소와 검색 화면에서 찾습니다.")).check(matches(isDisplayed())) }
        }
        else instrumentation.waitForIdleSync()
    }
    private fun await(timeoutMs: Long = 10_000, assertion: () -> Unit) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var failure: Throwable? = null
        do {
            instrumentation.waitForIdleSync()
            try { assertion(); return } catch (error: AssertionError) { failure = error }
            catch (error: androidx.test.espresso.NoMatchingViewException) { failure = error }
            catch (error: androidx.test.espresso.NoMatchingRootException) { failure = error }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        throw failure ?: AssertionError("Condition timed out")
    }
    private fun clickText(text: String) = onView(withText(text)).perform(scrollTo(), click())
    private fun clickDialog(text: String) = onView(withText(text)).inRoot(isDialog()).perform(click())
    private fun field(hint: String, text: String) = onView(withHint(hint)).inRoot(isDialog()).perform(replaceText(text))
    private fun command(text: String) {
        onView(withId(R.id.command_input)).perform(scrollTo(), replaceText(text))
        closeSoftKeyboard()
        clickText("명령 실행")
    }
    private fun home() = runBlocking { graph.places.get("home") }
    private fun seed(id: String, name: String, aliases: List<String>) = runBlocking {
        graph.places.saveConfirmed(UserPlace(id, name, aliases, synthetic, "합성 테스트 주소", "TEST", PlaceSource.MANUAL, 1, 1))
    }
    private fun noNavigation() = assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_VIEW && it.data?.scheme == "nmap" })
    private fun screenshot(name: String) {
        // Only synthetic fixtures are present. The production screenshot protection stays enabled.
        scenario?.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        instrumentation.waitForIdleSync()
        val folder = File(context.getExternalFilesDir(null), "emulator-evidence").apply { mkdirs() }
        val file = File(folder, "$name.png")
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            // WindowManager updates the secure surface asynchronously after the UI is idle.
            SystemClock.sleep(200)
            assertTrue(device.takeScreenshot(file))
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val visible = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != Color.BLACK
            bitmap.recycle()
            if (visible) return
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Screenshot remained black after the secure-surface update")
    }

    @Test fun missingHomeAttemptsMapLaunchWithoutForcingRegistration() {
        launch()
        command("집으로 네비 찍고 노래 재생해줘")
        await { assertTrue(Intents.getIntents().any { it.data?.scheme == "nmap" && it.data?.host == "map" }) }
        assertNull(home())
        await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
        screenshot("01-unregistered-home")
    }
    @Test fun manualHomeReviewPersistenceAliasAndDeletion() {
        launch()
        clickText("집 설정"); clickDialog("좌표 직접 입력")
        field("주소 (선택)", "합성 테스트 주소")
        field("위도", synthetic.latitude.toString()); field("경도", synthetic.longitude.toString())
        clickDialog("확인")
        assertNull(home()) // Review is not a write.
        clickDialog("저장 확인")
        await { assertEquals(synthetic, home()?.coordinates) }
        await { onView(withText("수정")).check(matches(isDisplayed())) }
        assertEquals(PlaceSlots.aliases.getValue("home"), home()?.aliases)
        scenario!!.recreate()
        await { onView(withText("수정")).check(matches(isDisplayed())) }
        assertEquals(synthetic, home()?.coordinates)
        clickText("별칭 추가"); field("예: 단골 주유소", "테스트집")
        clickDialog("내용 확인")
        assertFalse("테스트집" in home()!!.aliases)
        clickDialog("추가 확인")
        await { assertTrue("테스트집" in home()!!.aliases) }
        await { onView(withText("수정")).check(matches(isDisplayed())) }
        screenshot("02-home-and-alias")
        clickText("삭제"); clickDialog("취소")
        assertNotNull(home())
        clickText("삭제"); clickDialog("삭제")
        await { assertNull(home()) }
        noNavigation()
    }
    @Test fun invalidManualCoordinatesDoNotWrite() {
        launch()
        clickText("집 설정"); clickDialog("좌표 직접 입력")
        field("위도", "95"); field("경도", "128")
        clickDialog("확인")
        onView(withText("좌표 직접 입력")).inRoot(isDialog()).check(matches(isDisplayed()))
        assertNull(home()); noNavigation()
    }
    @Test fun sharedPlaceRequiresReviewThenOfficeAliasWorks() {
        launch("nmap://place?lat=36.123456&lng=128.123456&name=TestOffice")
        await { onView(withText("장소 저장 확인")).inRoot(isDialog()).check(matches(isDisplayed())) }
        assertTrue(runBlocking { graph.places.all().isEmpty() })
        field("무엇으로 저장할까요?", "회사")
        clickDialog("저장 확인")
        await { assertEquals(synthetic, runBlocking { graph.places.get("office") }?.coordinates) }
        val resolution = runBlocking { graph.resolver.resolve("직장으로") }
        assertEquals("office", resolution.resolved?.id)
        assertEquals(PlaceSource.SHARED_LINK, runBlocking { graph.places.get("office") }?.source)
        noNavigation()
    }
    @Test fun shortLinkCannotSilentlyImportCoordinates() {
        launch("[네이버 지도]\n합성카페\nhttps://naver.me/TestFixture")
        await { onView(withText("공유한 장소 확인")).inRoot(isDialog()).check(matches(isDisplayed())) }
        assertTrue(runBlocking { graph.places.all().isEmpty() }); noNavigation()
    }
    @Test fun duplicateAliasRequiresSelectionWithoutNavigation() {
        launch()
        seed("cafe_a", "합성 카페 A", listOf("단골 카페"))
        seed("cafe_b", "합성 카페 B", listOf("단골 카페"))
        command("단골 카페 가자")
        await { onView(withText("합성 카페 A\n합성 테스트 주소")).inRoot(isDialog()).check(matches(isDisplayed())) }
        noNavigation(); clickDialog("취소"); noNavigation()
    }
    @Test fun negativeCommandDoesNotStartNavigation() {
        launch()
        command("집 가지마")
        await { onView(withId(R.id.command_status)).check(matches(withText("취소·조건·질문이 포함된 문장입니다. 실행할 명령을 분명하게 입력해 주세요."))) }
        noNavigation()
    }
    @Test fun missingModelDoesNotPretendToUseAI() {
        launch()
        assertFalse(graph.modelFile.exists())
        // Settings now contains a longer voice section. Use real scroll gestures so the
        // platform ScrollView has settled before touching its native Switch.
        repeat(3) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 20)
            SystemClock.sleep(200)
        }
        onView(withId(R.id.use_functiongemma)).check(matches(isDisplayed())).perform(click())
        onView(withId(R.id.use_functiongemma)).check(matches(isNotChecked()))
        onView(withId(R.id.command_status)).check(matches(withText("먼저 .litertlm 모델 파일을 불러와 주세요.")))
    }
    @Test fun currentLocationDoesNotPersistUntilConfirmation() {
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        launch()
        clickText("집 설정"); clickDialog("현재 위치 사용")
        await(25_000) { onView(withText("장소 저장 확인")).inRoot(isDialog()).check(matches(isDisplayed())) }
        assertNull(home())
        clickDialog("저장 확인")
        await { assertEquals(PlaceSource.CURRENT_LOCATION, home()?.source) }
        assertEquals(synthetic.latitude, home()!!.coordinates.latitude, 0.00001)
        assertEquals(synthetic.longitude, home()!!.coordinates.longitude, 0.00001)
        noNavigation()
    }
    @Test fun navigationIntentWithoutUiCompletionDoesNotClaimCompoundSuccessOrResumeMedia() {
        launch()
        seed("home", "집", PlaceSlots.aliases.getValue("home"))
        device.executeShellCommand("cmd notification allow_listener ${context.packageName}/dev.localphone.agent.runtime.AgentNotificationListener")
        val played = AtomicInteger()
        val session = MediaSession(context, "SyntheticMusicForEmulatorTest")
        try {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    played.incrementAndGet()
                    session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                }
            }, Handler(Looper.getMainLooper()))
            session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f).build())
            session.isActive = true
            graph.settings.put("music_package", context.packageName)
            await { assertTrue(android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().contains(context.packageName)) }
            command("집으로 네비 찍고 노래 재생해줘")
            await { assertEquals(NaverLinks.PACKAGE, device.currentPackageName) }
            val target = Intents.getIntents().single { it.action == Intent.ACTION_VIEW && it.data?.scheme == "nmap" }
            assertEquals(NaverLinks.PACKAGE, target.`package`)
            assertEquals("navigation", target.data?.host)
            assertEquals("집", target.data?.getQueryParameter("dname"))
            assertEquals(synthetic.latitude.toString(), target.data?.getQueryParameter("dlat"))
            assertEquals(synthetic.longitude.toString(), target.data?.getQueryParameter("dlng"))
            assertEquals(context.packageName, target.data?.getQueryParameter("appname"))
            assertTrue(target.categories.contains(Intent.CATEGORY_BROWSABLE))
            assertEquals(0, played.get())
            assertEquals(PlaybackState.STATE_PAUSED, session.controller.playbackState?.state)
            screenshot("03-navigation-receiver")
        } finally { session.release() }
    }
}
