import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.kotlinCompose)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.sonar)
  id("jacoco")
  id("com.google.gms.google-services")
}

android {
  namespace = "com.android.sample"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.android.sample"
    minSdk = 28
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
    }

    debug {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  testCoverage { jacocoVersion = "0.8.11" }

  buildFeatures { compose = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  // Robolectric needs to be run only in debug. But its tests are placed in the shared source set
  // (test)
  // The next lines transfers the src/test/* from shared to the testDebug one
  //
  // This prevent errors from occurring during unit tests
  sourceSets.getByName("testDebug") {
    val test = sourceSets.getByName("test")

    java.directories.clear()
    java.directories.addAll(test.java.directories)
    res.directories.clear()
    res.directories.addAll(test.res.directories)
    resources.directories.clear()
    resources.directories.addAll(test.resources.directories)
  }

  sourceSets.getByName("test") {
    java.directories.clear()
    res.directories.clear()
    resources.directories.clear()
  }
}

// With AGP 9+ the JVM target is set outside the Android block.
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

sonar {
  properties {
    property("sonar.projectKey", "Commando-project_Commando")
    property("sonar.projectName", "Commando")
    property("sonar.organization", "commando-swent")
    property("sonar.host.url", "https://sonarcloud.io")

    property(
        "sonar.junit.reportPaths",
        "${project.layout.buildDirectory.get()}/test-results/testDebugunitTest/",
    )

    property(
        "sonar.androidLint.reportPaths",
        "${project.layout.buildDirectory.get()}/reports/lint-results-debug.xml",
    )

    property(
        "sonar.coverage.jacoco.xmlReportPaths",
        "${project.layout.buildDirectory.get()}/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
    )
  }
}

// When a library is used both by robolectric and connected tests, use this function
fun DependencyHandlerScope.globalTestImplementation(dep: Any) {
  androidTestImplementation(dep)
  testImplementation(dep)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(platform(libs.compose.bom))
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockk)
  globalTestImplementation(libs.androidx.junit)
  globalTestImplementation(libs.androidx.espresso.core)

  // Firebase
  implementation(platform("com.google.firebase:firebase-bom:33.8.0"))
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.firebase:firebase-firestore")

  implementation(libs.androidx.credentials)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.play.services)
  // ------------- Jetpack Compose ------------------
  val composeBom = platform(libs.compose.bom)
  implementation(composeBom)
  globalTestImplementation(composeBom)

  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)

  // Material Design 3
  implementation(libs.compose.material3)

  // Integration with activities
  implementation(libs.compose.activity)

  // Integration with ViewModels
  implementation(libs.compose.viewmodel)
  implementation(libs.compose.navigation)

  // Android Studio Preview support
  implementation(libs.compose.preview)
  debugImplementation(libs.compose.tooling)

  // UI Tests
  globalTestImplementation(libs.compose.test.junit)
  debugImplementation(libs.compose.test.manifest)

  // --------- Kaspresso test framework ----------
  globalTestImplementation(libs.kaspresso)
  globalTestImplementation(libs.kaspresso.compose)

  // ---------- Robolectric ----------
  testImplementation(libs.robolectric)
}

val firebaseEmulatorTestPatterns =
    mapOf(
        "auth" to "com.android.sample.emulator.auth.*",
        "firestoreAdapter" to "com.android.sample.emulator.firestore.adapter.*",
        "firestoreSecurity" to "com.android.sample.emulator.firestore.security.*",
    )
val firebaseEmulatorProfile = providers.gradleProperty("firebaseEmulatorProfile").orNull

tasks.withType<Test> {
  // Configure Jacoco for each tests
  configure<JacocoTaskExtension> {
    isIncludeNoLocationClasses = true
    excludes = listOf("jdk.internal.*")
  }

  if (firebaseEmulatorProfile == null) {
    filter { excludeTestsMatching("com.android.sample.emulator.*") }
  } else if (name == "testDebugUnitTest") {
    filter {
      val testPattern =
          firebaseEmulatorTestPatterns[firebaseEmulatorProfile]
              ?: throw GradleException(
                  "Unknown Firebase emulator profile '$firebaseEmulatorProfile'. " +
                      "Expected one of: ${firebaseEmulatorTestPatterns.keys.joinToString()}"
              )
      includeTestsMatching(testPattern)
      isFailOnNoMatchingTests = true
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }

    addTestListener(
        object : TestListener {
          override fun beforeSuite(suite: TestDescriptor) = Unit

          override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.skippedTestCount > 0) {
              throw GradleException(
                  "Firebase emulator profile '$firebaseEmulatorProfile' skipped " +
                      "${result.skippedTestCount} test(s)"
              )
            }
          }

          override fun beforeTest(testDescriptor: TestDescriptor) = Unit

          override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) = Unit
        }
    )
  }
}

tasks.register("jacocoTestReport", JacocoReport::class) {
  mustRunAfter("testDebugUnitTest", "connectedDebugAndroidTest")

  reports {
    xml.required = true
    html.required = true
  }

  val fileFilter =
      listOf(
          "**/R.class",
          "**/R$*.class",
          "**/BuildConfig.*",
          "**/Manifest*.*",
          "**/*Test*.*",
          "android/**/*.*",
      )

  val debugTree =
      fileTree(
          "${project.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
      ) {
        exclude(fileFilter)
      }

  val mainSrc = "${project.layout.projectDirectory}/src/main/java"
  sourceDirectories.setFrom(files(mainSrc))
  classDirectories.setFrom(files(debugTree))
  executionData.setFrom(
      fileTree(project.layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        include("outputs/code_coverage/debugAndroidTest/connected/*/coverage.ec")
      }
  )
}
