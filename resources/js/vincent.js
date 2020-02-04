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
});