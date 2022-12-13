
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.Duration.ZERO
import java.time.Duration.between
import java.time.Instant
import kotlin.io.path.inputStream
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

@OptIn(FlowPreview::class)
fun main(args: Array<String>) = runBlocking {
	val parser = ArgParser("websrvmon")
	val configPath by parser.option(
		ArgType.String,
		shortName = "c",
		fullName = "config",
		description = "Path to config file",
	)
	val dryRunArg by parser.option(
		ArgType.Boolean,
		shortName = "d",
		fullName = "dry-run",
		description = "Don't actually execute any remediation",
	)
	val testSendEmail by parser.option(
		ArgType.Boolean,
		shortName = "t",
		fullName = "test-email",
		description = "Send a test email with all the defaults",
	)
	parser.parse(args)
	val dryRun = dryRunArg ?: false
	val configFile = File(configPath ?: "/etc/websrvmon.conf.yaml")
	val config = readConfig(configFile)
	if (config == null) {
		withContext(Dispatchers.IO) {
			logger.error { "No configuration available.  See --help." }
		}
		exitProcess(1)
	}
	val materializedDefaults = materializeDefaults(config.defaults)
	if (testSendEmail == true) {
		val emailTo = materializedDefaults.emailTo
		if (emailTo == null) {
			withContext(Dispatchers.IO) {
				logger.error { "No default To email address available." }
			}
			exitProcess(1)
		}
		handleEmail(
			EmailConfig(
				materializedDefaults.emailApp,
				materializedDefaults.emailBody,
				materializedDefaults.emailFrom,
				materializedDefaults.emailSubject,
				materializedDefaults.emailTo,
			),
			ServiceFailure("Fake Failure", null, ZERO, ServiceConfig(name = "Fake Service", url = "fake")),
			false,
		)
		exitProcess(0)
	}
	val services = config.services.map { materializeService(it, materializedDefaults) }
	withContext(Dispatchers.IO) {
		logger.info { "Checking ${services.size} service${if (services.size == 1) "" else "s"}." }
	}
	val failures: List<ServiceFailure> = services
		.asFlow()
		.flatMapMerge { flow { emit(checkService(it)) } }
		.filterNotNull()
		.toList()
	if (failures.isEmpty()) {
		withContext(Dispatchers.IO) {
			logger.info { "Complete.  All services seem fine." }
		}
		return@runBlocking
	}
	withContext(Dispatchers.IO) {
		logger.info { "Remediating ${failures.size} failure${if (failures.size == 1) "" else "s"}." }
	}
	val systemctl = Path.of(config.systemctl)
	runBlocking {
		withTimeout(failures) { s -> s.restarts }
			.forEach { (serviceName, timeout) ->
				launch {
					handleRestart(serviceName, timeout, systemctl, dryRun)
				}
			}
	}
	runBlocking {
		withTimeout(failures) { s -> s.scripts }
			.forEach { (serviceName, timeout) ->
				launch {
					handleScript(serviceName, timeout, dryRun)
				}
			}
	}
	runBlocking {
		failures
			.forEach { failure ->
				(failure.service.emails ?: listOf()).forEach { emailConfig ->
					launch {
						handleEmail(emailConfig, failure, dryRun)
					}
				}
			}
	}
	withContext(Dispatchers.IO) {
		logger.info { "Complete." }
	}
}

fun <T> withTimeout(
	failures: List<ServiceFailure>,
	pick: (ServiceConfig) -> List<T>?,
): Map<T, Long> {
	return failures
		.flatMap { f ->
			pick(f.service)?.map { r -> Pair(r, f.service.execTimeoutSecs ?: EXEC_TIMEOUT_SECS_DEFAULT) } ?: listOf()
		}
		.groupBy({ p -> p.first }, { p -> p.second })
		.mapValues { e -> e.value.max() }
}

suspend fun checkService(service: ServiceConfig): ServiceFailure? {
	if (service.check == false) {
		withContext(Dispatchers.IO) {
			logger.info { "Skipping check for ${service.name}" }
		}
		return null
	}
	try {
		withContext(Dispatchers.IO) {
			logger.info { "Checking: ${service.name}" }
		}
		val timeout = (service.fetchTimeoutSecs ?: FETCH_TIMEOUT_SECS_DEFAULT).seconds
		val httpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(timeout.toJavaDuration())
			.build()
		val httpRequest = HttpRequest.newBuilder()
			.GET()
			.uri(URI(service.url))
			.timeout(timeout.toJavaDuration())
			.header("User-Agent", "websrvmon")
			.build()
		val startTime = Instant.now()
		val httpResponse = withContext(Dispatchers.IO) {
			httpClient.send(httpRequest, BodyHandlers.ofString())
		}
		val requestDuration = between(startTime, Instant.now())
		try {
			if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
				throw ServiceFailure(
					failure = "Unsuccessful status code: ${httpResponse.statusCode()}",
					httpResponse = httpResponse,
					requestDuration = requestDuration,
					service = service,
				)
			}
			withContext(Dispatchers.IO) {
				logger.info { "Okay after ${requestDuration.toMillis()}ms: ${service.name}" }
			}
		} catch (e: ServiceFailure) {
			withContext(Dispatchers.IO) {
				logger.error { e.message }
			}
			return e
		}
	} catch (e: Throwable) {
		withContext(Dispatchers.IO) {
			logger.error(e) { "Failed to check service ${service.name}: ${e.message}" }
		}
	}
	return null
}

suspend fun handleRestart(
	serviceName: String,
	timeout: Long,
	systemctl: Path,
	dryRun: Boolean,
) {
	runSomething("restart '${serviceName}'", timeout, dryRun) {
		executeCommand(
			null,
			systemctl,
			"restart",
			serviceName,
		)
	}
}

suspend fun handleScript(scriptName: String, timeout: Long, dryRun: Boolean) {
	runSomething(scriptName, timeout, dryRun) { executeShellCommand(scriptName) }
}

fun interpolate(source: String, failure: ServiceFailure): String {
	return source
		.replace(Regex("\\{\\{service.name}}", setOf(RegexOption.IGNORE_CASE)), failure.service.name)
		.replace(Regex("\\{\\{failure.message}}", setOf(RegexOption.IGNORE_CASE)), failure.message ?: "(unknown)")
}

fun interpolateEmail(source: List<String>, email: EmailConfig): MutableList<String> {
	return source.map {
		it.replace(Regex("\\{\\{to}}", setOf(RegexOption.IGNORE_CASE)), email.emailTo ?: "")
			.replace(
				Regex("\\{\\{subject}}", setOf(RegexOption.IGNORE_CASE)),
				email.emailSubject ?: EMAIL_SUBJECT_DEFAULT,
			)
	}.toMutableList()
}

suspend fun handleEmail(email: EmailConfig, failure: ServiceFailure, dryRun: Boolean) {
	if (email.emailTo == null) {
		return
	}
	runSomething("Email ${email.emailTo} about ${failure.service.name}", EXEC_TIMEOUT_SECS_DEFAULT, dryRun) {
		val emailBodyFile = kotlin.io.path.createTempFile()
		val bodyText = interpolate(email.emailBody ?: EMAIL_BODY_DEFAULT, failure)
		withContext(Dispatchers.IO) {
			logger.debug { "Temp file: ${emailBodyFile}" }
		}
		emailBodyFile.toFile().writeText(bodyText)
		emailBodyFile.toFile().deleteOnExit()
		val mailCommand = interpolateEmail(email.emailApp ?: EMAIL_APP_DEFAULT, email).map { interpolate(it, failure) }.toMutableList()
		val mailExec = mailCommand.removeAt(0)
		executeCommand(
			emailBodyFile.inputStream(),
			Path.of(mailExec),
			*mailCommand.toTypedArray(),
		)
	}
}

suspend fun runSomething(
	logStatement: String,
	timeout: Long,
	dryRun: Boolean,
	block: suspend () -> CommandResult,
) {
	withContext(Dispatchers.IO) {
		logger.info { "Exec${if (dryRun) "<DryRun>" else ""}: ${logStatement}" }
	}
	try {
		if (!dryRun) {
			val (exitCode, _, stderr) = kotlinx.coroutines.withTimeout(timeout = timeout.seconds) {
				block()
			}
			if (exitCode != 0) {
				withContext(Dispatchers.IO) {
					logger.error { "Could not execute: ${logStatement} -> ${exitCode}" }
					logger.error { stderr }
				}
			}
		}
	} catch (e: IOException) {
		withContext(Dispatchers.IO) {
			logger.error { "Failed to execute '${logStatement}' : ${e.message}" }
		}
	}
}
