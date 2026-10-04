plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 31
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    return process.inputStream.bufferedReader().use { it.readText().trim().toInt() }
}

fun getGitDescribe(): String {
    val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always"))
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

fun getVersionCode(): Int {
    // A downloaded source snapshot has no upstream git history, so the version
    // identity can be pinned from gradle.properties instead of the local commit
    // count; the git-derived value stays the default for a real checkout.
    providers.gradleProperty("KSU_VERSION_CODE").orNull?.trim()?.toIntOrNull()?.let { return it }
    val commitCount = getGitCommitCount()
    return 30000 + commitCount
}

fun getVersionName(): String {
    providers.gradleProperty("KSU_VERSION_NAME").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return getGitDescribe()
}
