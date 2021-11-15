package net.earthcomputer.quiltflowerintellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.io.exists
import com.intellij.util.io.readText
import com.intellij.util.text.SemVer
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Transient
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@State(
    name = "net.earthcomputer.quiltflowerintellij.QuiltflowerState",
    storages = [Storage("quiltflower.xml")]
)
class QuiltflowerState : PersistentStateComponent<QuiltflowerState> {
    @JvmField
    var enabled: Boolean = true
    @JvmField
    var autoUpdate: Boolean = true
    @JvmField
    var enableSnapshots: Boolean = false
    @JvmField
    var quiltflowerVersionStr: String? = null
    @JvmField
    var releaseBaseUrl: String = "https://maven.quiltmc.org/repository/release/org/quiltmc/quiltflower/"
    @JvmField
    var snapshotsBaseUrl: String = "https://maven.quiltmc.org/repository/snapshot/org/quiltmc/quiltflower/"

    @Transient
    @JvmField
    var quiltflowerVersions: QuiltflowerVersions? = null
    @Transient
    @JvmField
    var isDownloadingQuiltflower = false
    @Transient
    @JvmField
    var downloadedQuiltflower: Path? = null

    @get:Transient
    var quiltflowerVersion: SemVer?
        get() = SemVer.parseFromText(quiltflowerVersionStr)
        set(value) {
            quiltflowerVersionStr = value?.toString()
        }

    fun initialize() {
        reloadQuiltflowerVersions({ quiltflowerVersions ->
            ApplicationManager.getApplication().invokeLater {
                this.quiltflowerVersions = quiltflowerVersions
                if (autoUpdate) {
                    quiltflowerVersion = if (enableSnapshots) {
                        quiltflowerVersions.latestSnapshot
                    } else {
                        quiltflowerVersions.latestRelease
                    }
                }
                val prevQuiltflowerVersion = quiltflowerVersionStr
                downloadQuiltflower({ quiltflowerJar ->
                    ApplicationManager.getApplication().invokeLater {
                        isDownloadingQuiltflower = false
                        if (quiltflowerVersionStr == prevQuiltflowerVersion) {
                            downloadedQuiltflower = quiltflowerJar
                        }
                    }
                }) {
                    isDownloadingQuiltflower = false
                }
            }
        }) {
            LOGGER.error("Failed to load Quiltflower versions", it)
        }
    }

    override fun getState() = this

    override fun loadState(state: QuiltflowerState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun downloadQuiltflower(callback: (Path) -> Unit, failedCallback: () -> Unit) {
        val wasDownloadingQuiltflower = isDownloadingQuiltflower
        isDownloadingQuiltflower = true
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Downloading Quiltflower", false) {
            override fun run(indicator: ProgressIndicator) {
                val version = quiltflowerVersion
                val allVersions = quiltflowerVersions
                if (wasDownloadingQuiltflower || version == null || allVersions == null) {
                    failedCallback()
                    return
                }
                val jarsDir = PathManager.getConfigDir().resolve("quiltflower").resolve("jars")
                val jarFile = jarsDir.resolve("quiltflower-$version.jar")
                val etagFile = jarsDir.resolve("quiltflower-$version.etag")
                val etag = if (jarFile.exists() && etagFile.exists()) etagFile.readText() else null
                val urlStr = if (version in allVersions.allReleases) {
                    "$releaseBaseUrl$version/quiltflower-$version.jar"
                } else {
                    val snapshotVersion = version.toString().substringBefore("-") + "-SNAPSHOT"
                    "$snapshotsBaseUrl$snapshotVersion/quiltflower-$version.jar"
                }
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                if (etag != null) {
                    connection.setRequestProperty("If-None-Match", etag)
                }
                connection.setRequestProperty("User-Agent", "Quiltflower IntelliJ Plugin")
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    LOGGER.info("Quiltflower $version already downloaded")
                    callback(jarFile)
                    return
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    LOGGER.error("Failed to download quiltflower $version from $urlStr: $responseCode")
                    failedCallback()
                    return
                }
                if (!Files.exists(jarFile.parent)) {
                    Files.createDirectories(jarFile.parent)
                }
                connection.inputStream.use {
                    Files.copy(it, jarFile, StandardCopyOption.REPLACE_EXISTING)
                }
                val newEtag = connection.getHeaderField("ETag")
                if (newEtag != null) {
                    Files.writeString(etagFile, newEtag, StandardOpenOption.CREATE)
                }
                callback(jarFile)
            }
        })
    }

    companion object {
        private val LOGGER = logger<QuiltflowerState>()

        fun getInstance(): QuiltflowerState {
            return ApplicationManager.getApplication().getService(QuiltflowerState::class.java)
        }

        fun reloadQuiltflowerVersions(callback: (QuiltflowerVersions) -> Unit, errorCallback: (Throwable) -> Unit) {
            fun download(baseUrl: String): Pair<SemVer?, List<SemVer>> {
                val latestVersion: SemVer?
                val allVersions: List<SemVer>
                URL(baseUrl + "maven-metadata.xml").openConnection().getInputStream().use { inputStream ->
                    val element = JDOMUtil.load(inputStream)
                    if (element.name != "metadata") {
                        throw IllegalStateException("Invalid metadata file")
                    }
                    val versioning = element.getChild("versioning") ?: throw IllegalStateException("Invalid metadata file")
                    latestVersion = SemVer.parseFromText(versioning.getChild("latest")?.text)
                    allVersions = versioning.getChild("versions")?.children?.mapNotNull {
                        SemVer.parseFromText(it.text)
                    } ?: emptyList()
                }
                return latestVersion to allVersions
            }
            ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Fetching Quiltflower versions", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val (latestVersion, allVersions) = download(getInstance().releaseBaseUrl)
                        val (latestSnapshot, allSnapshots) = download(getInstance().snapshotsBaseUrl)
                        val snapshotSubversions = allSnapshots.associateWith { snapshot ->
                            val latest: String
                            val all: List<String>
                            URL("${getInstance().snapshotsBaseUrl}$snapshot/maven-metadata.xml").openConnection()
                                .getInputStream().use { inputStream ->
                                    val element = JDOMUtil.load(inputStream)
                                    if (element.name != "metadata") {
                                        throw IllegalStateException("Invalid metadata file")
                                    }
                                    val versioning = element.getChild("versioning")
                                        ?: throw IllegalStateException("Invalid metadata file")
                                    val latestSnapshotVer = versioning.getChild("snapshot")
                                        ?: throw IllegalStateException("Invalid metadata file")
                                    val timestamp = latestSnapshotVer.getChild("timestamp")?.text
                                        ?: throw IllegalStateException("Invalid metadata file")
                                    val buildNumber = latestSnapshotVer.getChild("buildNumber")?.text
                                        ?: throw IllegalStateException("Invalid metadata file")
                                    latest = "${snapshot.toString().replace("-SNAPSHOT", "")}-$timestamp-$buildNumber"
                                    all = versioning.getChild("snapshotVersions")?.children?.mapNotNull {
                                        if (it.getChild("extension")?.text == "jar" && it.getChild("classifier") == null) {
                                            it.getChild("value")?.text
                                        } else {
                                            null
                                        }
                                    } ?: emptyList()
                                }
                            (latest to all)
                        }
                        callback(QuiltflowerVersions(
                            latestVersion,
                            SemVer.parseFromText(snapshotSubversions[latestSnapshot]?.first),
                            allVersions,
                            allSnapshots.flatMap { snapshotSubversions[it]?.second?.mapNotNull(SemVer::parseFromText) ?: emptyList() }
                        ))
                    } catch (e: Throwable) {
                        errorCallback(e)
                    }
                }
            })
        }
    }
}

data class QuiltflowerVersions(
    val latestRelease: SemVer?,
    val latestSnapshot: SemVer?,
    val allReleases: List<SemVer>,
    val allSnapshots: List<SemVer>
)