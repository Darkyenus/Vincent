function showDetailIfChecked(input, detail) {
	if (input.checked) {
		detail.style = "";
	} else {
		detail.style = "display: none;"
		detail
	}
}

window.addEventListener('DOMContentLoaded', (event) => {
	// Setup confirm buttons
    var confirmForms = document.getElementsByClassName("confirmed-submit");
    for (var i=0, len=confirmForms.length|0; i<len; i=i+1|0) {
        var confirmMessage = confirmForms[i].getAttribute("confirmation-message")
        if (confirmMessage) {
            (function (confirmMessage, i) { // Stupidity guard
	            confirmForms[i].onsubmit = function(event) {
	                       if (!confirm(confirmMessage)) {
	                           event.preventDefault();
	                       }
	                   };
        	}) (confirmMessage, i); // Stupidity guard
        }
    }

    // Setup one-of-detail switching
    var oneOfDetailBlocks = document.getElementsByClassName("one-of-detail");
    var updateHandlers = [];
    for (var i=0, len=oneOfDetailBlocks.length|0; i<len; i=i+1|0) {
		var detailBlock = oneOfDetailBlocks[i];
		var radioId = detailBlock.getAttribute("oneOfDetailFor");
		var radioElement = document.getElementById(radioId);
		if (radioElement) {
			showDetailIfChecked(radioElement, detailBlock);
			var updateHandler =
				(function (detailBlock, radioElement) { // Stupidity guard
					return function() {
						showDetailIfChecked(radioElement, detailBlock);
					};
				}) (detailBlock, radioElement); // Stupidity guard
			updateHandlers.push(updateHandler);
		}
    }

	var updateOneOfDetails = function() {
		for (var i=0, len=updateHandlers.length|0; i<len; i=i+1|0) {
			updateHandlers[i]();
		}
	};
    var inputElements = document.getElementsByClassName("one-of-detail-radio");
    for (var i=0, len=inputElements.length|0; i<len; i=i+1|0) {
        var input = inputElements[i];
        if (input) {
            input.oninput = updateOneOfDetails;
        }
    }

    // Setup section count-down
    var sectionCountDownTicker = document.getElementById("section-count-down-ticker");
    var sectionButtons = document.getElementById("section-buttons");
    var sectionCountDownContainer = document.getElementById("section-count-down-container")
    if (sectionCountDownTicker && sectionButtons) {
        var remainingSeconds = sectionCountDownTicker.attributes["seconds"].value | 0;

		if (remainingSeconds > 0) {
			sectionCountDownTicker.style = ""; // Show the ticker
            sectionButtons.style = "display: none;"; // Hide the buttons
			// Start ticking
			var tickerIntervalId = 0;

			function ticker() {
				remainingSeconds -= 1;
				if (remainingSeconds <= 0) {
					// Done
					window.clearInterval(tickerIntervalId);
					sectionCountDownTicker.style = "display: none;"; // Hide the ticker
                    sectionButtons.style = ""; // Show the buttons
                    if (sectionCountDownContainer) { // Hide whole section, if present
                        sectionCountDownContainer.style = "display: none;"
                    }
				} else {
					// Update ticker
					var minutes = (remainingSeconds / 60) | 0;
					var seconds = (remainingSeconds % 60) | 0;
					if (minutes < 10) {
                        minutes = "0"+minutes;
                    }
					if (seconds < 10) {
						seconds = "0"+seconds;
					}
					sectionCountDownTicker.textContent = minutes+":"+seconds;
				}
			}

			tickerIntervalId = window.setInterval(ticker, 1000);
		} else if (sectionCountDownContainer) {
			sectionCountDownContainer.style = "display: none;"
		}
    }
});