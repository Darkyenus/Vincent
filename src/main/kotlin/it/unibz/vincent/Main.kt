@file:JvmName("Main")
package it.unibz.vincent

import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.server.handlers.resource.PathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import it.unibz.vincent.pages.setupHomeRoutes
import it.unibz.vincent.pages.setupQuestionnaireAnswerRoutes
import it.unibz.vincent.pages.setupQuestionnaireEditRoutes
import it.unibz.vincent.pages.setupWelcomeRoutes
import it.unibz.vincent.util.Option
import it.unibz.vincent.util.closeDatabase
import it.unibz.vincent.util.createDatabase
import it.unibz.vincent.util.onShutdown
import it.unibz.vincent.util.wrapRootHandler
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
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
	routingHandler.setupHomeRoutes()
	routingHandler.setupQuestionnaireEditRoutes()
	routingHandler.setupQuestionnaireAnswerRoutes()

	val db = createDatabase(databaseFile?.let { "jdbc:h2:file:$it" } ?: "jdbc:h2:mem:")
	onShutdown {
		closeDatabase(db)
		LOG.info("Database closed")
	}
	Database.connect(db)

	createSchemaTables()

	val undertow = Undertow.builder().addHttpListener(port, host).setHandler(wrapRootHandler(routingHandler)).build()
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
							LOG.info("CLI: Account level of {} changed to {}", email, type)
						}
					}
				}
				"list-accounts" -> {
					transaction {
						val rowFormat = "%8s | %20s | %20s | %6s | %24s | %24s"
						println(rowFormat.format("ID", "E-Mail", "Name", "Type", "Registered", "Last Login"))
						println(rowFormat.format("--------", "--------------------", "--------------------", "------", "------------------------", "------------------------"))
						var total = 0
						for (row in Accounts.selectAll()) {
							println(rowFormat.format(row[Accounts.id], row[Accounts.email], row[Accounts.name], row[Accounts.accountType], row[Accounts.timeRegistered], row[Accounts.timeLastLogin]))
							total++
						}
						println("\tTotal: $total")
					}
				}
				"reserve-code" -> {
					val userEmail = cliArgs.getOrNull(1)
					val newCode = cliArgs.getOrNull(2)?.toIntOrNull()
					if (userEmail == null || newCode == null) {
						println("usage: reserve-code <email> <code>")
					} else {
						transaction {
							val alreadyGivenTo = Accounts.slice(Accounts.email).select { Accounts.code eq newCode }.firstOrNull()?.let { it[Accounts.email] }
							if (alreadyGivenTo == userEmail) {
								println("User '$userEmail' already has that code")
							} else if (alreadyGivenTo != null) {
								println("Code already assigned to a user '$alreadyGivenTo'")
							} else {
								var idWithThatEmail = 0L
								var codeWithThatEmail = 0
								var emailExists = false

								Accounts.slice(Accounts.id, Accounts.code).select { Accounts.email eq userEmail }.firstOrNull()?.let {
									idWithThatEmail = it[Accounts.id].value
									codeWithThatEmail = it[Accounts.code]
									emailExists = true
								}

								if (emailExists) {
									val success = Accounts.update(where = { Accounts.id eq idWithThatEmail }, limit=1) { it[code] = newCode } > 0
									if (success) {
										LOG.info("CLI: Code of user '$userEmail' changed from $codeWithThatEmail to $newCode")
									} else {
										println("Failed to change code of user '$userEmail' from $codeWithThatEmail to $newCode")
									}
								} else {
									AccountCodeReservations.insert {
										it[email] = userEmail
										it[code] = newCode
									}
									LOG.info("CLI: Reserved code $newCode for user with e-mail '$userEmail'")
								}
							}
						}
					}
				}
				else -> {
					println("stop")
					println("\tStop the server")
					println("account <email> <${AccountType.values().joinToString("|")}>")
					println("\tChange account type")
					println("list-accounts")
					println("\tList all accounts")
					println("reserve-code <email> <code>")
					println("\tReserve given code for account with given e-mail")
				}
			}
		}
	}
}