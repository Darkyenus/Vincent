function showDetailIfChecked(input, detail) {
	if (input.checked) {
		detail.style = "";
	} else {
		detail.style = "display: none;"
		detail
	}
}

function parentWithClass(element, className) {
	while (element && !element.classList.contains(className)) {
		element = element.parentElement;
	}
	return element;
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
        inputElements[i].oninput = updateOneOfDetails;
    }

    // Setup section count-down
    var sectionCountDownTicker = document.getElementById("section-count-down-ticker");
    var sectionButtons = document.getElementById("section-buttons");
    var sectionCountDownContainer = document.getElementById("section-count-down-container")
    if (sectionCountDownTicker && sectionButtons) {
        var remainingSeconds = sectionCountDownTicker.getAttribute("seconds") | 0;

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

    // Setup password toggles
    var passwordToggleLabels = document.getElementsByClassName("password-mask-toggle-label");
    for (var i=0, len=passwordToggleLabels.length|0; i<len; i=i+1|0) {
        var label = passwordToggleLabels[i];
        label.style=""; // Show it
        // Make it work through keyboard
        label.addEventListener("keyup", function(e) {
			if (e.keyCode == 13) {
				e.target.click();
			}
		});
    }

    var passwordToggles = document.getElementsByClassName("password-mask-toggle");
    for (var i=0, len=passwordToggles.length|0; i<len; i=i+1|0) {
        var passwordToggle = passwordToggles[i];
        var passwordFieldId = passwordToggle.getAttribute("password-field");
        var passwordField = document.getElementById(passwordFieldId);
        if (passwordField) {
            // Wire it up
			(function (passwordToggle, passwordField) {
				passwordToggle.onchange = function(e) {
					if (passwordToggle.checked) {
						passwordField.type = "text";
						passwordField.focus();
					} else {
						passwordField.type = "password";
					}
				};

				var ourPasswordParent = parentWithClass(passwordField, "password-container");
				ourPasswordParent.addEventListener('focusout', function(e) {
					// User may lose focus because of a click on toggle button. We must not make it unchecked yet,
					// because the focus loss is handled before click is and it would mess everything up.
					setTimeout(function() {
						var newFocusedPasswordContainer = parentWithClass(document.activeElement, "password-container");
						if (ourPasswordParent !== newFocusedPasswordContainer) {
							// Our password parent has lost focus, hide goodies
							passwordField.type = "password";
                            passwordToggle.checked = false;
						}
					}, 1);
                });
			}) (passwordToggle, passwordField);
        }
    }

    // Scroll to the missing field, if any
    var requiredQuestions = document.getElementsByClassName("section-part-required");
    if (requiredQuestions.length > 0) {
        requiredQuestions[0].scrollIntoView();
    }
});