function showDetailIfCheckedFunction(input, detail) {
	return function() {
		if (input.checked) {
			showElement(detail);
		} else {
			hideElement(detail);
		}
	};
}

function parentWithClass(element, className) {
	while (element && !element.classList.contains(className)) {
		element = element.parentElement;
	}
	return element;
}

function setupSuperCompactForm(formInput) {
	var originalValue = formInput.value;
	formInput.onchange = function() {
		var newValue = formInput.value;
		if (newValue != originalValue) {
			formInput.classList.add("changed");
		} else {
			formInput.classList.remove("changed");
		}
	};
}

function enterClickEvent(element) {
	element.addEventListener("keyup", function(e) {
        if (e.keyCode == 13) {
            e.target.click();
        }
    });
}

function hideElement(element) {
	if (element) {
		element.classList.add("hidden");
	}
}
function hideElements(elements) {
	for (var i=0, len=elements.length|0; i<len; i=i+1|0) {
		hideElement(elements[i]);
    }
}

function showElement(element) {
	if (element) {
		element.classList.remove("hidden");
	}
}
function showElements(elements) {
	for (var i=0, len=elements.length|0; i<len; i=i+1|0) {
		showElement(elements[i]);
    }
}

function updateTimerElement(element, remainingSeconds) {
	var minutes = (remainingSeconds / 60) | 0;
    var seconds = (remainingSeconds % 60) | 0;
    if (minutes < 10) {
        minutes = "0"+minutes;
    }
    if (seconds < 10) {
        seconds = "0"+seconds;
    }
    element.textContent = minutes+":"+seconds;
}

function startTimerCountdown(element, seconds, onDone) {
	var remainingSeconds = seconds | 0;
	updateTimerElement(element, remainingSeconds);

	if (remainingSeconds <= 0) {
		if (onDone) {
			onDone();
		}
		return;
	}

	function tick() {
		remainingSeconds -= 1;
        if (remainingSeconds <= 0) {
            updateTimerElement(element, 0);
            if (onDone) {
                window.setTimeout(onDone, 500);
            }
        } else {
            updateTimerElement(element, remainingSeconds);
            window.setTimeout(tick, 1000);
        }
	}

	// First tick half second, then seconds and the last bit is also half second
	window.setTimeout(tick, 500);
}

function setupTimeProgression(timeProgressionContainer) {
	var stepSeconds = timeProgressionContainer.getAttribute("time-progression-step-seconds") | 0;
	var stepMs = stepSeconds * 1000;

	var examples = timeProgressionContainer.getElementsByClassName("time-progression-example");
	var timeProgressionDone = timeProgressionContainer.getElementsByClassName("time-progression-done")[0];
	var timeProgressionTimer = timeProgressionContainer.getElementsByClassName("time-progression-timer")[0];
	var timeProgressionStarts = timeProgressionContainer.getElementsByClassName("time-progression-start");
	var timeProgressionParts = timeProgressionContainer.getElementsByClassName("time-progression-part");
	var timeProgressionEnds = timeProgressionContainer.getElementsByClassName("time-progression-end");

	showElements(examples);
	showElements(timeProgressionStarts);
	var partCount = timeProgressionParts.length | 0;

	var started = false;
	var startButton = timeProgressionStarts[0].getElementsByTagName("button")[0];
	enterClickEvent(startButton);
	startButton.addEventListener("click", function (e) {
		e.preventDefault();
		if (started) {
			return;
		}
		started = true;

		// Hide example & start & show first
		hideElements(examples);
		hideElements(timeProgressionStarts);
		showElement(timeProgressionTimer);
		showElement(timeProgressionParts[0]);

		var shownPart = 0;
		function showNextPart() {
			hideElement(timeProgressionParts[shownPart]);
			shownPart += 1;
			if (shownPart < partCount) {
				showElement(timeProgressionParts[shownPart]);
				startTimerCountdown(timeProgressionTimer, stepSeconds, showNextPart);
			} else {
				hideElement(timeProgressionTimer);
				showElements(timeProgressionEnds);
				timeProgressionDone.checked = true;
			}
		}
		startTimerCountdown(timeProgressionTimer, stepSeconds, showNextPart);
	});
}

function setupFormConfirmMessage(form, message) {
	form.onsubmit = function(event) {
		if (!confirm(message)) {
			event.preventDefault();
		}
	};
}

function setupPasswordToggle(passwordToggle) {
    var passwordFieldId = passwordToggle.getAttribute("password-field");
    var passwordField = document.getElementById(passwordFieldId);
    if (!passwordField) {
        return;
    }

    passwordToggle.onchange = function(e) {
        if (passwordToggle.checked) {
            passwordField.type = "text";
            passwordField.focus();
        } else {
            passwordField.type = "password";
        }
    };

    var ourPasswordParent = parentWithClass(passwordField, "password-container");
    if (!ourPasswordParent) {
        return;
    }

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
}

window.addEventListener('DOMContentLoaded', (event) => {
	// Setup confirm buttons
    var confirmForms = document.getElementsByClassName("confirmed-submit");
    for (var i=0, len=confirmForms.length|0; i<len; i=i+1|0) {
        var form = confirmForms[i];
        var confirmMessage = form.getAttribute("confirmation-message");
        if (confirmMessage) {
            setupFormConfirmMessage(form, confirmMessage);
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
			var updateHandler = showDetailIfCheckedFunction(radioElement, detailBlock);
			updateHandler();
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

		showElement(sectionCountDownTicker);
		hideElement(sectionButtons);
		startTimerCountdown(sectionCountDownTicker, remainingSeconds, function() {
			hideElement(sectionCountDownContainer);
			hideElement(sectionCountDownTicker);
			showElement(sectionButtons);
		});
    }

    // Setup password toggles
    var passwordToggleLabels = document.getElementsByClassName("password-mask-toggle-label");
    for (var i=0, len=passwordToggleLabels.length|0; i<len; i=i+1|0) {
        var label = passwordToggleLabels[i];
        showElement(label);
        enterClickEvent(label);
    }

    var passwordToggles = document.getElementsByClassName("password-mask-toggle");
    for (var i=0, len=passwordToggles.length|0; i<len; i=i+1|0) {
        setupPasswordToggle(passwordToggles[i]);
    }

    // Scroll to the missing field, if any
    var requiredQuestions = document.getElementsByClassName("section-part-required");
    if (requiredQuestions.length > 0) {
        requiredQuestions[0].scrollIntoView();
    }

    // Setup super-compact forms
    var superCompactForms = document.getElementsByClassName("super-compact-input");
    for (var i=0, len=superCompactForms.length|0; i<len; i=i+1|0) {
        setupSuperCompactForm(superCompactForms[i]);
    }

    // Setup time progression
    var timeProgressionContainers = document.getElementsByClassName("time-progression-container");
    for (var i=0, len=timeProgressionContainers.length|0; i<len; i=i+1|0) {
        setupTimeProgression(timeProgressionContainers[i]);
    }

    // Fill login time-zone
    var timeZoneInputs = document.getElementsByClassName("put-timezone-here");
    for (var i=0, len=timeZoneInputs.length|0; i<len; i=i+1|0) {
        timeZoneInputs[i].value = -new Date().getTimezoneOffset();
    }
});