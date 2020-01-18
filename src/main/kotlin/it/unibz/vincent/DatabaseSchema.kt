package it.unibz.vincent

import it.unibz.vincent.util.HASHED_PASSWORD_SIZE
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.`java-time`.CurrentTimestamp
import org.jetbrains.exposed.sql.`java-time`.timestamp
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.math.floor
import kotlin.math.log10

/** Random only for [randomCode]. Not secure, because nobody will do randomness attack on wine. */
private val randomCodeRandom = java.util.Random()

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

	val randomCodeRandom = randomCodeRandom
	return lowerBound + synchronized(randomCodeRandom) { randomCodeRandom.nextInt(upperBound - lowerBound) }
}

/** Dictates what the account can do.
 * Ordered from least to most privileged. */
enum class AccountType : Comparable<AccountType> {
	// NOTE: This is stored in DB by ordinal!
	// Do not change the order or meaning of enum values without considering backward compatibility!

	GUEST,
	NORMAL,
	STAFF,
	ADMIN
}

/** Table of registered users. */
object Accounts : LongIdTable() {
	const val MAX_NAME_LENGTH = 128
	val name = varchar("name", MAX_NAME_LENGTH)
	const val MAX_EMAIL_LENGTH = 128
	val email = varchar("email", MAX_EMAIL_LENGTH).uniqueIndex()
	val password = binary("password", HASHED_PASSWORD_SIZE)
	val accountType = enumeration("account_type", AccountType::class)
	val code: Column<Int> = integer("code").uniqueIndex()

	val timeRegistered = timestamp("time_registered")
	val timeLastLogin = timestamp("time_last_login")

	/** Call in a transaction. Gets reserved code or generates a new one. */
	fun findUniqueAccountCode(email:String):Int {
		AccountCodeReservations.slice(AccountCodeReservations.code)
				.select { AccountCodeReservations.email eq email }
				.limit(1)
				.firstOrNull()
				?.let { return it[AccountCodeReservations.code] }

		var attempt = 0
		while (true) {
			val code = randomCode(attempt++)
			if (Accounts.select { Accounts.code eq code }.empty() && AccountCodeReservations.select { AccountCodeReservations.code eq code }.empty()) {
				return code
			}
		}
	}
}

/** Account codes reserved for someone. */
object AccountCodeReservations : Table() {
	val code = integer("code")
	val email = varchar("email", Accounts.MAX_EMAIL_LENGTH).uniqueIndex()

	override val primaryKey: PrimaryKey = PrimaryKey(code)
}

object QuestionnaireTemplates : LongIdTable() {
	val createdBy = long("created_by").references(Accounts.id)
	/** Name of the template. Derived from the XML. */
	val name = varchar("name", 128)
	val timeCreated = timestamp("time_created").defaultExpression(CurrentTimestamp())

	val template_xml = blob("template_xml")
}

enum class QuestionnaireState {
	CREATED,
	RUNNING,
	CLOSED
}

object Questionnaires : LongIdTable() {
	/** User facing name. */
	val name = varchar("name", 128)
	val createdBy = long("created_by").references(Accounts.id, onDelete=ReferenceOption.SET_NULL).nullable()
	val timeCreated = timestamp("time_created").defaultExpression(CurrentTimestamp())
	val template = long("template").references(QuestionnaireTemplates.id)
	val state = enumeration("state", QuestionnaireState::class).default(QuestionnaireState.CREATED)
}

enum class QuestionnaireParticipationState {
	INVITED,
	STARTED,
	DONE
}

object QuestionnaireParticipants : Table() {
	val participant = long("participant").references(Accounts.id, onDelete=ReferenceOption.CASCADE)
	val questionnaire = long("questionnaire").references(Questionnaires.id, onDelete=ReferenceOption.CASCADE)
	val state = enumeration("state", QuestionnaireParticipationState::class).default(QuestionnaireParticipationState.INVITED)

	override val primaryKey: PrimaryKey = PrimaryKey(participant, questionnaire)
}

object QuestionnaireWines : LongIdTable() {
	val questionnaire = long("questionnaire").references(Questionnaires.id, onDelete=ReferenceOption.CASCADE).index()
	val name = varchar("name", 128)
	/** Primary code assigned to this wine for this questionnaire */
	val code1 = integer("code1")
	/** Secondary code assigned to this wine for this questionnaire (for re-testing) */
	val code2 = integer("code2")


	/** Call in transaction. */
	fun findUniqueCode(questionnaireId:Long, primary:Int = -1):Int {
		var attempt = 0
		while (true) {
			val code = randomCode(attempt++)
			if (code != primary && QuestionnaireWines
							.select { (questionnaire eq questionnaireId) and ((code1 eq code) or (code2 eq code)) }
							.empty()) {
				return code
			}
		}
	}
}

object WineParticipantAssignment : Table() {
	val questionnaire = long("questionnaire").references(Questionnaires.id)
	val participant = long("participant").references(Accounts.id)
	val wine = long("wine").references(QuestionnaireWines.id)
	val order = integer("order")

	override val primaryKey = PrimaryKey(questionnaire, participant, wine)
}

object QuestionnaireResponses : Table() {
	/** Who created this response */
	val participant = long("participant").references(Accounts.id)
	/** Into which questionnaire */
	val questionnaire = long("questionnaire").references(Questionnaires.id)
	/** Which wine this relates to */
	val wine = long("wine").references(QuestionnaireWines.id)
	/** On which question */
	val questionId = varchar("question_id", 64)
	/** What the response was */
	/*
	Size limit is mostly arbitrary. We expect that most responses will be very short (few words),
	but not allowing longer text would be stupid. Most DB engines handle this fine, except for MySQL,
	which could have some problems. Solution = don't use MySQL.
	 */
	val response = varchar("response", 1 shl 20 /* 1MB */)

	override val primaryKey: PrimaryKey = PrimaryKey(participant, questionnaire, questionId)
}

fun createSchemaTables() {
	transaction {
		SchemaUtils.create(
				Accounts,
				AccountCodeReservations,
				QuestionnaireTemplates,
				Questionnaires,
				QuestionnaireParticipants,
				QuestionnaireWines,
				WineParticipantAssignment,
				QuestionnaireResponses
		)
	}
}