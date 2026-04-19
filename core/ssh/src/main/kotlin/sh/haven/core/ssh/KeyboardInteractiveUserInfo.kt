package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.runBlocking

private const val KI_TAG = "HavenKI"

/**
 * Bridges JSch's synchronous [UIKeyboardInteractive] callback (invoked on
 * JSch's internal IO thread during auth) to a suspending
 * [KeyboardInteractivePrompter]. The JSch thread blocks in [runBlocking]
 * until the prompter resumes — which, for a UI-backed prompter, means
 * until the user dismisses the dialog.
 *
 * Also implements [UserInfo] because JSch's API requires any interactive
 * callback to come in as a single object that implements both interfaces.
 * We deliberately return nulls / false from the [UserInfo] methods so JSch
 * falls back to the password set via `session.setPassword(...)` for
 * password auth, and cancels prompts we don't handle (host-key acceptance
 * is handled separately by [HostKeyVerifier]).
 *
 * An optional [fallbackPassword] auto-answers the first prompt when it
 * looks like a password prompt — i.e. the server sent exactly one prompt
 * with echo=false, and we already have a saved password for the profile.
 * This matches Gboard-style UX: the user shouldn't have to retype their
 * saved password just because the server routed "Password:" through the
 * keyboard-interactive channel instead of password auth. For two-factor
 * rounds (password + code) the prompter is invoked normally.
 */
internal class KeyboardInteractiveUserInfo(
    private val destination: String,
    private val prompter: KeyboardInteractivePrompter,
    private val fallbackPassword: CharArray? = null,
) : UserInfo, UIKeyboardInteractive {

    override fun getPassphrase(): String? = null

    override fun getPassword(): String? = null

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) { /* no-op */ }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        val prompts = (prompt ?: emptyArray()).mapIndexed { i, p ->
            KeyboardInteractiveChallenge.Prompt(
                text = p,
                echo = echo?.getOrNull(i) ?: true,
            )
        }
        Log.d(
            KI_TAG,
            "promptKeyboardInteractive name='$name' instruction='$instruction' " +
                "prompts=${prompts.map { "${it.text}(echo=${it.echo})" }}",
        )

        // Single-prompt password-style round with a saved password: answer
        // silently and let the user see a dialog only for the things they
        // actually need to type (TOTP codes, etc.).
        if (fallbackPassword != null &&
            prompts.size == 1 &&
            !prompts[0].echo &&
            prompts[0].text.contains("password", ignoreCase = true)
        ) {
            Log.d(KI_TAG, "  fallback: answering with saved password (len=${fallbackPassword.size})")
            return arrayOf(String(fallbackPassword))
        }

        val challenge = KeyboardInteractiveChallenge(
            destination = destination ?: this.destination,
            name = name ?: "",
            instruction = instruction ?: "",
            prompts = prompts,
        )
        Log.d(KI_TAG, "  dispatching to prompter")
        val responses = runBlocking { prompter.prompt(challenge) }
        Log.d(
            KI_TAG,
            "  prompter returned: ${if (responses == null) "null (cancel)" else "${responses.size} responses, " +
                "lengths=${responses.map { it.length }}"}",
        )
        return responses?.toTypedArray()
    }
}
