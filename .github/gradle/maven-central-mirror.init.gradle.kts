// Installed by CI only. repo.maven.apache.org answers GitHub's runners with 429 Too Many Requests
// often enough to fail runs, and a run that fails that way leaves the Gradle cache it saves without
// what it could not fetch, so the next runs fetch it again. Google's mirror of Maven Central serves
// the same artifacts from its own storage.
val central = "https://repo.maven.apache.org/maven2/"
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun RepositoryHandler.useMirror() = withType<MavenArtifactRepository>().configureEach {
    if (url.toString() == central) setUrl(mirror)
}

beforeSettings {
    pluginManagement.repositories.useMirror()
    dependencyResolutionManagement.repositories.useMirror()
}
