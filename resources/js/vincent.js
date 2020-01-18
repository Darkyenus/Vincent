window.addEventListener('DOMContentLoaded', (event) => {
	// Setup confirm buttons
    var confirmForms = document.getElementsByClassName("confirmed-submit");
    for (var i=0, len=confirmForms.length|0; i<len; i=i+1|0) {
        var confirmMessage = confirmForms[i].getAttribute("confirmation-message")
        if (confirmMessage) {
            confirmForms[i].onsubmit = function(event) {
        	           if (!confirm(confirmMessage)) {
        	               event.preventDefault();
        	           }
        	       };
        }
    }
});