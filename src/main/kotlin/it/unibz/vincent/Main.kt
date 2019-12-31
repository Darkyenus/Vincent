@file:JvmName("Main")
package it.unibz.vincent

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.server.handlers.resource.PathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.util.Option
import it.unibz.vincent.util.sendHtml
import it.unibz.vincent.util.wrapRootHandler
import kotlinx.html.body
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

/** Entry point */
fun main(args: Array<String>) {
	val staticFileDirectories = ArrayList<Path>()
	var host = "0.0.0.0"
	var port = 7000

	// Parse options
	val options = arrayOf(
			Option('s', "static", "Directory with publicly served files, can appear multiple times", true, "directory path") { arg, _ ->
				val path = Paths.get(arg!!)
				if (Files.isDirectory(path)) {
					try {
						val realPath = path.toRealPath()
						staticFileDirectories.add(realPath)
						LOG.info("Serving static files from {}", realPath)
					} catch (e: IOException) {
						LOG.warn("--static: Path {} could not be resolved to a real path", arg)
					}
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
	routingHandler.get("/") { exchange ->
		exchange.statusCode = StatusCodes.OK
		exchange.sendHtml {
			body { +"Hello world!" }
		}
	}

	Undertow.builder().addHttpListener(port, host).setHandler(wrapRootHandler(routingHandler)).build().start()
}