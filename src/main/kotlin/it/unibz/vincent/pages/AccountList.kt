package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import it.unibz.vincent.AccountType
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import kotlinx.html.div

const val ACCOUNT_LIST_URL = "/account-list"
const val ACCOUNT_LIST_FILTER_PARAM = "filter"
const val ACCOUNT_LIST_FILTER_REGULAR = "regular"
const val ACCOUNT_LIST_FILTER_GUEST = "guest"
const val ACCOUNT_LIST_FILTER_RESERVED = "reserved"

private fun showAccountList(exchange: HttpServerExchange) {
	val session = exchange.session()!!

	exchange.sendBase("Accounts - Vincent") { _, locale ->
		div("page-container") {
			// TODO(jp): Implement
		}
	}
}


fun RoutingHandler.setupAccountListRoutes() {
	GET(ACCOUNT_LIST_URL, accessLevel=AccountType.STAFF) { exchange ->
		showAccountList(exchange)
	}
}