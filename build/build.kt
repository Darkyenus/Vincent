import wemi.dependency.JCenter
import wemi.dependency.Jitpack

@Suppress("unused")
val Vincent by project {

	projectGroup set { "it.unibz.inf" }
	projectName set { "Vincent" }
	projectVersion set { "0.1-SNAPSHOT" }

	mainClass set { "it.unibz.vincent.Main" }

	repositories add { Jitpack }
	repositories add { JCenter }

	libraryDependencies add { dependency("io.undertow", "undertow-core","2.0.28.Final") }
	libraryDependencies add { dependency("com.h2database", "h2","1.4.200") }
	libraryDependencies add { dependency("org.jetbrains.kotlinx", "kotlinx-html-jvm", "0.6.12", exclusions = listOf(
			DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib"),
			DependencyExclusion("org.jetbrains.kotlin", "kotlin-stdlib-common")
	)) }
	libraryDependencies add { dependency("org.slf4j", "slf4j-api", "1.7.25") }
	libraryDependencies add { dependency("com.darkyen", "tproll","v1.3.1") }

	val exposed = "0.20.2"
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-core", exposed) }
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-jdbc", exposed) }
	libraryDependencies add { dependency("org.jetbrains.exposed", "exposed-java-time", exposed) }

}
