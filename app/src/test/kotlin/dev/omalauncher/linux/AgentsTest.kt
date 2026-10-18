package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsTest {
    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    /** Started in ~ without --allow-home, omp works in a temp dir instead, away from AGENTS.md and the widgets. */
    @Test fun ompStaysInTheDirectoryItIsStartedIn() {
        assertEquals("omp --allow-home 'hi'", agentInvocation("omp", "hi"))
        assertTrue(agentInvocation("omp", "hi", readOnly = true).startsWith("omp --allow-home "))
    }

    /** Reviewing an untrusted widget: omp gets no tool that runs commands or writes files. */
    @Test fun ompReadOnlyHasOnlyReadingTools() {
        val tools = Regex("--tools (\\S+)").find(agentInvocation("omp", "review", readOnly = true))!!.groupValues[1].split(',')
        assertEquals(listOf("read", "grep", "glob"), tools)
    }

    /** agy rejects a positional prompt and drops to a shell; its first prompt goes through -i. */
    @Test fun agyTakesThePromptWithDashI() {
        assertEquals("agy -i 'hi'", agentInvocation("agy", "hi"))
        assertEquals("agy --mode plan -i 'hi'", agentInvocation("agy", "hi", readOnly = true))
    }

    /** Hermes opens its chat with the first prompt, from the agent's launch command. */
    @Test fun hermesTakesThePromptAsChatQuery() {
        assertEquals("hermes chat -q 'hi'", agentInvocation("hermes", "hi"))
        val hermes = AGENTS.first { it.bin == "hermes" }
        assertTrue(agentInvocation("hermes", "hi").startsWith(hermes.launch + " chat -q "))
    }

    @Test fun copilotReceivesAnInteractivePromptAsOneLiteralArgument() {
        val prompt = "Explain \"it's quoted\"; \$(exit 17)\nand keep \$HOME literal."
        val script = """
            set -- ${agentInvocation("copilot", prompt)}
            test "${'$'}#" -eq 3 &&
            test "${'$'}1" = copilot &&
            test "${'$'}2" = -i &&
            test "${'$'}3" = "${'$'}EXPECTED_PROMPT"
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["EXPECTED_PROMPT"] = prompt }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    /** OpenCode installs from before 2 have no node, and mise needs it for OpenCode 2's postinstall. */
    @Test fun updatesAddNodeForOpencode() {
        assertEquals(
            "if [ -z \"\$(mise ls -g node)\" ] && [ -n \"\$(mise ls -g 'opencode' 'npm:@xai-official/grok' 'npm:@moonshot-ai/kimi-code')\" ]; " +
                "then mise use -g node@lts; fi",
            MISE_NODE_FOR_AGENTS,
        )
    }
}
