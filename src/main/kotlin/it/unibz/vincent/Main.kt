@file:JvmName("Main")
package it.unibz.vincent

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.server.handlers.resource.PathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import it.unibz.vincent.pages.setupWelcomeRoutes
import it.unibz.vincent.util.Option
import it.unibz.vincent.util.closeDatabase
import it.unibz.vincent.util.createDatabase
import it.unibz.vincent.util.onShutdown
import it.unibz.vincent.util.wrapRootHandler
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

var VINCENT_UNSAFE_MODE = false
	private set

/** Entry point */
fun main(args: Array<String>) {
	val staticFileDirectories = ArrayList<Path>()
	var host = "0.0.0.0"
	var port = 7000
	var databaseFile:Path? = null

	// Parse options
	val options = arrayOf(
			Option('s', "static", "Directory with publicly served files, can appear multiple times", true, "directory path") { arg, _ ->
				val path = Paths.get(arg!!)
				if (Files.isDirectory(path)) {
					val realPath = path.toRealPath()
					staticFileDirectories.add(realPath)
					LOG.info("Serving static files from {}", realPath)
				} else {
					LOG.warn("--static: Path {} does not denote a directory, ignoring", arg)
				}
			},
			Option('p', "port", "Port on which the server should run", true, "port") { arg, _ ->
				try {
					port = arg.toInt()
				} catch (e: NumberFormatException) {
					LOG.warn("--port: {} is not a valid port number", arg)
				}
			},
			Option('h', "host", "Address on which the server should run", true, "host") { arg, _ ->
				host = arg
			},
			Option('d', "database", "Path to the database file", true, "file") { arg, _ ->
				val path = Paths.get(arg!!)
				Files.createDirectories(path.parent)
				if (Files.exists(path) && !Files.isRegularFile(path)) {
					LOG.warn("--database: Path {} does not denote a file, ignoring", arg)
				} else {
					databaseFile = path.toAbsolutePath() // Can't use real path, because the file may not exist
					LOG.info("Serving database from {}", databaseFile)
				}
			},
			Option(Option.NO_SHORT_NAME, "unsafe-mode", "Disable some security measures to work even without HTTPS") { _, _ ->
				LOG.warn("Enabling unsafe mode - I hope I'm not in production!")
				VINCENT_UNSAFE_MODE = true
			},
			Option('?', "help", "Display this help and exit") { _, allOptions ->
				Option.printLaunchHelp(allOptions)
				exitProcess(0)
			}
	)

	val extraArguments = Option.parseOptions(args, options) ?: exitProcess(1)

	if (extraArguments.isNotEmpty()) {
		LOG.warn("{} extra argument(s) ignored", extraArguments.size)
	}

	val routingHandler = RoutingHandler(false)
	routingHandler.fallbackHandler = staticFileDirectories.fold(ResponseCodeHandler.HANDLE_404 as HttpHandler) {
		handler, path -> ResourceHandler(PathResourceManager.builder().setBase(path).build(), handler) }

	routingHandler.setupWelcomeRoutes()

	val db = createDatabase(databaseFile?.let { "jdbc:h2:file:$it" } ?: "jdbc:h2:mem:")
	onShutdown {
		closeDatabase(db)
		LOG.info("Database closed")
	}
	Database.connect(db)

	createSchemaTables()

	Undertow.builder().addHttpListener(port, host).setHandler(wrapRootHandler(routingHandler)).build().start()
}