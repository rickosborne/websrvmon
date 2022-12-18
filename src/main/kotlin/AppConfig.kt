
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class AppConfig(
	val defaults: DefaultsConfig? = null,
	val systemctl: String,
	val services: List<ServiceConfig>,
)

@Serializable
data class DefaultsConfig(
	val attempts: Int? = null,
	val emailApp: List<String>? = null,
	val emailBody: String? = null,
	val emailFrom: String? = null,
	val emailSubject: String? = null,
	val emailTo: String? = null,
	val execTimeoutSecs: Long? = null,
	val fetchTimeoutSecs: Long? = null,
	val period: String? = null,
	val waitSecs: Int? = null,
)

@Serializable
data class ServiceConfig(
	val after: List<String>? = null,
	val attempts: Int? = null,
	val check: Boolean? = null,
	val emails: List<EmailConfig>? = null,
	val execTimeoutSecs: Long? = null,
	val fetchTimeoutSecs: Long? = null,
	val headers: List<String>? = null,
	val name: String,
	val period: String? = null,
	val restarts: List<String>? = null,
	val scripts: List<String>? = null,
	val url: String,
	val waitSecs: Int? = null,
)

@Serializable
data class EmailConfig(
	val emailApp: List<String>? = null,
	val emailBody: String? = null,
	val emailFrom: String? = null,
	val emailSubject: String? = null,
	val emailTo: String? = null,
)

data class ServiceFailure(
	val failure: String,
	val httpResponse: HttpResponse<String>? = null,
	val requestDuration: Duration,
	val service: ServiceConfig,
) : Exception("Failure in ${service.name} after ${requestDuration.toMillis()}ms: ${failure}")

private val logger = KotlinLogging.logger {}

const val ATTEMPTS_DEFAULT = 2
val EMAIL_APP_DEFAULT = listOf("/usr/bin/mail", "-s", "{{subject}}", "{{to}}")
const val EMAIL_BODY_DEFAULT = "The following service appears to be down:\n\n{{service.name}}\n\n{{failure.message}}"
const val EMAIL_SUBJECT_DEFAULT = "Problems: {{service.name}}"
const val EXEC_TIMEOUT_SECS_DEFAULT = 30L
const val FETCH_TIMEOUT_SECS_DEFAULT = 30L
const val WAIT_SECS_DEFAULT = 5

val defaultDefaults = DefaultsConfig(
	attempts = ATTEMPTS_DEFAULT,
	emailApp = EMAIL_APP_DEFAULT,
	emailBody = EMAIL_BODY_DEFAULT,
	emailFrom = null,
	emailTo = null,
	emailSubject = EMAIL_SUBJECT_DEFAULT,
	execTimeoutSecs = EXEC_TIMEOUT_SECS_DEFAULT,
	fetchTimeoutSecs = FETCH_TIMEOUT_SECS_DEFAULT,
	period = "PT15M",
	waitSecs = WAIT_SECS_DEFAULT,
)

fun readConfig(file: File): AppConfig? {
	if (file.isFile) {
		file.inputStream().use { f ->
			try {
				return Yaml.default.decodeFromStream(AppConfig.serializer(), f)
			} catch (e: YamlException) {
				logger.error { "Could not parse file: ${file}:${e.line},${e.column} : ${e.path.toHumanReadableString()} : ${e.message}" }
				return null
			}
		}
	}
	return null
}

fun materializeDefaults(specified: DefaultsConfig?): DefaultsConfig {
	return DefaultsConfig(
		emailApp = specified?.emailApp ?: defaultDefaults.emailApp,
		emailFrom = specified?.emailFrom ?: defaultDefaults.emailFrom,
		emailTo = specified?.emailTo ?: defaultDefaults.emailTo,
		execTimeoutSecs = specified?.execTimeoutSecs ?: defaultDefaults.execTimeoutSecs,
		fetchTimeoutSecs = specified?.fetchTimeoutSecs ?: defaultDefaults.fetchTimeoutSecs,
		period = specified?.period ?: defaultDefaults.period,
		waitSecs = specified?.waitSecs ?: defaultDefaults.waitSecs,
	)
}

fun materializeService(service: ServiceConfig, defaults: DefaultsConfig): ServiceConfig {
	return ServiceConfig(
		after = service.after,
		attempts = service.attempts ?: defaults.attempts,
		check = service.check ?: true,
		emails = service.emails?.map { materializeEmail(it, defaults) },
		execTimeoutSecs = service.execTimeoutSecs ?: defaults.execTimeoutSecs,
		fetchTimeoutSecs = service.fetchTimeoutSecs ?: defaults.fetchTimeoutSecs,
		headers = service.headers,
		name = service.name,
		period = service.period ?: defaults.period,
		restarts = service.restarts,
		scripts = service.scripts,
		url = service.url,
		waitSecs = service.waitSecs ?: defaults.waitSecs,
	)
}

fun materializeEmail(email: EmailConfig, defaults: DefaultsConfig): EmailConfig {
	return EmailConfig(
		emailApp = email.emailApp ?: defaults.emailApp,
		emailBody = email.emailBody ?: defaults.emailBody,
		emailFrom = email.emailFrom ?: defaults.emailFrom,
		emailSubject = email.emailSubject ?: defaults.emailSubject,
		emailTo = email.emailTo ?: defaults.emailTo,
	)
}
