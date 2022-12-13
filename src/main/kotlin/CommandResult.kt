
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.commons.text.StringEscapeUtils
import java.io.InputStream
import java.nio.file.Path
import kotlin.text.Charsets.UTF_8

private val logger = KotlinLogging.logger {}

/**
 * Most of the code in this file comes from Alexandru Nedelcu,
 * and can be found here:
 *
 * https://alexn.org/blog/2022/10/03/execute-shell-commands-in-java-scala-kotlin/
 *
 * The terms of Alex's license for this code are *slightly* different
 * from the rest of this repo.
 */

data class CommandResult(
	val exitCode: Int,
	val stdout: String,
	val stderr: String,
)

/**
 * Executes a program. This needs to be a valid path on the
 * file system.
 *
 * See [executeShellCommand] for the version that executes
 * `/bin/sh` commands.
 */
suspend fun executeCommand(
	inputStream: InputStream?,
	executable: Path,
	vararg args: String,
): CommandResult =
	// Blocking I/O should use threads designated for I/O
	withContext(Dispatchers.IO) {
		val cmdArgs = listOf(executable.toAbsolutePath().toString()) + args
		val proc = Runtime.getRuntime().exec(cmdArgs.toTypedArray())
		logger.info { "Exec: ${if (inputStream == null) "" else "cat '...' | "} \"${cmdArgs.joinToString("\" \"", transform = StringEscapeUtils::escapeXSI)}\"" }
		try {
			if (inputStream != null) {
				proc.outputStream.use {
					inputStream.use {
						inputStream.copyTo(proc.outputStream)
					}
				}
			}
			// Concurrent execution ensures the stream's buffer doesn't
			// block processing when overflowing
			val stdout = async {
				runInterruptible {
					// That `InputStream.read` doesn't listen to thread interruption
					// signals; but for future development it doesn't hurt
					String(proc.inputStream.readAllBytes(), UTF_8)
				}
			}
			val stderr = async {
				runInterruptible {
					String(proc.errorStream.readAllBytes(), UTF_8)
				}
			}
			CommandResult(
				exitCode = runInterruptible { proc.waitFor() },
				stdout = stdout.await(),
				stderr = stderr.await(),
			)
		} finally {
			// This interrupts the streams as well, so it terminates
			// async execution, even if thread interruption for that
			// InputStream doesn't work
			proc.destroy()
		}
	}

/**
 * Executes shell commands.
 *
 * WARN: command arguments need be given explicitly because
 * they need to be properly escaped.
 */
suspend fun executeShellCommand(
	command: String,
	vararg args: String,
): CommandResult =
	executeCommand(
		null,
		Path.of("/bin/sh"),
		"-c",
		(listOf(command) + args).joinToString(" ", transform = StringEscapeUtils::escapeXSI),
	)
