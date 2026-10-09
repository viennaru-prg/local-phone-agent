package dev.localphone.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.agent.llm.LlamaNative
import dev.localphone.core.Prompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same tokens and output on each backend, with no device actions or preference changes. */
@RunWith(AndroidJUnit4::class)
class NativeBackendBenchmarkTest {
    @Test fun comparesCpuAndGpuWithTheSelectedKananaModel() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.app
        val file=app.llm.modelFile()!!
        assertTrue("This controlled comparison uses the selected Kanana model",file.name.contains("kanana",true))
        app.llm.unload()
        val prompt="<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n${Prompts.SYSTEM}<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\n목표: 보이는 결과를 알려줘\n현재 화면:\n[1] 텍스트 \"처리 결과 15분\"\n" +
            "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        val output="""{"action":"done","say":"처리 결과는 15분입니다.","expect":"","check":true,"note":"실제 관찰 값으로 답함"}"""
        val grammar="root ::= " + com.google.gson.JsonPrimitive(output).toString()
        for(layers in listOf(0,99)) {
            val started=android.os.SystemClock.elapsedRealtime()
            val handle=LlamaNative.load(file.absolutePath,app.prefs.threads,3072,layers)
            val load=android.os.SystemClock.elapsedRealtime()-started
            try {
                repeat(2) { round ->
                    LlamaNative.reset(handle)
                    val value=String(LlamaNative.infer(handle,prompt.toByteArray(),200,grammar.toByteArray(),30_000))
                    assertEquals(output,value)
                    val m=LlamaNative.metrics(handle)
                    if(round==1) assertTrue("Action prefix must survive a completion-role inference",m[1]>=m[0]-2)
                    android.util.Log.i("AgentVerification","BACKEND_BENCH model=${file.name} layers=$layers round=$round loadMs=$load " +
                        "prompt=${m[0]} reused=${m[1]} output=${m[2]} prefillMs=${m[3]} generationMs=${m[4]}")
                    if(round==0) {
                        val other=prompt.replace(Prompts.SYSTEM,Prompts.VERIFY_SYSTEM)
                        assertEquals(output,String(LlamaNative.infer(handle,other.toByteArray(),200,grammar.toByteArray(),30_000,1)))
                    }
                }
            } finally { LlamaNative.unload(handle) }
        }
    }
}
