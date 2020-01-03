package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import it.unibz.vincent.Accounts
import it.unibz.vincent.Session
import kotlinx.html.ButtonType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.getForm
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.style

/**
 *
 */
fun HttpServerExchange.home(session: Session) {
	val userName = session.get(Accounts.name)
	val userLevel = session.get(Accounts.accountType)

	sendBase { _, _ ->
		div("container") {
			h1 { +"Welcome home user $userName" }



		}

		div("container") {
			style = "margin-top: 5rem"
			getForm(action="/", classes = "o3 w6 column") {
				hiddenInput(name="logout") { value="true" }
				button(type=ButtonType.submit, classes="dangerous u-centered"){ style = "min-width: 50%"; +"Logout" }
			}
			/* Probably not needed and would just confuse people
			getForm(action="/", classes = "w6 column") {
				hiddenInput(name="logout-fully") { value="true" }
				button(type=ButtonType.submit, classes="dangerous u-centered"){ style = "min-width: 50%"; +"Logout from all browsers" }
			}
			 */
		}
	}
}
