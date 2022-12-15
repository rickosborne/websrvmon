import java.io.IOException
import java.util.*
import java.util.jar.Manifest

data class BuildProperties(
	val version: String
)

fun getBuildProperties(): BuildProperties {
	class Local {}
	val local = Local()
	val manifests = local.javaClass.classLoader.getResources("META-INF/MANIFEST.MF")
	while (manifests.hasMoreElements()) {
		try {
			val manifestUrl = manifests.nextElement()
			val manifest = Manifest(manifestUrl.openStream())
			val implTitle = manifest.mainAttributes.getValue("Implementation-Title")
			if (implTitle == "websrvmon") {
				return BuildProperties(manifest.mainAttributes.getValue("Implementation-Version"))
			}
		} catch (e: IOException) {
			// do nothing
		}
	}
	val props = Properties()
	val propResource = local.javaClass.getResourceAsStream("version.properties")
	if (propResource != null) {
		props.load(propResource)
		return BuildProperties(props.getProperty("version"))
	}
	return BuildProperties("?")
}
