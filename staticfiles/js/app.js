async function loadDashboardData() {
    try {
        // Fetch Data in Parallel
        const [circlesRes, usersRes, instancesRes] = await Promise.all([
            fetch('/api/circles/'),
            fetch('/api/users/'),
            fetch('/api/instances/')
        ]);

        const circles = await circlesRes.json();
        const users = await usersRes.json();
        const instances = await instancesRes.json();

        // Update Stats
        document.getElementById('stats-circles').textContent = circles.length || 0;
        document.getElementById('stats-users').textContent = users.length || 0;
        document.getElementById('stats-integrations').textContent = instances.length || 0;

        // Render Circles List
        const circlesList = document.getElementById('circles-list');
        if (circles.length > 0) {
            circlesList.innerHTML = circles.map(circle => `
                <div class="px-6 py-4 flex items-center justify-between hover:bg-gray-700 transition-colors">
                    <div>
                        <h4 class="text-sm font-medium text-white">${circle.name}</h4>
                        <p class="text-xs text-gray-400">${circle.purpose || 'No purpose defined'}</p>
                    </div>
                    <span class="text-xs text-gray-500">${new Date(circle.created_at).toLocaleDateString()}</span>
                </div>
            `).join('');
        } else {
            circlesList.innerHTML = '<div class="p-6 text-center text-gray-500">No circles found.</div>';
        }

        // Render Services (Instances) List
        const servicesList = document.getElementById('services-list');
        if (instances.length > 0) {
            servicesList.innerHTML = instances.map(instance => `
                <div class="px-6 py-4 flex items-center justify-between hover:bg-gray-700 transition-colors">
                    <div class="flex items-center">
                        <div class="w-8 h-8 rounded bg-gray-600 flex items-center justify-center mr-3 text-xs font-bold text-white">
                            ${instance.service.provider_type.substring(0, 2).toUpperCase()}
                        </div>
                        <div>
                            <h4 class="text-sm font-medium text-white">${instance.name}</h4>
                            <p class="text-xs text-gray-400">${instance.service.name}</p>
                        </div>
                    </div>
                    <span class="px-2 py-1 text-xs font-medium rounded-full bg-gray-700 text-gray-300 border border-gray-600">
                        ${instance.service.auth_method}
                    </span>
                </div>
            `).join('');
        } else {
            servicesList.innerHTML = '<div class="p-6 text-center text-gray-500">No services connected.</div>';
        }

        // Fetch and Render Files for Storage Instances
        const filesList = document.getElementById('files-list');
        const storageInstances = instances.filter(i => i.service.provider_type === 'storage');

        if (storageInstances.length > 0) {
            filesList.innerHTML = '<div class="p-6 text-center text-gray-500 animate-pulse">Fetching files from Infomaniak...</div>';

            let allFiles = [];
            for (const instance of storageInstances) {
                try {
                    const res = await fetch(`/api/instances/${instance.id}/files/`);
                    if (res.ok) {
                        const files = await res.json();
                        allFiles = [...allFiles, ...files];
                    }
                } catch (err) {
                    console.error(`Failed to fetch files for instance ${instance.id}`, err);
                }
            }

            if (allFiles.length > 0) {
                filesList.innerHTML = allFiles.map(file => `
                    <div class="px-6 py-4 flex items-center justify-between hover:bg-gray-700 transition-colors">
                        <div class="flex items-center">
                            <div class="mr-3 text-gray-400">
                                <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"></path></svg>
                            </div>
                            <div>
                                <h4 class="text-sm font-medium text-white">${file.name}</h4>
                                <p class="text-xs text-gray-400">${file.mime_type} • ${(file.size / 1024).toFixed(1)} KB</p>
                            </div>
                        </div>
                        <a href="${file.webview_link || '#'}" target="_blank" class="text-xs text-blue-400 hover:text-blue-300">Open</a>
                    </div>
                `).join('');
            } else {
                filesList.innerHTML = '<div class="p-6 text-center text-gray-500">No files found in connected drives.</div>';
            }
        } else {
            filesList.innerHTML = '<div class="p-6 text-center text-gray-500">Connect a Storage Service to see files.</div>';
        }

    } catch (error) {
        console.error('Error loading dashboard data:', error);
    }
}
