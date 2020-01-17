package it.unibz.vincent

import it.unibz.vincent.QuestionnaireTemplates.defaultExpression
import it.unibz.vincent.util.HASHED_PASSWORD_SIZE
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.`java-time`.CurrentTimestamp
import org.jetbrains.exposed.sql.`java-time`.timestamp
import org.jetbrains.exposed.sql.function
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

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

	val timeRegistered = timestamp("time_registered")
	val timeLastLogin = timestamp("time_last_login")
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
	val createdBy = long("created_by").references(Accounts.id)
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
	val participant = long("participant").references(Accounts.id)
	val questionnaire = long("questionnaire").references(Questionnaires.id)
	val state = enumeration("state", QuestionnaireParticipationState::class)

	override val primaryKey: PrimaryKey = PrimaryKey(participant, questionnaire)
}

object QuestionnaireResponses : Table() {
	/** Who created this response */
	val participant = long("participant").references(Accounts.id)
	/** Into which questionnaire */
	val questionnaire = long("questionnaire").references(Questionnaires.id)
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
				QuestionnaireTemplates,
				Questionnaires,
				QuestionnaireParticipants,
				QuestionnaireResponses
		)
	}
}