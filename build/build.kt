@file:Suppress("unused")

import wemi.compile.KotlinCompilerVersion
import wemi.configuration
import wemi.dependency.JCenter
import wemi.dependency.Jitpack

val continuousDebug by configuration("") {
	runOptions add { "-agentlib:jdwp=transport=dt_socket,server=n,suspend=n,address=5005" }
}

val Vincent by project {

	projectGroup set { "it.unibz.inf" }
	projectName set { "Vincent" }
	projectVersion set { "0.1-SNAPSHOT" }

	kotlinVersion set { KotlinCompilerVersion.Version1_3_61 }

	mainClass set { "it.unibz.vincent.Main" }
	runArguments add { "--unsafe-mode" }
	runArguments add { "--database=${cacheDirectory.get() / "-database/vincent" }" }
	runArguments add { "--host=localhost" }

	repositories add { Jitpack }
	repositories add { JCenter }

	// Web server
	libraryDependencies add { dependency("io.undertow", "undertow-core","2.0.28.Final") }
	// Embedded database
	libraryDependencies add { dependency("com.h2database", "h2","1.4.200") }
	// Password hashing
	libraryDependencies add { dependency("com.lambdaworks", "scrypt", "1.4.0") }
	// Html builder
	libraryDependencies add { dependency("org.jetbrains.kotlinx", "kotlinx-html-jvm", "0.6.12", exclusions = listOf(
			// It uses a very old stdlib
			DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib"),
			DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib-common")
	)) }
	// Primitive collections
	libraryDependencies add { dependency("com.carrotsearch", "hppc", "0.8.1") }

	// Logging
	libraryDependencies add { dependency("org.slf4j", "slf4j-api", "1.7.25") }
	libraryDependencies add { dependency("com.darkyen", "tproll","v1.3.1") }

	// Database access
	val exposed = "0.21.1"
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-core", exposed) }
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-jdbc", exposed) }
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-java-time", exposed) }

	// Collection utils (used for expiring cache map)
	libraryDependencies add { dependency("com.google.guava", "guava", "28.2-jre") }

	// Localization help
	libraryDependencies add { dependency("com.ibm.icu", "icu4j", "65.1") }

	assemblyMapFilter set {
		{ path, source ->
			if (path.startsWith("META-INF/") && (path.endsWith(".RSA") || path.endsWith(".DSA") || path.endsWith(".SF"))) {
				null
			} else {
				source.data
			}
		}
	}
}
