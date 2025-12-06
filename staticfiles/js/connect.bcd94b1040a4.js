document.addEventListener('DOMContentLoaded', () => {
    const serviceSelect = document.getElementById('service-select');
    const nameInput = document.getElementById('instance-name');
    const apiKeyField = document.getElementById('api-key-field');
    const instructionPlaceholder = document.getElementById('instruction-placeholder');
    const instructionInfomaniak = document.getElementById('instruction-infomaniak');
    const form = document.getElementById('connect-form');
    const btnSpinner = document.getElementById('btn-spinner');
    const btnText = document.getElementById('btn-text');
    const errorMessage = document.getElementById('error-message');

    let services = [];

    // 1. Load Services
    async function loadServices() {
        try {
            const res = await fetch('/api/services/');
            services = await res.json();

            services.forEach(service => {
                const option = document.createElement('option');
                option.value = service.id;
                option.textContent = service.name;
                serviceSelect.appendChild(option);
            });
        } catch (err) {
            console.error('Failed to load services', err);
            errorMessage.textContent = 'Failed to load available services.';
            errorMessage.classList.remove('hidden');
        }
    }

    loadServices();

    // 2. Handle Selection Change
    serviceSelect.addEventListener('change', () => {
        const selectedId = serviceSelect.value;
        const service = services.find(s => s.id === selectedId);

        if (service) {
            // Auto-fill name
            nameInput.value = `${service.name}`;

            // Show API Key field if applicable (assuming all are API Key for now based on user request)
            // In a real app, check service.auth_method
            apiKeyField.classList.remove('hidden');

            // Show Instructions
            instructionPlaceholder.classList.add('hidden');
            if (service.name.toLowerCase().includes('infomaniak')) {
                instructionInfomaniak.classList.remove('hidden');
            } else {
                // Fallback or other instructions
                instructionInfomaniak.classList.add('hidden');
                instructionPlaceholder.textContent = `Please consult the documentation for ${service.name} to generate an API Key.`;
                instructionPlaceholder.classList.remove('hidden');
            }
        }
    });

    // 3. Handle Submission
    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        // Reset UI
        errorMessage.classList.add('hidden');
        btnText.textContent = 'Connecting...';
        btnSpinner.classList.remove('hidden');

        const serviceId = serviceSelect.value;
        const name = nameInput.value;
        const apiKey = document.getElementById('api-key').value;

        if (!serviceId || !apiKey) {
            errorMessage.textContent = 'Please select a service and enter an API Token.';
            errorMessage.classList.remove('hidden');
            resetBtn();
            return;
        }

        const payload = {
            service: serviceId,
            name: name,
            configuration: {
                api_key: apiKey
            },
            // Default circle for now, or let backend handle it if optional
            // We might need to fetch a default circle or let user pick. 
            // For simplicity, we'll omit it if the model allows, or fetch the first one.
        };

        try {
            // Quick hack: Fetch first circle to satisfy foreign key if needed
            // Ideally, the user selects the circle or it's optional.
            // Checking serializer: 'circle' is in fields. If model has null=True, we are good.
            // Let's try sending without circle first.

            const res = await fetch('/api/instances/', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRFToken': getCookie('csrftoken') // Django CSRF
                },
                body: JSON.stringify(payload)
            });

            if (res.ok) {
                window.location.href = '/'; // Redirect to Dashboard
            } else {
                const data = await res.json();
                errorMessage.textContent = 'Connection failed: ' + JSON.stringify(data);
                errorMessage.classList.remove('hidden');
                resetBtn();
            }
        } catch (err) {
            console.error(err);
            errorMessage.textContent = 'Network error occurred.';
            errorMessage.classList.remove('hidden');
            resetBtn();
        }
    });

    function resetBtn() {
        btnText.textContent = 'Connect Service';
        btnSpinner.classList.add('hidden');
    }

    // Helper for CSRF
    function getCookie(name) {
        let cookieValue = null;
        if (document.cookie && document.cookie !== '') {
            const cookies = document.cookie.split(';');
            for (let i = 0; i < cookies.length; i++) {
                const cookie = cookies[i].trim();
                if (cookie.substring(0, name.length + 1) === (name + '=')) {
                    cookieValue = decodeURIComponent(cookie.substring(name.length + 1));
                    break;
                }
            }
        }
        return cookieValue;
    }
});
