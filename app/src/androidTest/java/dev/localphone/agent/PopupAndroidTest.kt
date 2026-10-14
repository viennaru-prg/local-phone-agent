package dev.localphone.agent

import android.app.Dialog
import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native dialog/action binding on Android; the NAVER package is a fixture, not the installed map. */
@RunWith(AndroidJUnit4::class)
class PopupAndroidTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()

    @Test fun anAdXNamedDeleteClosesThroughAccessibilityWithoutLoadingTheModel()=checkPopup("삭제")
    @Test fun anUnnamedAdXRemainsActionableAndClosesWithoutLoadingTheModel()=checkPopup(null)

    private fun checkPopup(label: String?)=runBlocking {
        val context=instrument.targetContext
        assertFalse(context.app.llm.loaded)
        val activity=instrument.startActivitySync(Intent(context,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val metrics=context.resources.displayMetrics
        lateinit var dialog: Dialog
        instrument.runOnMainSync {
            dialog=Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val root=FrameLayout(activity)
            root.addView(TextView(activity).apply { text="광고 이미지"; textSize=22f },
                FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
            root.addView(ImageButton(activity).apply {
                contentDescription=label
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setOnClickListener { dialog.dismiss() }
            },FrameLayout.LayoutParams(147,147,Gravity.TOP or Gravity.RIGHT))
            dialog.setContentView(root)
            dialog.show()
            dialog.window!!.setLayout((metrics.widthPixels*.75).toInt(),(metrics.heightPixels*.45).toInt())
        }
        instrument.waitForIdleSync()
        val live=mutableListOf<AccessibilityNodeInfo>()
        var actions=0
        val phone=object:Phone {
            override suspend fun observe(): Snapshot? {
                val tree=instrument.uiAutomation.rootInActiveWindow ?: return null
                live.forEach { @Suppress("DEPRECATION") it.recycle() }; live.clear()
                val nodes=mutableListOf<RawNode>()
                fun visit(node: AccessibilityNodeInfo,path: String,parent: Int) {
                    val i=nodes.size
                    live+=node
                    val b=Rect();node.getBoundsInScreen(b)
                    nodes+=RawNode(path,parent,text=node.text?.toString().orEmpty(),desc=node.contentDescription?.toString().orEmpty(),
                        viewId=node.viewIdResourceName.orEmpty(),className=node.className?.toString().orEmpty(),
                        clickable=node.isClickable,editable=node.isEditable,scrollable=node.isScrollable,enabled=node.isEnabled,
                        bounds=Bounds(b.left,b.top,b.right,b.bottom))
                    for(c in 0 until node.childCount) node.getChild(c)?.let { visit(it,"$path.$c",i) }
                }
                visit(tree,"r",-1)
                return Snapshot(NavigationSession.NAVER_MAP,"네이버지도 시험 화면",nodes,metrics.widthPixels,metrics.heightPixels)
            }
            override suspend fun perform(view: ScreenView,action: AgentAction): Boolean {
                val click=action as? AgentAction.Click ?: return false
                assertTrue(NavigationPopup.isClose(view,click.id))
                actions++
                return live[view.element(click.id)!!.node].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            override suspend fun openApp(name: String)=error("No app launches in this fixture")
            override suspend fun media(key: MediaKey)=error("No media in this fixture")
            override fun now()=android.os.SystemClock.elapsedRealtime()
        }
        try {
            var view: ScreenView?=null
            repeat(20) {
                if(view==null) {
                    val next=phone.observe()?.let { ScreenCompactor.compact(it,"회사로 안내해줘") }
                    if(next!=null && NavigationPopup.find(next.snapshot)!=null) view=next else delay(100)
                }
            }
            assertNotNull("Native popup was not exposed to accessibility",view)
            val result=NavigationPopupHandler(phone,"회사로 안내해줘",object:AgentListener{},PopupDismissState()).handle(view!!)
            assertTrue(result.toString(),result is NavigationPopupHandler.Result.Cleared)
            instrument.runOnMainSync { assertFalse(dialog.isShowing) }
            assertEquals(1,actions)
            assertFalse(context.app.llm.loaded)
        } finally {
            instrument.runOnMainSync { dialog.dismiss(); activity.finish() }
            live.forEach { @Suppress("DEPRECATION") it.recycle() }
        }
    }
}
