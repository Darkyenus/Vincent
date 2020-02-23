@file:JvmName("Main")
package it.unibz.vincent

import com.darkyen.tproll.TPLogger
import com.darkyen.tproll.logfunctions.FileLogFunction
import com.darkyen.tproll.logfunctions.LogFunctionMultiplexer
import com.darkyen.tproll.logfunctions.SimpleLogFunction
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.server.handlers.resource.PathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import it.unibz.vincent.pages.setupAccountListRoutes
import it.unibz.vincent.pages.setupDemographyRoutes
import it.unibz.vincent.pages.setupGuestLoginRoutes
import it.unibz.vincent.pages.setupHomeRoutes
import it.unibz.vincent.pages.setupProfileRoutes
import it.unibz.vincent.pages.setupQuestionnaireAnswerRoutes
import it.unibz.vincent.pages.setupQuestionnaireEditRoutes
import it.unibz.vincent.pages.setupTemplateInfoRoutes
import it.unibz.vincent.pages.setupWelcomeRoutes
import it.unibz.vincent.util.Option
import it.unibz.vincent.util.closeDatabase
import it.unibz.vincent.util.createDatabase
import it.unibz.vincent.util.hashPassword
import it.unibz.vincent.util.onShutdown
import it.unibz.vincent.util.toRawPassword
import it.unibz.vincent.util.wrapRootHandler
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("Main")

/** The brand name for the app, shown to users. It will always be Vincent in my heart. */
const val BRAND_NAME = "SENSY"
//const val BRAND_NAME = "Vincent"
const val BRAND_LOGO = true

var VINCENT_UNSAFE_MODE = false
	private set

/** Entry point */
fun main(args: Array<String>) {
	System.setProperty("org.jboss.logging.provider", "slf4j")

	val root = Paths.get(".").toAbsolutePath().normalize()

	TPLogger.setLogFunction(LogFunctionMultiplexer(
			FileLogFunction(root.resolve("logs").toFile()),
			SimpleLogFunction.CONSOLE_LOG_FUNCTION
	))

	val staticFileDirectories = ArrayList<Path>()
	var host = "0.0.0.0"
	var port = 7000
	var databaseFile:Path? = root.resolve("database")
	var behindReverseProxy = false

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
				if (arg == "-") {
					LOG.info("Serving database from memory")
					databaseFile = null
				} else {
					val path = Paths.get(arg!!)
					Files.createDirectories(path.parent)
					if (Files.exists(path) && !Files.isRegularFile(path)) {
						LOG.warn("--database: Path {} does not denote a file, ignoring", arg)
					} else {
						databaseFile = path.toAbsolutePath() // Can't use real path, because the file may not exist
						LOG.info("Serving database from {}", databaseFile)
					}
				}
			},
			Option(Option.NO_SHORT_NAME, "unsafe-mode", "Disable some security measures to work even without HTTPS") { _, _ ->
				LOG.warn("Enabling unsafe mode - I hope I'm not in production!")
				VINCENT_UNSAFE_MODE = true
			},
			Option(Option.NO_SHORT_NAME, "behind-reverse-proxy", "Makes the program aware that it is behind a reverse proxy and will handle X-Forwarded headers correctly") { _, _ ->
				behindReverseProxy = true
			},
			Option('l', "log", "Set the log level", true, "trace|debug|info|warn|error") { arg, _ ->
				when (arg.toLowerCase().firstOrNull()) {
					't' -> TPLogger.TRACE()
					'd' -> TPLogger.DEBUG()
					'i' -> TPLogger.INFO()
					'w' -> TPLogger.WARN()
					'e' -> TPLogger.ERROR()
					else -> println("Invalid log level: $arg")
				}
			},
			Option('?', "help", "Display this help and exit") { _, allOptions ->
				Option.printLaunchHelp(allOptions)
				exitProcess(0)
			}
	)

	val extraArguments = Option.parseOptions(args, options) ?: exitProcess(1)

	if (staticFileDirectories.isEmpty()) {
		staticFileDirectories.add(root.resolve("resources"))
		staticFileDirectories.add(root.resolve("resources/favicon"))
	}

	if (extraArguments.isNotEmpty()) {
		LOG.warn("{} extra argument(s) ignored", extraArguments.size)
	}

	val routingHandler = RoutingHandler(false)
	routingHandler.fallbackHandler = staticFileDirectories.fold(ResponseCodeHandler.HANDLE_404 as HttpHandler) {
		handler, path -> ResourceHandler(PathResourceManager.builder().setBase(path).build(), handler) }

	routingHandler.setupWelcomeRoutes()
	routingHandler.setupHomeRoutes()
	routingHandler.setupQuestionnaireEditRoutes()
	routingHandler.setupQuestionnaireAnswerRoutes()
	routingHandler.setupDemographyRoutes()
	routingHandler.setupAccountListRoutes()
	routingHandler.setupProfileRoutes()
	routingHandler.setupTemplateInfoRoutes()
	routingHandler.setupGuestLoginRoutes()

	val db = createDatabase(databaseFile?.let { "jdbc:h2:file:$it" } ?: "jdbc:h2:mem:")
	onShutdown {
		closeDatabase(db)
		LOG.info("Database closed")
	}
	Database.connect(db)

	createSchemaTables()

	var rootHandler = wrapRootHandler(routingHandler)
	if (behindReverseProxy) {
		rootHandler = Handlers.proxyPeerAddress(rootHandler)
	}
	val undertow = Undertow.builder().addHttpListener(port, host).setHandler(rootHandler).build()
	undertow.start()

	// Start CLI
	InputStreamReader(System.`in`, Charsets.UTF_8).useLines { lines ->
		val argSplitPattern = Regex("\\s+")
		for (commandRaw in lines) {
			val cliArgs = commandRaw.trim().split(argSplitPattern)
			if (cliArgs.isEmpty() || cliArgs[0].isEmpty()) {
				continue
			}

			LOG.info("CLI: {}", cliArgs.joinToString(" "))

			when (cliArgs[0].toLowerCase()) {
				"stop" -> {
					LOG.info("CLI: Stopping the server")
					undertow.stop()
					LOG.info("CLI: Server stopped")
					exitProcess(0)
				}
				"account" -> {
					val email = cliArgs.getOrNull(1)
					val type = try { AccountType.valueOf(cliArgs.getOrNull(2)?.toUpperCase() ?: "") } catch (e:IllegalArgumentException) { null }
					if (email == null || type == null || cliArgs.size != 3) {
						println("usage: account <email> <${AccountType.values().joinToString("|")}>")
					} else {
						val updated = transaction { Accounts.update(where={ Accounts.email eq email }, limit=1) { it[accountType] = type } }
						if (updated == 0) {
							println("No such user")
						} else {
							transaction {
								for (row in Accounts.slice(Accounts.id).select { Accounts.email eq email }) {
									flushSessionCache(row[Accounts.id].value)
								}
							}
							LOG.info("CLI: Account level of {} changed to {}", email, type)
						}
					}
				}
				"reserve-code" -> {
					val userEmail = cliArgs.getOrNull(1)
					val newCode = cliArgs.getOrNull(2)?.toIntOrNull()
					if (userEmail == null || newCode == null) {
						println("usage: reserve-code <email> <code>")
					} else {
						when (val result = transaction { Accounts.assignCodeToEmail(userEmail, newCode) }) {
							Accounts.CodeAssignResult.AlreadyHasThatCode -> println("User '$userEmail' already has that code")
							is Accounts.CodeAssignResult.CodeNotFree -> println("Code already assigned to a user '${result.occupiedByEmail}'")
							Accounts.CodeAssignResult.SuccessChanged -> {} // Already logged
							is Accounts.CodeAssignResult.FailureToChange -> println("Failed to change code of user '$userEmail' from ${result.oldCode} to $newCode")
							Accounts.CodeAssignResult.SuccessReserved -> {} // Already logged
						}
					}
				}
				"change-password" -> {
					val email = cliArgs.getOrNull(1)
					val newPassword = cliArgs.drop(2).joinToString(" ")
					if (email == null || newPassword.isEmpty()) {
						println("usage: change-password <email> <new-password>")
					} else {
						val updated = transaction { Accounts.update(where={ Accounts.email eq email }, limit=1) { it[password] = hashPassword(newPassword.toRawPassword()) } }
						if (updated == 0) {
							println("No such user")
						} else {
							LOG.info("CLI: Password of {} changed", email)
						}
					}
				}
				else -> {
					println("stop")
					println("\tStop the server")
					println("account <email> <${AccountType.values().joinToString("|")}>")
					println("\tChange account type")
					println("reserve-code <email> <code>")
					println("\tReserve given code for account with given e-mail")
					println("change-password <email> <new password>")
					println("\tChange the password of the account of given email")
				}
			}
		}
	}
}