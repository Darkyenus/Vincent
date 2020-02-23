package it.unibz.vincent

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import it.unibz.vincent.template.QuestionnaireTemplate
import it.unibz.vincent.template.parseTemplate
import it.unibz.vincent.util.HASHED_PASSWORD_SIZE
import it.unibz.vincent.util.HashedPassword
import it.unibz.vincent.util.NO_PASSWORD
import it.unibz.vincent.util.SQLErrorType
import it.unibz.vincent.util.appendHex
import it.unibz.vincent.util.getLong
import it.unibz.vincent.util.parseHex
import it.unibz.vincent.util.putLong
import it.unibz.vincent.util.type
import it.unibz.vincent.util.varcharIgnoreCase
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.`java-time`.CurrentTimestamp
import org.jetbrains.exposed.sql.`java-time`.timestamp
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.nextVal
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.random.Random

private val LOG = LoggerFactory.getLogger("DatabaseSchema")

/** Generates a random code to use.
 * May be duplicate, in that case call again with [attempt]`+1` until a unique code is found. */
private fun randomCode(attempt:Int):Int {
	val lowerBound = 100
	var upperBound = 1000

	// After 10 attempts, upper bound is multiplied by 10
	// After 100 attempts, upper bound is multiplied by 100, etc.
	for (i in 0 until floor(log10(maxOf(attempt, 1).toDouble())).toInt()) {
		upperBound *= 10
	}

	return lowerBound + Random.Default.nextInt(upperBound - lowerBound)
}

/** Dictates what the account can do.
 * Ordered from least to most privileged. */
enum class AccountType : Comparable<AccountType> {
	// NOTE: This is stored in DB by ordinal!
	// Do not change the order or meaning of enum values without considering backward compatibility!

	/** Inactive account, just a reserved ID. */
	RESERVED,
	GUEST,
	NORMAL,
	STAFF,
	ADMIN
}

/** Guest accounts have IDs which are actually [Accounts.id], obfuscated and prefixed with this. */
const val GUEST_CODE_PREFIX = "G#"
private const val GUEST_CODE_CIPHER_SIZE_BYTES = 8
private const val GUEST_CODE_OBFUSCATION_CIPHER_NAME = "DES/ECB/NoPadding"

/** **This provides no security** and is only for minor obfuscation. */
private val obfuscationKey = SecretKeySpec("Vincent".toByteArray(Charsets.UTF_8).copyOf(GUEST_CODE_CIPHER_SIZE_BYTES), "DES")

fun accountIdToGuestCode(accountId:Long):String {
	val cipher = Cipher.getInstance(GUEST_CODE_OBFUSCATION_CIPHER_NAME)
	cipher.init(Cipher.ENCRYPT_MODE, obfuscationKey)

	val accountIdBytes = ByteArray(GUEST_CODE_CIPHER_SIZE_BYTES)
	accountIdBytes.putLong(0, accountId)
	val result = cipher.doFinal(accountIdBytes)
	assert(result.size == GUEST_CODE_CIPHER_SIZE_BYTES)
	return StringBuilder().append(GUEST_CODE_PREFIX).appendHex(result).toString()
}

fun guestCodeToAccountId(guestCode:String):Long? {
	if (!guestCode.startsWith(GUEST_CODE_PREFIX, ignoreCase = true)) {
		return null
	}

	val aesBytes = guestCode.parseHex(GUEST_CODE_PREFIX.length, guestCode.length) ?: return null

	val cipher = Cipher.getInstance(GUEST_CODE_OBFUSCATION_CIPHER_NAME)
	cipher.init(Cipher.DECRYPT_MODE, obfuscationKey)
	val result = cipher.doFinal(aesBytes)
	assert(result.size == GUEST_CODE_CIPHER_SIZE_BYTES)
	return result.getLong(0)
}


/** Table of registered users. */
object Accounts : LongIdTable() {
	const val MAX_NAME_LENGTH = 128
	val name = varchar("name", MAX_NAME_LENGTH).nullable()
	const val MAX_EMAIL_LENGTH = 128
	val email = varcharIgnoreCase("email", MAX_EMAIL_LENGTH).uniqueIndex().nullable()
	val password = binary("password", HASHED_PASSWORD_SIZE)
	val accountType = enumeration("account_type", AccountType::class)
	val code = integer("code").uniqueIndex().nullable()

	val timeRegistered = timestamp("time_registered")
	val timeLastLogin = timestamp("time_last_login")

	const val GUEST_LOGIN_CODE_SIZE = 16
	val guestLoginCode = binary("guest_login_code", GUEST_LOGIN_CODE_SIZE).nullable()

	private const val PanelistCodeSequenceRawName = "panelist_code_sequence"
	val PanelistCodeSequence = Sequence(PanelistCodeSequenceRawName, startWith = 100)

	fun createRegularAccount(name:String, email:String, password:HashedPassword):AccountCreationResult {
		val reservedAccountId = try {
			Accounts.slice(Accounts.id).select { (Accounts.email eq email) and (Accounts.accountType eq AccountType.RESERVED) }.firstOrNull()?.let { it[Accounts.id].value }
		} catch (e:SQLException) {
			LOG.error("Failed to check for reservations", e)
			null
		}

		if (reservedAccountId != null) {
			// Update reservations
			val rows = Accounts.update(where={ (Accounts.id eq reservedAccountId) and (Accounts.accountType eq AccountType.RESERVED) }, limit=1) {
				it[Accounts.name] = name
				it[Accounts.password] = password
				it[accountType] = AccountType.NORMAL
				val now = Instant.now()
				it[timeRegistered] = now
				it[timeLastLogin] = now
			}
			if (rows != 1) {
				throw SQLException("Account id is no longer reserved")
			}

			return AccountCreationResult.Success(reservedAccountId)
		}

		try {
			val id = Accounts.insertAndGetId {
				it[Accounts.name] = name
				it[Accounts.email] = email
				it[Accounts.password] = password
				it[Accounts.accountType] = AccountType.NORMAL
				it[Accounts.code] = PanelistCodeSequence.nextVal() // TODO(jp): This will not work right now
				val now = Instant.now()
				it[Accounts.timeRegistered] = now
				it[Accounts.timeLastLogin] = now
			}.value
			return AccountCreationResult.Success(id)
		} catch (e: SQLException) {
			if (e.type() == SQLErrorType.DUPLICATE_KEY) {
				return AccountCreationResult.DuplicateEmail
			} else {
				throw e
			}
		}
	}

	sealed class AccountCreationResult {
		class Success(val id:Long):AccountCreationResult()
		object DuplicateEmail : AccountCreationResult()
		object Failure : AccountCreationResult()
	}

	fun createGuestAccount(loginCode: ByteArray):Long {
		return insertAndGetId {
			it[Accounts.name] = null
			it[Accounts.email] = null
			it[Accounts.password] = NO_PASSWORD
			it[Accounts.code] = null
			it[Accounts.accountType] = AccountType.GUEST
			it[Accounts.timeRegistered] = Instant.now()
			it[Accounts.timeLastLogin] = Instant.EPOCH
			it[Accounts.guestLoginCode] = loginCode
		}.value
	}

	private fun refreshCodeSequence(newCode:Int) {
		val transaction = TransactionManager.current()
		val nextSequenceDefault = transaction.exec("SELECT NEXT VALUE FOR ${PanelistCodeSequence.identifier}") { resultSet ->
			if (resultSet.next()) {
				resultSet.getInt(1)
			} else 0
		} ?: 0
		val nextSequenceManual = newCode + 1
		val newNextSequence = max(nextSequenceDefault, nextSequenceManual)

		for (sql in PanelistCodeSequence.dropStatement()) {
			transaction.exec(sql)
		}

		val newModifiedSequence = Sequence(PanelistCodeSequenceRawName, startWith = newNextSequence)
		for (sql in newModifiedSequence.createStatement()) {
			transaction.exec(sql)
		}
	}

	fun assignCodeToEmail(email:String, code:Int):CodeAssignResult {
		val alreadyGivenTo = Accounts.slice(Accounts.email).select { Accounts.code eq code }.firstOrNull()?.let { it[Accounts.email] }
		if (alreadyGivenTo == email) {
			return CodeAssignResult.AlreadyHasThatCode
		} else if (alreadyGivenTo != null) {
			return CodeAssignResult.CodeNotFree(alreadyGivenTo)
		} else {
			var idWithThatEmail = 0L
			val codeWithThatEmail:Int? = Accounts.slice(Accounts.id, Accounts.code).select { Accounts.email eq email }.firstOrNull()?.let {
				idWithThatEmail = it[Accounts.id].value
				it[Accounts.code]
			}

			if (codeWithThatEmail != null) {
				val success = Accounts.update(where = { Accounts.id eq idWithThatEmail }, limit=1) { it[this.code] = code } > 0
				return if (success) {
					LOG.info("Code of user '$email' changed from $codeWithThatEmail to $code")
					if (code > codeWithThatEmail) {
						refreshCodeSequence(code)
					}
					CodeAssignResult.SuccessChanged
				} else {
					CodeAssignResult.FailureToChange(codeWithThatEmail)
				}
			} else {
				Accounts.insert {
					it[Accounts.name] = "RESERVED"
					it[Accounts.email] = email
					it[Accounts.password] = ByteArray(0)
					it[Accounts.code] = code
					it[Accounts.accountType] = AccountType.RESERVED
					it[Accounts.timeRegistered] = Instant.now()
					it[Accounts.timeLastLogin] = Instant.EPOCH
				}
				LOG.info("Reserved code $code for user with e-mail '$email'")
				refreshCodeSequence(code)
				return CodeAssignResult.SuccessReserved
			}
		}
	}

	sealed class CodeAssignResult {
		object AlreadyHasThatCode:CodeAssignResult()
		class CodeNotFree(val occupiedByEmail:String):CodeAssignResult()
		object SuccessChanged:CodeAssignResult()
		class FailureToChange(val oldCode:Int):CodeAssignResult()
		object SuccessReserved:CodeAssignResult()
	}
}

object DemographyInfo : Table() {
	val user = long("user").references(Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	private const val MAX_QUESTION_ID_LENGTH = 64
	val questionId = varchar("question_id", MAX_QUESTION_ID_LENGTH)

	const val MAX_RESPONSE_LENGTH = 1024
	val response = varchar("response", MAX_RESPONSE_LENGTH)

	override val primaryKey = PrimaryKey(user, questionId)
}

object QuestionnaireTemplates : LongIdTable() {
	val createdBy = long("created_by")
			.references(Accounts.id, onDelete=ReferenceOption.SET_NULL, onUpdate=ReferenceOption.CASCADE)
			.nullable()
	/** Name of the template. Derived from the XML. */
	val name = varchar("name", 128)
	val timeCreated = timestamp("time_created").defaultExpression(CurrentTimestamp())

	val templateXml = blob("template_xml")

	val CACHE: LoadingCache<Long, QuestionnaireTemplate> = CacheBuilder.newBuilder()
			.expireAfterAccess(Duration.ofHours(24L))
			.maximumSize(16L)
			.build(object : CacheLoader<Long, QuestionnaireTemplate>() {
				override fun load(key: Long): QuestionnaireTemplate {
					val bytes = transaction {
						QuestionnaireTemplates
								.slice(templateXml)
								.select { QuestionnaireTemplates.id eq key }
								.firstOrNull()?.let { row -> row[templateXml].bytes }
					} ?: throw NoSuchElementException("No such template")

					return ByteArrayInputStream(bytes).use {
						parseTemplate(it).result
					}
				}
			})

	fun invalidateParsedCache(templateId:Long) {
		CACHE.invalidate(templateId)
	}

	fun parsed(templateId:Long):QuestionnaireTemplate? {
		try {
			return CACHE.get(templateId)
		} catch (e: ExecutionException) {
			if (e.cause is NoSuchElementException) {
				return null
			}
			LOG.warn("Failed to load template from cache", e)
			return null
		}
	}
}

enum class QuestionnaireState {
	CREATED,
	RUNNING,
	CLOSED
}

object Questionnaires : LongIdTable() {
	/** User facing name. */
	val name = varchar("name", 128)
	val createdBy = long("created_by")
			.references(Accounts.id, onDelete=ReferenceOption.SET_NULL, onUpdate=ReferenceOption.CASCADE)
			.nullable()
	val timeCreated = timestamp("time_created").defaultExpression(CurrentTimestamp())
	val template = long("template")
			.references(QuestionnaireTemplates.id, onDelete=ReferenceOption.CASCADE, onUpdate=ReferenceOption.CASCADE)
	val state = enumeration("state", QuestionnaireState::class).default(QuestionnaireState.CREATED)

	val hasWines = bool("has_no_wines").default(true)
}

enum class QuestionnaireParticipationState {
	INVITED,
	STARTED,
	DONE
}

object QuestionnaireParticipants : Table() {
	val participant = long("participant")
			.references(Accounts.id, onDelete=ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	val questionnaire = long("questionnaire")
			.references(Questionnaires.id, onDelete=ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	val state = enumeration("state", QuestionnaireParticipationState::class)
			.default(QuestionnaireParticipationState.INVITED)

	val currentWineIndex = integer("wine_index").default(0)
	val currentSection = integer("section").default(0)
	val currentSectionStartedAt = timestamp("section_started_at").default(Instant.EPOCH)

	override val primaryKey: PrimaryKey = PrimaryKey(participant, questionnaire)
}

object QuestionnaireWines : LongIdTable() {
	val questionnaire = long("questionnaire")
			.references(Questionnaires.id, onDelete=ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
			.index()
	val name = varchar("name", 128)
	/** Primary code assigned to this wine for this questionnaire */
	val code = integer("code")

	/** Call in transaction. */
	fun findUniqueCode(questionnaireId:Long, primary:Int = -1):Int {
		var attempt = 0
		while (true) {
			val code = randomCode(attempt++)
			if (code != primary && QuestionnaireWines
							.select { (questionnaire eq questionnaireId) and (this@QuestionnaireWines.code eq code) }
							.empty()) {
				return code
			}
		}
	}
}

object WineParticipantAssignment : Table() {
	val questionnaire = long("questionnaire")
			.references(Questionnaires.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	val participant = long("participant")
			.references(Accounts.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	val order = integer("order")
	val wine = long("wine")
			.references(QuestionnaireWines.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)

	override val primaryKey = PrimaryKey(questionnaire, participant, order)

	init {
		uniqueIndex(questionnaire, participant, wine)
	}
}

object QuestionnaireResponses : Table() {
	/** Who created this response */
	val participant = long("participant")
			.references(Accounts.id, onDelete = ReferenceOption.RESTRICT, onUpdate = ReferenceOption.CASCADE)
	/** Into which questionnaire */
	val questionnaire = long("questionnaire")
			.references(Questionnaires.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	/** Which wine this relates to */
	val wine = long("wine")
			.references(QuestionnaireWines.id, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
	/** On which question */
	val questionId = varchar("question_id", 64)
	/** What the response was */
	/*
	Size limit is mostly arbitrary. We expect that most responses will be very short (few words),
	but not allowing longer text would be stupid. Most DB engines handle this fine, except for MySQL,
	which could have some problems. Solution = don't use MySQL.
	 */
	val response = varchar("response", 1 shl 20 /* 1MB */)

	override val primaryKey: PrimaryKey = PrimaryKey(participant, questionnaire, wine, questionId)
}

val AllTables = arrayOf(
		Accounts,
		DemographyInfo,
		QuestionnaireTemplates,
		Questionnaires,
		QuestionnaireParticipants,
		QuestionnaireWines,
		WineParticipantAssignment,
		QuestionnaireResponses
)

fun createSchemaTables() {
	transaction {
		SchemaUtils.create(*AllTables)
		SchemaUtils.createSequence(Accounts.PanelistCodeSequence)
	}
}