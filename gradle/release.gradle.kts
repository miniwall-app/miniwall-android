// Release plumbing shared by :core and :sdk. Nothing here publishes by itself.
//  - signs every publication when a key is supplied (ORG_GRADLE_PROJECT_signingInMemoryKey / signingInMemoryKeyPassword,
//    or the same -P properties); without a key the build is unsigned, so local builds and CI need no secrets;
//  - adds a local "staging" repository (build/maven-repo) in the standard Maven layout. The release workflow seeds it with
//    what is already on https://maven.miniwall.app, publishes into it (Gradle merges maven-metadata.xml) and uploads it back.
// The key and the storage credentials live in the release environment's secrets, never in this repository.
apply(plugin = "signing")

val publishing = extensions.getByType<PublishingExtension>()

publishing.repositories {
    maven {
        name = "staging"
        url = uri(rootProject.layout.buildDirectory.dir("maven-repo"))
    }
}

extensions.configure<SigningExtension> {
    val key = findProperty("signingInMemoryKey") as String?
    if (key.isNullOrBlank()) {
        isRequired = false
    } else {
        useInMemoryPgpKeys(key, findProperty("signingInMemoryKeyPassword") as String? ?: "")
        sign(publishing.publications)
    }
}
